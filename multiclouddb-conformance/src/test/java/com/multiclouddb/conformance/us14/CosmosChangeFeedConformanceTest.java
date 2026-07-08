// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us14;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.models.ChangeFeedPolicy;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.time.Duration;

/**
 * Cosmos DB change-feed conformance, running against a Cosmos DB endpoint
 * (emulator or real account) that supports the
 * <b>All-Versions-and-Deletes (AVAD)</b> change-feed policy.
 * <p>
 * <b>Provisioning</b>: a dedicated container ({@code todoapp}/{@code todos-cf}
 * by default) is created on first use via {@link #ensureContainer()} with an
 * AVAD change-feed policy attached. The Cosmos provider always reads the
 * change feed in AVAD mode, so the container <em>must</em> be configured
 * for AVAD — without it the first call to {@code readChanges} would fail
 * with a Cosmos {@code 400 BadRequest}.
 * <p>
 * If your Cosmos environment does not support AVAD (older emulators, or
 * accounts without the required backup configuration), this entire test
 * class will fail at the {@code @BeforeAll} provisioning step — that is
 * intentional: portable change-feed semantics require AVAD on Cosmos, and
 * the SDK does not silently downgrade to a less-capable mode.
 */
@Tag("cosmos")
@Tag("emulator")
@Tag("changefeed")
class CosmosChangeFeedConformanceTest extends ChangeFeedConformanceTest {

    private static final String ENDPOINT = System.getProperty(
            "cosmos.endpoint", "https://localhost:8081");
    private static final String KEY = System.getProperty(
            "cosmos.key",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
    private static final String DATABASE = System.getProperty(
            "cosmos.database", "todoapp");
    private static final String CONTAINER = System.getProperty(
            "cosmos.changefeed.container", "todos-cf");

    /**
     * Provision the database and container on the emulator if they don't
     * already exist. The CI workflow only provisions the default
     * {@code todos} container; this subclass owns its own
     * {@code todos-cf} container so the change-feed tests can run in
     * isolation without touching the CRUD test data.
     */
    @BeforeAll
    static void ensureContainer() {
        try (CosmosClient bootstrap = new CosmosClientBuilder()
                .endpoint(ENDPOINT)
                .key(KEY)
                .gatewayMode(new GatewayConnectionConfig())
                .buildClient()) {
            bootstrap.createDatabaseIfNotExists(DATABASE);
            CosmosContainerProperties props =
                    new CosmosContainerProperties(CONTAINER, "/partitionKey");
            // AVAD retention window — long enough to absorb test latency
            // (writes → propagation → read), short enough to keep storage
            // bounded. The Cosmos provider always reads the change feed in
            // AVAD mode, so this policy is a hard prerequisite.
            //
            // 10 minutes is the maximum the Cosmos DB emulator accepts
            // (the emulator rejects retentions outside [0,10] minutes with
            // a 400 BadRequest "Invalid operation log retention duration").
            // Real Cosmos DB accepts 1 minute to 7 days, so this value is
            // safely within both ranges and is sufficient for any single
            // conformance test run.
            props.setChangeFeedPolicy(
                    ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(Duration.ofMinutes(10)));
            bootstrap.getDatabase(DATABASE).createContainerIfNotExists(props);
        }
    }

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

}
