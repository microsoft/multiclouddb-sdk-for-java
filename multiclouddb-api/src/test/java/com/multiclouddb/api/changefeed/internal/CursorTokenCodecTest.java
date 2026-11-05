// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.TestJson;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CursorTokenCodecTest {

    private static final ProviderId COSMOS = ProviderId.fromId("cosmos");
    private static final ProviderId DYNAMO = ProviderId.fromId("dynamo");
    private static final ResourceAddress ADDR = new ResourceAddress("todoapp", "todos");
    private static final ResourceAddress OTHER_ADDR = new ResourceAddress("todoapp", "audit");
    private static final String SECRET = "private-callback-result-marker";

    @Test
    void malformedRetentionDoesNotRenderNormalJsonValues() {
        for (String value : List.of("[\"" + SECRET + "\"]", "{\"private\":\"" + SECRET + "\"}",
                "\"" + SECRET + "\"", "true")) {
            CursorExpiredException failure = assertThrows(CursorExpiredException.class,
                    () -> ChangeFeedCursor.fromToken(retentionWire(value), TestJson::read));
            assertMalformedRetention(failure);
        }
    }

    @Test
    void malformedRetentionNeverCallsContainerToString() {
        var list = new ArrayList<Object>(List.of(SECRET)) {
            @Override public String toString() { throw hostileFailure(); }
        };
        var map = new LinkedHashMap<String, Object>(Map.of("private", SECRET)) {
            @Override public String toString() { throw hostileFailure(); }
        };
        for (Object value : List.of(list, map)) {
            Map<String, Object> root = validGraph();
            root.put("e", value);
            assertMalformedRetention(assertThrows(CursorExpiredException.class,
                    () -> ChangeFeedCursor.fromToken("e30", (s, t) -> root)));
        }
    }

    @Test
    void depth999RetentionParsedWithinProfileIsMalformedWithoutRecursiveRendering() {
        String wire = retentionWire("[".repeat(999) + "0" + "]".repeat(999));
        var parsed = new java.util.concurrent.atomic.AtomicBoolean();
        CursorExpiredException failure = assertThrows(CursorExpiredException.class,
                () -> ChangeFeedCursor.fromToken(wire, (s, t) -> {
                    Object result = TestJson.read(s, t);
                    parsed.set(true);
                    return result;
                }));
        assertTrue(parsed.get(), "total depth 1000 must reach SDK semantic validation");
        assertMalformedRetention(failure);
    }

    @Test
    void validationSanitizesMapEntryAccessFailures() {
        for (String access : List.of("entrySet", "getKey", "getValue")) {
            RuntimeException original = hostileFailure();
            Map<String, Object> root = new java.util.AbstractMap<>() {
                @Override public Set<Entry<String, Object>> entrySet() {
                    if (access.equals("entrySet")) throw original;
                    return Set.of(new SimpleImmutableEntry<String, Object>("v", 1) {
                        @Override public String getKey() {
                            if (access.equals("getKey")) throw original;
                            return super.getKey();
                        }
                        @Override public Object getValue() { throw original; }
                    });
                }
            };
            assertAccessFailure(root, original);
        }
        RuntimeException original = hostileFailure();
        Map<String, Object> root = new java.util.AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() {
                return new java.util.AbstractSet<>() {
                    @Override public Iterator<Entry<String, Object>> iterator() { throw original; }
                    @Override public int size() { return 1; }
                };
            }
        };
        assertAccessFailure(root, original);
    }

    @Test
    void semanticExtractionSanitizesEveryRootAndPartitionGet() {
        for (String field : List.of("v", "p", "i", "a", "r", "e", "s")) {
            RuntimeException original = hostileFailure();
            Map<String, Object> root = new LinkedHashMap<>(validGraph()) {
                @Override public Object get(Object key) {
                    if (field.equals(key)) throw original;
                    return super.get(key);
                }
            };
            assertAccessFailure(root, original);
        }
        for (String field : List.of("id", "c")) {
            RuntimeException original = hostileFailure();
            Map<String, Object> partition = new LinkedHashMap<>(Map.of("id", "p0", "c", "c0")) {
                @Override public Object get(Object key) {
                    if (field.equals(key)) throw original;
                    return super.get(key);
                }
            };
            Map<String, Object> root = validGraph();
            root.put("s", List.of(partition));
            assertAccessFailure(root, original);
        }
    }

    @Test
    void listIterationFailuresAreSanitizedInValidationAndSemanticExtraction() {
        for (int pass : List.of(1, 2)) {
            for (String access : List.of("iterator", "hasNext", "next")) {
                RuntimeException original = hostileFailure();
                var partitions = new ArrayList<Object>(List.of(Map.of("id", "p0"))) {
                    private int calls;
                    @Override public Iterator<Object> iterator() {
                        Iterator<Object> delegate = super.iterator();
                        if (++calls != pass) return delegate;
                        if (access.equals("iterator")) throw original;
                        return new Iterator<>() {
                            @Override public boolean hasNext() {
                                if (access.equals("hasNext")) throw original;
                                return delegate.hasNext();
                            }
                            @Override public Object next() { throw original; }
                        };
                    }
                };
                Map<String, Object> root = validGraph();
                root.put("s", partitions);
                assertAccessFailure(root, original);
            }
        }
    }

    @Test
    void accessFailureCannotSpoofSdkSemanticOrProcessingExceptionProvenance() {
        var semantic = new CursorExpiredException(new MulticloudDbError(
                MulticloudDbErrorCategory.CURSOR_EXPIRED, SECRET, null, "fromToken", false,
                Map.of("reason", CursorTokenCodec.REASON_MALFORMED)), new IllegalArgumentException(SECRET));
        for (RuntimeException original : List.of(semantic,
                new IllegalStateException("Application JSON deserialize failed: " + SECRET),
                new UncheckedIOException(new IOException(SECRET, new IllegalArgumentException(SECRET))))) {
            original.addSuppressed(new IllegalArgumentException(SECRET));
            Map<String, Object> root = new LinkedHashMap<>(validGraph()) {
                @Override public Object get(Object key) { throw original; }
            };
            assertAccessFailure(root, original);
            Class<? extends RuntimeException> expected = original instanceof UncheckedIOException
                    ? CursorExpiredException.class : IllegalStateException.class;
            RuntimeException invoked = assertThrows(expected,
                    () -> ChangeFeedCursor.fromToken("e30", (s, t) -> { throw original; }));
            assertSanitized(invoked);
            if (invoked instanceof CursorExpiredException processing) {
                assertEquals(CursorTokenCodec.REASON_MALFORMED, processing.error().providerDetails().get("reason"));
                assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, processing.error().category());
            }
        }
    }

    @Test
    void readSideNumberAccessorFailuresAreSanitizedWithoutChangingNumericVocabulary() {
        for (String field : List.of("v", "i", "r", "e")) {
            RuntimeException original = hostileFailure();
            var number = new java.math.BigDecimal("1") {
                @Override public int intValue() { throw original; }
                @Override public long longValue() { throw original; }
                @Override public double doubleValue() { throw original; }
                @Override public String toString() { throw original; }
            };
            Map<String, Object> root = validGraph();
            root.put(field, number);
            assertAccessFailure(root, original);
        }
    }

    private static String retentionWire(String retentionJson) {
        String json = "{\"v\":1,\"p\":\"cosmos\",\"i\":" + System.currentTimeMillis()
                + ",\"a\":\"CONTINUING\",\"r\":\"db/coll\",\"s\":[{\"id\":\"p0\"}],\"e\":"
                + retentionJson + "}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> validGraph() {
        return new LinkedHashMap<>(Map.of("v", 1, "p", "cosmos", "i", System.currentTimeMillis(),
                "a", "CONTINUING", "r", "db/coll", "s", List.of(Map.of("id", "p0"))));
    }

    private static RuntimeException hostileFailure() {
        var failure = new IllegalArgumentException(SECRET, new IllegalStateException(SECRET));
        failure.addSuppressed(new IllegalStateException(SECRET));
        return failure;
    }

    private static void assertAccessFailure(Object graph, RuntimeException original) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ChangeFeedCursor.fromToken("e30", (s, t) -> graph));
        assertEquals(IllegalStateException.class, failure.getClass());
        assertEquals("Application JSON deserialize failed: result access threw "
                + original.getClass().getName() + ".", failure.getMessage());
        assertNotSame(original, failure);
        assertSanitized(failure);
    }

    private static void assertMalformedRetention(CursorExpiredException failure) {
        assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, failure.error().category());
        assertEquals(CursorTokenCodec.REASON_MALFORMED, failure.error().providerDetails().get("reason"));
        assertEquals("fromToken", failure.error().operation());
        assertFalse(failure.error().retryable());
        assertEquals("token effective-retention 'e' is not a long", failure.getMessage());
        assertEquals(failure.getMessage(), failure.error().message());
        assertSanitized(failure);
    }

    private static void assertSanitized(RuntimeException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains(SECRET));
        assertFalse(failure.toString().contains(SECRET));
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET));
    }

    private static CursorToken token(ProviderId p, ResourceAddress r, long issuedAt,
                                     CursorAnchor anchor, List<PartitionPosition> parts) {
        return new CursorToken(p, r, issuedAt, anchor, parts);
    }

    @Test
    void surrogateEscapingReturnsOriginalStringWhenNoRepairIsNeeded() throws ReflectiveOperationException {
        var escape = CursorTokenCodec.class.getDeclaredMethod("escapeSurrogates", String.class);
        escape.setAccessible(true);
        for (String text : List.of("", "ASCII", "\uD55C\uAE00", "\ud83d\ude00",
                "before\ud83d\ude00\uD55C\uAE00\ud83d\ude80after", "\\uD800")) {
            assertSame(text, escape.invoke(null, text));
        }
    }

    @Test
    void isolatedSurrogatesKeepExactEscapesAndWireRoundTrips() throws ReflectiveOperationException {
        var escape = CursorTokenCodec.class.getDeclaredMethod("escapeSurrogates", String.class);
        escape.setAccessible(true);
        String[][] fixtures = {
                {"\ud800", "\\ud800"},
                {"\udfff", "\\udfff"},
                {"\ud800start", "\\ud800start"},
                {"\udfffstart", "\\udfffstart"},
                {"mid\ud800dle", "mid\\ud800dle"},
                {"mid\udfffdle", "mid\\udfffdle"},
                {"end\ud800", "end\\ud800"},
                {"end\udfff", "end\\udfff"},
                {"\ud800\ud801", "\\ud800\\ud801"},
                {"\udc00\udfff", "\\udc00\\udfff"},
                {"\udfff\ud800", "\\udfff\\ud800"},
                {"\ud83d\ude00x\ud800y\ud83d\ude80z\udfff",
                        "\ud83d\ude00x\\ud800y\ud83d\ude80z\\udfff"},
                {"\ud800\ud83d\ude00\udfff", "\\ud800\ud83d\ude00\\udfff"},
                {"\\uD800\ud800", "\\uD800\\ud800"}
        };
        for (String[] fixture : fixtures) {
            assertEquals(fixture[1], escape.invoke(null, fixture[0]));
            CursorToken original = token(COSMOS, ADDR, 1_700_000_000_000L,
                    CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", fixture[0])));
            String wire = CursorTokenCodec.encode(original, com.multiclouddb.api.TestJson::write);
            assertEquals(original, CursorTokenCodec.decode(wire, 1_700_000_000_000L,
                    com.multiclouddb.api.TestJson::read));
        }
    }

    @Test
    @DisplayName("round-trip: encode then decode yields the same token")
    void roundTrip() {
        List<PartitionPosition> parts = List.of(
                new PartitionPosition("p0", "continuation-a"),
                new PartitionPosition("p1", null));
        CursorToken original = token(COSMOS, ADDR, 1_700_000_000_000L,
                CursorAnchor.CONTINUING, parts);

        String wire = CursorTokenCodec.encode(original, com.multiclouddb.api.TestJson::write);
        CursorToken decoded = CursorTokenCodec.decode(wire, 1_700_000_000_000L, com.multiclouddb.api.TestJson::read);

        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip: unhydrated sentinel (resource=null) preserves null resource")
    void roundTripSentinel() {
        CursorToken sentinel = token(ProviderId.fromId("multicloud"), null,
                System.currentTimeMillis(), CursorAnchor.NOW, List.of());
        String wire = CursorTokenCodec.encode(sentinel, com.multiclouddb.api.TestJson::write);
        CursorToken decoded = CursorTokenCodec.decode(wire, com.multiclouddb.api.TestJson::read);
        assertNull(decoded.resource(), "sentinel must keep null resource binding");
        assertEquals(CursorAnchor.NOW, decoded.anchor());
        assertTrue(decoded.partitions().isEmpty());
    }

    @Test
    @DisplayName("decode: null/blank token raises MALFORMED")
    void decodeBlankMalformed() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode("", com.multiclouddb.api.TestJson::read));
        assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, ex.error().category());
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: garbage base64 raises MALFORMED")
    void decodeGarbageMalformed() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode("!!!not-base64!!!", com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: valid base64 of non-JSON raises MALFORMED")
    void decodeNonJsonMalformed() {
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: future codec version raises VERSION_UNSUPPORTED")
    void decodeFutureVersionUnsupported() {
        // Hand-craft a payload with v=999
        String json = "{\"v\":999,\"p\":\"cosmos\",\"r\":\"todoapp/todos\","
                + "\"i\":1700000000000,\"a\":\"CONTINUING\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_VERSION_UNSUPPORTED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: token older than 24h raises TOKEN_AGED_OUT")
    void decodeAgedOut() {
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + CursorTokenCodec.MAX_TOKEN_AGE_MILLIS + 1;
        CursorToken old = token(COSMOS, ADDR, issuedAt, CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", "c")));
        String wire = CursorTokenCodec.encode(old, com.multiclouddb.api.TestJson::write);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, now, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_TOKEN_AGED_OUT,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: token within 24h (boundary) succeeds")
    void decodeBoundaryOk() {
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + CursorTokenCodec.MAX_TOKEN_AGE_MILLIS; // exactly at boundary, not >
        CursorToken at = token(COSMOS, ADDR, issuedAt, CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", null)));
        String wire = CursorTokenCodec.encode(at, com.multiclouddb.api.TestJson::write);
        // Should NOT throw at the exact 24h boundary (boundary is strictly >)
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now, com.multiclouddb.api.TestJson::read));
    }

    @Test
    @DisplayName("validateProviderMatch: cross-provider token raises PROVIDER_MISMATCH")
    void providerMismatch() {
        CursorToken minted = token(DYNAMO, ADDR, System.currentTimeMillis(),
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", null)));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.validateProviderMatch(minted, COSMOS));
        assertEquals(CursorTokenCodec.REASON_PROVIDER_MISMATCH,
                ex.error().providerDetails().get("reason"));
        // Error now carries runtime provider + operation for diagnostics.
        assertEquals(COSMOS, ex.error().provider());
        assertEquals("readChanges", ex.error().operation());
    }

    @Test
    @DisplayName("validateProviderMatch: matching provider succeeds")
    void providerMatchOk() {
        CursorToken minted = token(COSMOS, ADDR, System.currentTimeMillis(),
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", null)));
        assertDoesNotThrow(() -> CursorTokenCodec.validateProviderMatch(minted, COSMOS));
    }

    @Test
    @DisplayName("validateResourceMatch: wrong resource raises RESOURCE_MISMATCH")
    void resourceMismatch() {
        CursorToken minted = token(COSMOS, ADDR, System.currentTimeMillis(),
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", null)));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.validateResourceMatch(minted, OTHER_ADDR, COSMOS));
        assertEquals(CursorTokenCodec.REASON_RESOURCE_MISMATCH,
                ex.error().providerDetails().get("reason"));
        // Error now carries runtime provider + operation for diagnostics.
        assertEquals(COSMOS, ex.error().provider());
        assertEquals("readChanges", ex.error().operation());
    }

    @Test
    @DisplayName("validateResourceMatch: unhydrated sentinel (resource=null) is always accepted")
    void resourceMatchSentinel() {
        CursorToken sentinel = token(ProviderId.fromId("multicloud"), null,
                System.currentTimeMillis(), CursorAnchor.NOW, List.of());
        assertDoesNotThrow(() -> CursorTokenCodec.validateResourceMatch(sentinel, ADDR, COSMOS));
        assertDoesNotThrow(() -> CursorTokenCodec.validateResourceMatch(sentinel, OTHER_ADDR, COSMOS));
    }

    @Test
    @DisplayName("decode: missing required fields raise MALFORMED")
    void decodeMissingFieldsMalformed() {
        // Missing 'p' (provider)
        String missingP = "{\"v\":1,\"i\":1700000000000,\"a\":\"CONTINUING\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(missingP.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: malformed resource binding raises MALFORMED")
    void decodeBadResourceBindingMalformed() {
        // 'r' is not in 'database/collection' form
        String bad = "{\"v\":1,\"p\":\"cosmos\",\"r\":\"justonepart\","
                + "\"i\":1700000000000,\"a\":\"CONTINUING\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bad.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    // ---------------------------------------------------------------------
    // extendedRetention ("e") field wire-format tests
    //
    // Background: the codec's static MAX_TOKEN_AGE_MILLIS (24h) is the
    // portable baseline. ChangeFeedConfig.extendedRetention(...) lets a
    // caller opt into a longer client-side cap (up to the server-side
    // change-stream retention window). Provider readers stamp that opt-in
    // value onto every minted token via the optional "e" JSON field; the
    // decoder then uses max(24h, encoded) as the age cap. Backwards-
    // compatible: tokens without "e" still get the 24h baseline.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("round-trip: extendedRetention > 24h is preserved through encode/decode")
    void roundTripExtendedRetention() {
        long sevenDaysMs = 7L * 24L * 60L * 60L * 1000L;
        CursorToken original = new CursorToken(COSMOS, ADDR, 1_700_000_000_000L,
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", "c")),
                sevenDaysMs);
        String wire = CursorTokenCodec.encode(original, com.multiclouddb.api.TestJson::write);
        CursorToken decoded = CursorTokenCodec.decode(wire, 1_700_000_000_000L, com.multiclouddb.api.TestJson::read);
        assertEquals(sevenDaysMs, decoded.effectiveRetentionMillis(),
                "decoder must thread the extendedRetention field back through");
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("decode: token aged 5d with e=7d succeeds (would have failed under baseline cap)")
    void decodeWithinExtendedRetentionSucceeds() {
        long sevenDaysMs = 7L * 24L * 60L * 60L * 1000L;
        long fiveDaysMs = 5L * 24L * 60L * 60L * 1000L;
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + fiveDaysMs;
        CursorToken minted = new CursorToken(COSMOS, ADDR, issuedAt,
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", "c")),
                sevenDaysMs);
        String wire = CursorTokenCodec.encode(minted, com.multiclouddb.api.TestJson::write);
        // 5d > 24h baseline but < 7d configured window — must NOT throw.
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now, com.multiclouddb.api.TestJson::read),
                "token within the encoded retention window must decode");
    }

    @Test
    @DisplayName("decode: token aged past extendedRetention still raises TOKEN_AGED_OUT")
    void decodeBeyondExtendedRetentionExpires() {
        long sevenDaysMs = 7L * 24L * 60L * 60L * 1000L;
        long eightDaysMs = 8L * 24L * 60L * 60L * 1000L;
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + eightDaysMs;
        CursorToken minted = new CursorToken(COSMOS, ADDR, issuedAt,
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", "c")),
                sevenDaysMs);
        String wire = CursorTokenCodec.encode(minted, com.multiclouddb.api.TestJson::write);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, now, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_TOKEN_AGED_OUT,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: legacy v1 token without 'e' still capped at 24h baseline")
    void decodeLegacyTokenAppliesBaselineCap() {
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + CursorTokenCodec.MAX_TOKEN_AGE_MILLIS + 1;
        // Hand-craft a payload without the "e" field — the wire shape older
        // versions of the codec produced.
        String json = "{\"v\":1,\"p\":\"cosmos\",\"r\":\"todoapp/todos\","
                + "\"i\":" + issuedAt + ",\"a\":\"CONTINUING\","
                + "\"s\":[{\"id\":\"p0\",\"c\":\"c\"}]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, now, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_TOKEN_AGED_OUT,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: 'e' shorter than baseline is silently clamped up to the 24h floor")
    void decodeShortExtendedRetentionClampedToBaseline() {
        long oneHourMs = 60L * 60L * 1000L;
        long issuedAt = 1_000_000_000_000L;
        // Aged 12h: well under the 24h baseline floor, but past the bogus 1h
        // value in "e". A correct decoder must NOT expire this — the floor
        // protects the portable 24h contract even if a buggy mint site
        // shortens the field.
        long now = issuedAt + 12L * 60L * 60L * 1000L;
        String json = "{\"v\":1,\"p\":\"cosmos\",\"r\":\"todoapp/todos\","
                + "\"i\":" + issuedAt + ",\"a\":\"CONTINUING\","
                + "\"e\":" + oneHourMs + ",\"s\":[{\"id\":\"p0\",\"c\":\"c\"}]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now, com.multiclouddb.api.TestJson::read),
                "a too-small 'e' must clamp up to the 24h portable floor");
    }

    @Test
    @DisplayName("decode: non-numeric 'e' raises MALFORMED")
    void decodeMalformedExtendedRetentionString() {
        String json = "{\"v\":1,\"p\":\"cosmos\",\"r\":\"todoapp/todos\","
                + "\"i\":1700000000000,\"a\":\"CONTINUING\","
                + "\"e\":\"not-a-number\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L, com.multiclouddb.api.TestJson::read));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: non-positive 'e' raises MALFORMED without rendering its value")
    void decodeMalformedExtendedRetentionNegative() {
        for (String value : List.of("0", "-1")) {
            CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                    () -> ChangeFeedCursor.fromToken(retentionWire(value), TestJson::read));
            assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, ex.error().category());
            assertEquals(CursorTokenCodec.REASON_MALFORMED, ex.error().providerDetails().get("reason"));
            assertEquals("token effective-retention 'e' must be > 0", ex.getMessage());
            assertSanitized(ex);
        }
    }

    @Test
    @DisplayName("encode: baseline-retention tokens omit 'e' for wire compatibility")
    void encodeBaselineOmitsExtendedRetentionField() {
        // A token built with the 5-arg constructor (no opt-in) must encode
        // to a JSON payload that does NOT contain the "e" key, so older
        // decoders that do not understand the field continue to read it
        // identically.
        CursorToken baseline = new CursorToken(COSMOS, ADDR, 1_700_000_000_000L,
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", "c")));
        String wire = CursorTokenCodec.encode(baseline, com.multiclouddb.api.TestJson::write);
        String json = new String(Base64.getUrlDecoder().decode(wire),
                StandardCharsets.UTF_8);
        assertFalse(json.contains("\"e\""),
                "baseline-retention tokens must not include the 'e' field: " + json);
    }
}
