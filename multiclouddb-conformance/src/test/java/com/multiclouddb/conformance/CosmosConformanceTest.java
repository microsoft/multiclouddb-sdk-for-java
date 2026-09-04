// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cosmos DB conformance test running against the Cosmos DB Emulator.
 * <p>
 * Prerequisites:
 * <ul>
 * <li>Cosmos DB Emulator running on https://localhost:8081</li>
 * <li>Database "todoapp" and container "todos" (partition key: /partitionKey) must
 * exist</li>
 * </ul>
 * <p>
 * To skip when emulator is unavailable, run with:
 * {@code -DskipCosmosTests=true}
 */
@Tag("cosmos")
@Tag("emulator")
class CosmosConformanceTest extends CrudConformanceTests {

    private static final String ENDPOINT = System.getProperty(
            "cosmos.endpoint", "https://localhost:8081");
    private static final String KEY = System.getProperty(
            "cosmos.key",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
    private static final String DATABASE = System.getProperty(
            "cosmos.database", "todoapp");
    private static final String CONTAINER = System.getProperty(
            "cosmos.container", "todos");

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", ENDPOINT)
                .connection("key", KEY)
                .connection("connectionMode", "gateway")
                .build();
        return MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, CONTAINER);
    }

    @Test
    @Order(28)
    @DisplayName("Cosmos result-item size overflow is normalized and atomic")
    void resultItemSizeOverflowIsNormalizedAndLeavesItemUnchanged() throws Exception {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("cosmos-result-size");
        String partitionKey = key.partitionKey();
        String id = key.sortKey();
        ObjectNode seed = JsonNodeFactory.instance.objectNode();
        seed.put("id", id);
        seed.put("partitionKey", partitionKey);
        seed.put("payload", "A".repeat(1_850_000));

        try (CosmosClient nativeClient = new CosmosClientBuilder()
                .endpoint(ENDPOINT)
                .key(KEY)
                .gatewayMode(new GatewayConnectionConfig())
                .buildClient()) {
            CosmosContainer container =
                    nativeClient.getDatabase(DATABASE).getContainer(CONTAINER);
            PartitionKey nativePartitionKey = new PartitionKey(partitionKey);
            container.createItem(seed, nativePartitionKey, new CosmosItemRequestOptions());
            try {
                try (MulticloudDbClient portableClient = createClient()) {
                    MulticloudDbException ex = assertThrows(
                            MulticloudDbException.class,
                            () -> portableClient.update(
                                    getAddress(),
                                    key,
                                    Map.of("overflow", "B".repeat(300_000))));

                    assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                            ex.error().category(), ex.error().toString());
                    assertEquals("update", ex.error().operation());
                    assertFalse(ex.error().retryable());
                    assertEquals("partial_update_extended_payload",
                            ex.error().providerDetails().get("capability"));
                    assertEquals("cosmos_result_item_size_limit",
                            ex.error().providerDetails().get("reason"));
                    assertEquals("2097152",
                            ex.error().providerDetails().get("maximumResultBytes"));
                }

                ObjectNode stored = container.readItem(
                        id, nativePartitionKey, ObjectNode.class).getItem();
                assertFalse(stored.has("overflow"),
                        "Rejected patch must leave the stored item unchanged");
            } finally {
                container.deleteItem(id, nativePartitionKey,
                        new CosmosItemRequestOptions());
            }
        }
    }
}
