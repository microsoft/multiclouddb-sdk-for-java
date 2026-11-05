// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.TestJson;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonSupportTest {
    private static final String SECRET = "private-callback-payload-847219";
    private static final String SECRET_TOKEN = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));

    @Test
    void callbackDiagnosticsSurvivePublicCursorBoundariesWithoutOriginalExceptions() {
        var processing = new UncheckedIOException(SECRET,
                new IOException(SECRET, new IllegalArgumentException(SECRET)));
        var contract = new IllegalArgumentException(SECRET, new IllegalStateException(SECRET));
        for (RuntimeException original : List.of(processing, contract)) {
            original.addSuppressed(new IllegalStateException(SECRET));
            original.getCause().addSuppressed(new IOException(SECRET));
            boolean isProcessing = original instanceof UncheckedIOException;
            String type = original.getClass().getName();
            Class<? extends Exception> directType = isProcessing ? IOException.class : IllegalStateException.class;
            Class<? extends Exception> decodeType = isProcessing ? CursorExpiredException.class : IllegalStateException.class;
            Exception directWrite = assertThrows(directType,
                    () -> JsonSupport.write(Map.of("payload", SECRET), value -> { throw original; }));
            Exception directRead = assertThrows(directType,
                    () -> JsonSupport.read(SECRET, (text, target) -> { throw original; }));
            assertTrue(directWrite.getMessage().contains("serialize failed: callback threw " + type));
            assertTrue(directRead.getMessage().contains("deserialize failed: callback threw " + type));
            assertNull(directWrite.getCause());
            assertNull(directRead.getCause());
            IllegalStateException encoded = assertThrows(IllegalStateException.class,
                    () -> ChangeFeedCursor.now().toToken(value -> { throw original; }));
            Throwable encodeDiagnostic = isProcessing ? encoded.getCause() : encoded;
            assertTrue(encodeDiagnostic.getMessage().contains("serialize failed: callback threw " + type));
            Exception decoded = assertThrows(decodeType,
                    () -> ChangeFeedCursor.fromToken(SECRET_TOKEN, (text, target) -> { throw original; }));
            assertTrue(decoded.getMessage().contains("deserialize failed: callback threw " + type));
            if (decoded instanceof CursorExpiredException expired) {
                assertEquals(CursorTokenCodec.REASON_MALFORMED, expired.error().providerDetails().get("reason"));
                assertEquals(com.multiclouddb.api.MulticloudDbErrorCategory.CURSOR_EXPIRED, expired.error().category());
                assertEquals("fromToken", expired.error().operation());
                assertFalse(expired.error().retryable());
                assertFalse(expired.error().toString().contains(SECRET));
                assertFalse(expired.error().providerDetails().toString().contains(SECRET));
            }
            for (Throwable failure : List.of(directWrite, directRead, encoded, decoded)) {
                assertSanitized(failure, original);
            }
        }
    }

    @Test
    void validationDiagnosticsAreFixedReasonsOnDirectAndPublicPaths() {
        Object unsupported = new Object() {
            @Override
            public String toString() { return SECRET; }
        };
        var cycleMap = new LinkedHashMap<String, Object>();
        cycleMap.put(SECRET, cycleMap);
        var cycleList = new ArrayList<Object>();
        cycleList.add(SECRET);
        cycleList.add(cycleList);
        var nullKey = new LinkedHashMap<Object, Object>();
        nullKey.put(null, SECRET);
        Object[][] fixtures = {
                {unsupported, "unsupported neutral value"},
                {List.of(unsupported), "unsupported neutral value"},
                {Map.of(unsupported, SECRET), "non-string map key"},
                {nullKey, "non-string map key"},
                {cycleMap, "cycle"},
                {cycleList, "cycle"}
        };
        for (Object[] fixture : fixtures) {
            IllegalStateException direct = assertThrows(IllegalStateException.class,
                    () -> JsonSupport.read(SECRET, (text, target) -> fixture[0]));
            IllegalStateException decoded = assertThrows(IllegalStateException.class,
                    () -> ChangeFeedCursor.fromToken(SECRET_TOKEN, (text, target) -> fixture[0]));
            for (Throwable failure : List.of(direct, decoded)) {
                assertEquals("Application JSON deserialize failed: " + fixture[1] + ".", failure.getMessage());
                assertSanitized(failure, null);
            }
        }
        for (Throwable failure : List.of(
                assertThrows(IllegalStateException.class, () -> JsonSupport.write(SECRET, value -> null)),
                assertThrows(IllegalStateException.class, () -> ChangeFeedCursor.now().toToken(value -> null)))) {
            assertEquals("Application JSON serialize failed: null serializer result.", failure.getMessage());
            assertSanitized(failure, null);
        }
    }

    @Test
    void processingDiagnosticsRequireSdkProvenanceAndPreserveBomClassification() {
        IOException foreign = new IOException(SECRET) {
            @Override
            public String getMessage() { throw new AssertionError("Must not read a foreign message"); }
        };
        assertEquals("JSON processing failed.", JsonSupport.processingFailureDetail(foreign));
        String bom = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("\uFEFF" + SECRET).getBytes(StandardCharsets.UTF_8));
        CursorExpiredException failure = assertThrows(CursorExpiredException.class,
                () -> ChangeFeedCursor.fromToken(bom, (text, target) -> {
                    throw new AssertionError("Leading BOM must fail before callback invocation");
                }));
        assertTrue(failure.getMessage().contains("deserialize failed: leading BOM"));
        assertEquals(CursorTokenCodec.REASON_MALFORMED, failure.error().providerDetails().get("reason"));
        assertSanitized(failure, foreign);
    }

    private static void assertSanitized(Throwable failure, Throwable original) {
        assertNotSame(original, failure);
        assertFalse(String.valueOf(failure.getMessage()).contains(SECRET));
        assertFalse(failure.toString().contains(SECRET));
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET));
        assertFalse(trace.toString().contains(SECRET_TOKEN));
        assertEquals(0, failure.getSuppressed().length);
        if (failure.getCause() != null) assertSanitized(failure.getCause(), original);
        for (Throwable suppressed : failure.getSuppressed()) assertSanitized(suppressed, original);
    }

    @Test
    void sequentialListsValidateWithoutCopyingOrIndexedReadsAndStillRejectCycles() throws IOException {
        var source = new java.util.LinkedList<Object>() {
            @Override
            public Object get(int index) {
                throw new AssertionError("Sequential list must not be accessed by index");
            }
        };
        var shared = new java.util.LinkedList<Object>(List.of("nested"));
        for (int i = 0; i < 2048; i++) source.add(i);
        source.add(shared);
        source.add(null);
        source.add(shared);
        var expected = new ArrayList<>(source);
        List<?> result = assertInstanceOf(List.class, JsonSupport.read("[]", (s, t) -> source));
        assertSame(source, result);
        assertEquals(expected, new ArrayList<>(source));
        assertEquals(2051, result.size());
        assertSame(shared, source.getLast());
        source.add(source);
        assertThrows(IllegalStateException.class, () -> JsonSupport.read("[]", (s, t) -> source));
    }

    @Test
    void compatibilityProfilePreservesStringContentAndRejectsUnsupportedSyntax() throws IOException {
        assertEquals("\uFEFF", JsonSupport.read("\"\\uFEFF\"", TestJson.DESERIALIZE));
        assertThrows(IOException.class, () -> JsonSupport.read("\"raw\ncontrol\"", TestJson.DESERIALIZE));
        String name = "n".repeat(50_000);
        assertEquals(Map.of(name, 1), JsonSupport.read("{\"" + name + "\":1}", TestJson.DESERIALIZE));
        assertThrows(IOException.class, () -> JsonSupport.read("{\"" + name + "n\":1}", TestJson.DESERIALIZE));
    }
    @Test
    void separatesProcessingFailuresFromContractFailuresWithoutLeakingPayloads() {
        IOException processing = assertThrows(IOException.class, () -> JsonSupport.read("secret", (s, t) -> {
            throw new UncheckedIOException(new IOException("secret"));
        }));
        assertNull(processing.getCause());
        assertFalse(processing.getMessage().contains("secret"));
        for (Object invalid : List.of(new Object(), new int[]{1}, Map.of(1, "bad"), List.of(new Object()))) {
            assertThrows(IllegalStateException.class, () -> JsonSupport.read("{}", (s, t) -> invalid));
        }
        IllegalStateException contract = assertThrows(IllegalStateException.class,
                () -> JsonSupport.read("secret", (s, t) -> { throw new IllegalArgumentException("secret"); }));
        assertNull(contract.getCause());
        assertFalse(contract.getMessage().contains("secret"));
        assertThrows(IllegalStateException.class, () -> JsonSupport.write(Map.of(), v -> null));
        assertThrows(IOException.class, () -> JsonSupport.write(Map.of(), v -> {
            throw new UncheckedIOException(new IOException("secret"));
        }));
    }

    @Test
    void validatesSharedContainersWithoutCopyingOrReadSideFiniteRestrictions() throws IOException {
        var nested = new ArrayList<Object>(List.of(1));
        var source = new LinkedHashMap<String, Object>();
        source.put("a", nested);
        source.put("b", nested);
        Map<?, ?> result = assertInstanceOf(Map.class, JsonSupport.read("{}", (s, t) -> source));
        assertSame(source, result);
        assertSame(nested, result.get("a"));
        assertEquals(List.of(1), result.get("a"));
        assertSame(result.get("a"), result.get("b"));
        assertEquals(2, source.size());
        source.put("self", source);
        assertThrows(IllegalStateException.class, () -> JsonSupport.read("{}", (s, t) -> source));
        assertEquals(Double.POSITIVE_INFINITY, JsonSupport.read("1e999", TestJson.DESERIALIZE));
        assertNull(JsonSupport.read("null", TestJson.DESERIALIZE));
        assertEquals(true, JsonSupport.read("true", TestJson.DESERIALIZE));
    }

    @Test
    void validatesDeepGraphsWithoutRecursionOrSourceMutation() throws IOException {
        Object graph = null;
        var leaf = new ArrayList<Object>();
        leaf.add(graph);
        graph = leaf;
        for (int i = 0; i < 10_000; i++) {
            graph = i % 2 == 0 ? List.of(graph) : Map.of("child", graph);
        }
        Object root = graph;
        assertSame(root, JsonSupport.read("{}", (s, t) -> root));
        assertEquals(1, leaf.size());
        assertNull(leaf.get(0));
        leaf.add(root);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> JsonSupport.read("{}", (s, t) -> root));
        assertEquals("Application JSON deserialize failed: cycle.", failure.getMessage());
        assertSanitized(failure, null);
    }

    @Test
    void publicCursorOwnsExtractedStateAfterCallbackContainersAreModified() {
        var partition = new LinkedHashMap<String, Object>();
        partition.put("id", "partition-1");
        partition.put("c", "continuation-1");
        var partitions = new ArrayList<Object>(List.of(partition));
        var root = new LinkedHashMap<String, Object>();
        long issuedAt = System.currentTimeMillis();
        root.put("v", 1);
        root.put("p", "cosmos");
        root.put("r", "database/collection");
        root.put("i", issuedAt);
        root.put("a", "CONTINUING");
        root.put("s", partitions);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ChangeFeedCursor cursor = ChangeFeedCursor.fromToken("e30", (text, target) -> {
            calls.incrementAndGet();
            return root;
        });
        assertEquals(1, calls.get());
        assertEquals(6, root.size());
        assertSame(partitions, root.get("s"));
        assertSame(partition, partitions.get(0));
        assertEquals(Map.of("id", "partition-1", "c", "continuation-1"), partition);
        CursorToken expected = new CursorToken(com.multiclouddb.api.ProviderId.COSMOS,
                new com.multiclouddb.api.ResourceAddress("database", "collection"), issuedAt,
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("partition-1", "continuation-1")));
        assertEquals(expected, cursor.token());
        String persisted = cursor.toToken(TestJson.SERIALIZE);
        partition.put("id", "changed");
        partition.put("c", "changed");
        partitions.clear();
        root.clear();
        assertEquals(expected, cursor.token());
        assertEquals(persisted, cursor.toToken(TestJson.SERIALIZE));
        assertEquals(expected, ChangeFeedCursor.fromToken(persisted, TestJson.DESERIALIZE).token());
    }

    @Test
    void inMemorySentinelNeedsNoCallbacksButPersistenceDoes() {
        var cursor = ChangeFeedCursor.now();
        assertNotNull(cursor);
        assertThrows(NullPointerException.class, () -> cursor.toToken(null));
        assertThrows(NullPointerException.class, () -> ChangeFeedCursor.fromToken("e30", null));
        String persisted = cursor.toToken(TestJson.SERIALIZE);
        var independentMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = ChangeFeedCursor.fromToken(persisted, (text, type) -> {
            try {
                return independentMapper.readValue(text, type);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        assertEquals(cursor.token(), restored.token());
        assertThrows(IllegalStateException.class,
                () -> ChangeFeedCursor.fromToken(persisted, (s, t) -> new Object()));
    }
}
