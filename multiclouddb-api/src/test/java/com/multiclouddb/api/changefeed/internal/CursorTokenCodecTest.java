// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CursorTokenCodecTest {

    private static final ProviderId COSMOS = ProviderId.fromId("cosmos");
    private static final ProviderId DYNAMO = ProviderId.fromId("dynamo");
    private static final ResourceAddress ADDR = new ResourceAddress("todoapp", "todos");
    private static final ResourceAddress OTHER_ADDR = new ResourceAddress("todoapp", "audit");

    private static CursorToken token(ProviderId p, ResourceAddress r, long issuedAt,
                                     CursorAnchor anchor, List<PartitionPosition> parts) {
        return new CursorToken(p, r, issuedAt, anchor, parts);
    }

    @Test
    @DisplayName("round-trip: encode then decode yields the same token")
    void roundTrip() {
        List<PartitionPosition> parts = List.of(
                new PartitionPosition("p0", "continuation-a"),
                new PartitionPosition("p1", null));
        CursorToken original = token(COSMOS, ADDR, 1_700_000_000_000L,
                CursorAnchor.CONTINUING, parts);

        String wire = CursorTokenCodec.encode(original);
        CursorToken decoded = CursorTokenCodec.decode(wire, 1_700_000_000_000L);

        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip: unhydrated sentinel (resource=null) preserves null resource")
    void roundTripSentinel() {
        CursorToken sentinel = token(ProviderId.fromId("multicloud"), null,
                System.currentTimeMillis(), CursorAnchor.NOW, List.of());
        String wire = CursorTokenCodec.encode(sentinel);
        CursorToken decoded = CursorTokenCodec.decode(wire);
        assertNull(decoded.resource(), "sentinel must keep null resource binding");
        assertEquals(CursorAnchor.NOW, decoded.anchor());
        assertTrue(decoded.partitions().isEmpty());
    }

    @Test
    @DisplayName("decode: null/blank token raises MALFORMED")
    void decodeBlankMalformed() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(""));
        assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, ex.error().category());
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: garbage base64 raises MALFORMED")
    void decodeGarbageMalformed() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode("!!!not-base64!!!"));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: valid base64 of non-JSON raises MALFORMED")
    void decodeNonJsonMalformed() {
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire));
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
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
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
        String wire = CursorTokenCodec.encode(old);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, now));
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
        String wire = CursorTokenCodec.encode(at);
        // Should NOT throw at the exact 24h boundary (boundary is strictly >)
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now));
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
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
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
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
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
        String wire = CursorTokenCodec.encode(original);
        CursorToken decoded = CursorTokenCodec.decode(wire, 1_700_000_000_000L);
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
        String wire = CursorTokenCodec.encode(minted);
        // 5d > 24h baseline but < 7d configured window — must NOT throw.
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now),
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
        String wire = CursorTokenCodec.encode(minted);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, now));
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
                () -> CursorTokenCodec.decode(wire, now));
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
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now),
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
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: negative 'e' raises MALFORMED")
    void decodeMalformedExtendedRetentionNegative() {
        String json = "{\"v\":1,\"p\":\"cosmos\",\"r\":\"todoapp/todos\","
                + "\"i\":1700000000000,\"a\":\"CONTINUING\","
                + "\"e\":-1,\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
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
        String wire = CursorTokenCodec.encode(baseline);
        String json = new String(Base64.getUrlDecoder().decode(wire),
                StandardCharsets.UTF_8);
        assertFalse(json.contains("\"e\""),
                "baseline-retention tokens must not include the 'e' field: " + json);
    }
}
