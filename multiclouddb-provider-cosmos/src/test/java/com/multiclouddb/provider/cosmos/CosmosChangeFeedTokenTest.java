// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.fasterxml.jackson.databind.node.TextNode;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.StartPosition;
import com.multiclouddb.api.changefeed.internal.ContinuationTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CosmosChangeFeed#decodeAndValidateResumeToken}.
 */
class CosmosChangeFeedTokenTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "col");

    private static String encodeEnvelope(String cursor) {
        return ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR,
                CosmosChangeFeed.buildCursorEnvelopeForTest(cursor));
    }

    private static String encodePhysicalPartitionEnvelope(String cursor) {
        var env = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        env.put("cursor", cursor);
        env.put("scope", "PhysicalPartition");
        env.put("partitionValue", "partition-A");
        return ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR, env);
    }

    private static String encodeLegacyBareString(String cursor) {
        return ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR, new TextNode(cursor));
    }

    private static ChangeFeedRequest resumeWith(String token) {
        return ChangeFeedRequest.builder(ADDR)
                .startPosition(StartPosition.fromContinuationToken(token))
                .build();
    }

    @Test
    @DisplayName("Resume with EntireCollection scope returns the wrapped Cosmos cursor unchanged")
    void resumeSameScopeReturnsCursor() {
        String token = encodeEnvelope("cosmos-native-cursor-X");
        ChangeFeedRequest req = resumeWith(token);

        String cursor = CosmosChangeFeed.decodeAndValidateResumeToken(token, req);

        assertEquals("cosmos-native-cursor-X", cursor);
    }

    @Test
    @DisplayName("Legacy token naming PhysicalPartition scope is rejected as INVALID_REQUEST")
    void legacyPhysicalPartitionScopeRejected() {
        String token = encodePhysicalPartitionEnvelope("cosmos-cursor");
        ChangeFeedRequest req = resumeWith(token);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosChangeFeed.decodeAndValidateResumeToken(token, req));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertTrue(ex.error().message().contains("PhysicalPartition scope has been removed"),
                "message should explain the removal, was: " + ex.error().message());
    }

    @Test
    @DisplayName("Legacy bare-TextNode token (no envelope) decodes and bypasses scope validation")
    void legacyBareStringTokenDecodes() {
        String token = encodeLegacyBareString("legacy-cursor");
        ChangeFeedRequest req = resumeWith(token);

        assertEquals("legacy-cursor",
                CosmosChangeFeed.decodeAndValidateResumeToken(token, req));
    }

    @Test
    @DisplayName("Envelope with empty cursor field is rejected as INVALID_REQUEST")
    void envelopeWithEmptyCursorRejected() {
        String token = encodeEnvelope("");
        ChangeFeedRequest req = resumeWith(token);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosChangeFeed.decodeAndValidateResumeToken(token, req));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertTrue(ex.error().message().contains("Cosmos cursor"),
                "message should mention the missing Cosmos cursor, was: " + ex.error().message());
    }
}