// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fixtures established against the original Jackson-backed v1 implementation. */
class CursorWireCompatibilityTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final String PREFIX =
            "{\"v\":1,\"p\":\"cosmos\",\"r\":\"todoapp/todos\",\"i\":1700000000000,"
                    + "\"a\":\"CONTINUING\",";

    @Test
    void ordinaryWireAndExtendedRetentionRemainStable() {
        CursorToken baseline = token(List.of(new PartitionPosition("p0", "c"),
                new PartitionPosition("p1", null)));
        assertEquals(PREFIX + "\"s\":[{\"id\":\"p0\",\"c\":\"c\"},{\"id\":\"p1\"}]}",
                json(CursorTokenCodec.encode(baseline)));
        CursorToken extended = new CursorToken(baseline.providerId(), baseline.resource(),
                NOW, baseline.anchor(), baseline.partitions(), 604_800_000);
        assertEquals(PREFIX + "\"e\":604800000,\"s\":[{\"id\":\"p0\",\"c\":\"c\"},{\"id\":\"p1\"}]}",
                json(CursorTokenCodec.encode(extended)));
    }

    @Test
    void oldEscapedUnicodeAndOpaqueContentDecodeWithoutLoss() {
        String text = "slash/ quote\" backslash\\ controls\b\f\n\r\t \u2028\u2029 "
                + "\u00e9 \ud83d\ude00 isolated:\ud800";
        String oldJson = PREFIX + "\"s\":[{\"id\":\"p0\",\"c\":"
                + "\"slash/ quote\\\" backslash\\\\ controls\\b\\f\\n\\r\\t "
                + "\\u2028\\u2029 \\u00e9 \\ud83d\\ude00 isolated:\\ud800\"}]}";
        assertEquals(text, CursorTokenCodec.decode(wire(oldJson), NOW).partitions().get(0).continuation());
        CursorToken original = token(List.of(new PartitionPosition("p0", text)));
        assertEquals(original, CursorTokenCodec.decode(CursorTokenCodec.encode(original), NOW));
    }

    @Test
    void retainsAcceptedFieldCoercionsAndLastDuplicateValue() {
        String json = "{\"v\":99,\"v\":\"1.0\",\"p\":\"cosmos\",\"i\":\"1700000000000\","
                + "\"a\":\"CONTINUING\",\"e\":86400000.9,\"s\":[{\"id\":12,\"c\":true}]}";
        CursorToken decoded = CursorTokenCodec.decode(wire(json), NOW);
        assertEquals(NOW, decoded.issuedAtEpochMillis());
        assertEquals(CursorTokenCodec.MAX_TOKEN_AGE_MILLIS, decoded.effectiveRetentionMillis());
        assertEquals(List.of(new PartitionPosition("12", "true")), decoded.partitions());
        assertEquals(decoded, CursorTokenCodec.decode(wire(json + " true"), NOW));
    }

    @Test
    void rejectsNonJsonSyntaxAndWrongTypedRequiredFields() {
        for (String json : List.of(
                "{'v':1,'p':'cosmos','i':1700000000000,'a':'NOW','s':[]}",
                "{\"v\":1,/*comment*/\"p\":\"cosmos\",\"i\":1700000000000,\"a\":\"NOW\",\"s\":[]}",
                "{\"v\":1,\"p\":true,\"i\":1700000000000,\"a\":\"NOW\",\"s\":[]}",
                "{\"v\":1,\"p\":\"cosmos\",\"i\":1700000000000,\"a\":\"NOW\",\"e\":\"86400000\",\"s\":[]}")) {
            CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                    () -> CursorTokenCodec.decode(wire(json), NOW));
            assertEquals(CursorTokenCodec.REASON_MALFORMED, ex.error().providerDetails().get("reason"));
        }
    }

    private static CursorToken token(List<PartitionPosition> positions) {
        return new CursorToken(ProviderId.COSMOS, new ResourceAddress("todoapp", "todos"),
                NOW, CursorAnchor.CONTINUING, positions);
    }

    @Test
    void publicDecoderRejectsLegacyLexicalFailures() {
        String json = "{\"v\":1,\"p\":\"cosmos\",\"i\":" + System.currentTimeMillis()
                + ",\"a\":\"CONTINUING\",\"s\":[{\"id\":\"p0\",\"c\":\"normal\"}]}";
        assertAll(List.of(json.replace("\"v\":1", "\"v\":TRUE"),
                json.replace("\"normal\"", "Null"), json.replace("\"normal\"", "fAlSe"),
                json.replace("normal", "line\nbreak"), json.replace("normal", "literal\ttab"),
                json.replace("normal", "escaped\\'quote"), json.replace("normal", "escaped\\\nline"))
                .stream().<org.junit.jupiter.api.function.Executable>map(invalid -> () -> {
                    assertThrows(java.io.IOException.class, () -> new ObjectMapper().readTree(invalid));
                    CursorExpiredException failure = assertThrows(CursorExpiredException.class,
                            () -> ChangeFeedCursor.fromToken(wire(invalid)));
                    assertEquals(CursorTokenCodec.REASON_MALFORMED, failure.error().providerDetails().get("reason"));
                }));
    }

    @Test
    void publicDecoderKeepsEscapesDuplicateLastWinsTrailingRootsAndCoercion() {
        String json = "{\"v\":99,\"v\":true,\"p\":\"cosmos\",\"i\":" + System.currentTimeMillis()
                + ",\"a\":\"CONTINUING\",\"s\":[{\"id\":12,\"c\":\"line\\nbreak\\ttab\"}]} false";
        ChangeFeedCursor cursor = ChangeFeedCursor.fromToken(wire(json));
        assertEquals(List.of(new PartitionPosition("12", "line\nbreak\ttab")), cursor.token().partitions());
    }

    private static String wire(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void publicDecoderRejectsLeadingBomLikeLegacyStringReaderAndHandlesEmptyInput() throws Exception {
        String valid = json(ChangeFeedCursor.now().toToken());
        ObjectMapper legacy = new ObjectMapper();
        assertNotNull(legacy.readTree(valid));
        String withBom = "\uFEFF" + valid;
        assertThrows(java.io.IOException.class, () -> legacy.readTree(withBom));
        assertThrows(java.io.IOException.class, () -> CursorJson.read(""));
        for (String invalid : List.of(withBom, "")) {
            CursorExpiredException failure = assertThrows(CursorExpiredException.class,
                    () -> ChangeFeedCursor.fromToken(wire(invalid)));
            assertEquals(CursorTokenCodec.REASON_MALFORMED, failure.error().providerDetails().get("reason"));
        }
    }

    @Test
    void publicDecoderPreservesLiteralAndEscapedBomInsideStringValues() throws Exception {
        String expected = "\uFEFFinside\uFEFF";
        for (String content : List.of(expected, "\\uFEFFinside\\uFEFF")) {
            String json = "{\"v\":1,\"p\":\"cosmos\",\"i\":" + System.currentTimeMillis()
                    + ",\"a\":\"CONTINUING\",\"s\":[{\"id\":\"p0\",\"c\":\"" + content + "\"}]}";
            assertEquals(expected, new ObjectMapper().readTree(json).get("s").get(0).get("c").textValue());
            ChangeFeedCursor cursor = ChangeFeedCursor.fromToken(wire(json));
            assertEquals(expected, cursor.token().partitions().get(0).continuation());
            assertEquals(cursor.token(), ChangeFeedCursor.fromToken(cursor.toToken()).token());
        }
    }

    private static String json(String wire) {
        return new String(Base64.getUrlDecoder().decode(wire), StandardCharsets.UTF_8);
    }
}
