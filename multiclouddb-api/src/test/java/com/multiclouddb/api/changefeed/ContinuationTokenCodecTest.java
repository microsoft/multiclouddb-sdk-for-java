// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.internal.ContinuationTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContinuationTokenCodec} envelope encode/decode and
 * cross-provider/cross-resource validation.
 */
class ContinuationTokenCodecTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db1", "col1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("encode/decode round-trips a string cursor")
    void roundTripStringCursor() {
        String token = ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR,
                MAPPER.getNodeFactory().textNode("native-cursor-abc"));
        JsonNode cursor = ContinuationTokenCodec.decode(token, ProviderId.COSMOS, ADDR);
        assertEquals("native-cursor-abc", cursor.asText());
    }

    @Test
    @DisplayName("encode/decode round-trips a structured cursor")
    void roundTripObjectCursor() {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("streamArn", "arn:aws:dynamodb:...");
        inner.putArray("shards").add(MAPPER.createObjectNode().put("shardId", "s1").put("lastSeq", "42"));

        String token = ContinuationTokenCodec.encode(ProviderId.DYNAMO, ADDR, inner);
        JsonNode out = ContinuationTokenCodec.decode(token, ProviderId.DYNAMO, ADDR);
        assertEquals("arn:aws:dynamodb:...", out.path("streamArn").asText());
        assertEquals("s1", out.path("shards").path(0).path("shardId").asText());
    }

    @Test
    @DisplayName("decode rejects a token issued for a different provider")
    void rejectsCrossProvider() {
        String token = ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR,
                MAPPER.getNodeFactory().textNode("c"));
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> ContinuationTokenCodec.decode(token, ProviderId.DYNAMO, ADDR));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertTrue(ex.getMessage().contains("provider"));
    }

    @Test
    @DisplayName("decode rejects a token issued for a different resource")
    void rejectsCrossResource() {
        String token = ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR,
                MAPPER.getNodeFactory().textNode("c"));
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> ContinuationTokenCodec.decode(token, ProviderId.COSMOS,
                        new ResourceAddress("db1", "different")));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
    }

    @Test
    @DisplayName("decode rejects a malformed (non-base64) token")
    void rejectsMalformed() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> ContinuationTokenCodec.decode("???not-base64???", ProviderId.COSMOS, ADDR));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
    }

    @Test
    @DisplayName("decode rejects a token whose schema version is unknown")
    void rejectsUnknownVersion() {
        ObjectNode forged = MAPPER.createObjectNode();
        forged.put("v", 99);
        forged.put("p", "cosmos");
        forged.put("r", "db1/col1");
        forged.put("c", "x");
        String tok;
        try {
            tok = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(forged));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> ContinuationTokenCodec.decode(tok, ProviderId.COSMOS, ADDR));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertTrue(ex.getMessage().contains("schema version"));
    }

    @Test
    @DisplayName("decode rejects null/blank tokens")
    void rejectsBlank() {
        assertThrows(MulticloudDbException.class,
                () -> ContinuationTokenCodec.decode(null, ProviderId.COSMOS, ADDR));
        assertThrows(MulticloudDbException.class,
                () -> ContinuationTokenCodec.decode("   ", ProviderId.COSMOS, ADDR));
    }
}
