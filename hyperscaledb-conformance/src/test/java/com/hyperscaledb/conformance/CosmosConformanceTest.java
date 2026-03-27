// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance;

import com.hyperscaledb.api.*;
import org.junit.jupiter.api.Tag;

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
    protected HyperscaleDbClient createClient() {
        HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", ENDPOINT)
                .connection("key", KEY)
                .connection("connectionMode", "gateway")
                .build();
        return HyperscaleDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, CONTAINER);
    }
}
