// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeDocumentsTest {
    @Test
    void binaryNodesCannotEscapeAsNeutralReadOrFeedValues() {
        var factory = JsonNodeFactory.instance;
        JsonNode binary = factory.binaryNode(new byte[]{1, 2});
        for (JsonNode input : List.of(binary, factory.objectNode().set("nested", factory.arrayNode().add(binary)))) {
            for (boolean feed : List.of(false, true)) {
                MulticloudDbException failure = assertThrows(MulticloudDbException.class, () -> {
                    if (feed) NativeDocuments.changeData(input);
                    else NativeDocuments.document(input);
                });
                assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, failure.error().category());
                assertEquals(ProviderId.DYNAMO, failure.error().provider());
                assertEquals(feed ? "readChanges" : "read", failure.error().operation());
                assertFalse(failure.error().retryable());
                assertEquals(Map.of("reason", "invalid_document_payload"), failure.error().providerDetails());
                assertEquals("Provider response cannot be represented as a Document value.", failure.error().message());
                assertNull(failure.getCause());
            }
        }
    }
}
