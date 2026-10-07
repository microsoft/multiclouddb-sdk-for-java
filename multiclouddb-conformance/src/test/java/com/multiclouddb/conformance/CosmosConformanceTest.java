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
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Tag;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cosmos DB conformance test running against the Cosmos DB Emulator.
 *
 * <p>Requires the emulator on {@code https://localhost:8081} with database
 * {@code todoapp} and container {@code todos} partitioned by
 * {@code /partitionKey}.</p>
 */
@Tag("cosmos")
@Tag("emulator")
class CosmosConformanceTest extends CrudConformanceTests
        implements PartialUpdateResultLimitConformanceTest {

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
        return com.multiclouddb.api.MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, CONTAINER);
    }

    @Override
    public MulticloudDbClient createResultLimitClient() {
        return createClient();
    }

    @Override
    public ResourceAddress resultLimitAddress() {
        return getAddress();
    }

    @Override
    public PartialUpdateResultLimitScenario resultLimitScenario() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("cosmos-result-size");
        String partitionKey = key.partitionKey();
        String id = key.sortKey();
        ObjectNode seed = JsonNodeFactory.instance.objectNode();
        seed.put("id", id);
        seed.put("partitionKey", partitionKey);
        seed.put("payload", "A".repeat(1_850_000));
        PartitionKey nativePartitionKey = new PartitionKey(partitionKey);

        return new PartialUpdateResultLimitScenario(
                key,
                Map.of("overflow", "B".repeat(300_000)),
                "cosmos_result_item_size_limit",
                "2097152",
                () -> {
                    try (CosmosClient nativeClient = nativeClient()) {
                        container(nativeClient).createItem(
                                seed, nativePartitionKey, new CosmosItemRequestOptions());
                    }
                },
                () -> {
                    try (CosmosClient nativeClient = nativeClient()) {
                        ObjectNode stored = container(nativeClient).readItem(
                                id, nativePartitionKey, ObjectNode.class).getItem();
                        assertFalse(stored.has("overflow"),
                                "Rejected patch must leave the stored item unchanged");
                    }
                },
                () -> {
                    try (CosmosClient nativeClient = nativeClient()) {
                        container(nativeClient).deleteItem(
                                id, nativePartitionKey, new CosmosItemRequestOptions());
                    }
                });
    }

    private static CosmosClient nativeClient() {
        return new CosmosClientBuilder()
                .endpoint(ENDPOINT)
                .key(KEY)
                .gatewayMode(new GatewayConnectionConfig())
                .buildClient();
    }

    private static CosmosContainer container(CosmosClient client) {
        return client.getDatabase(DATABASE).getContainer(CONTAINER);
    }
}