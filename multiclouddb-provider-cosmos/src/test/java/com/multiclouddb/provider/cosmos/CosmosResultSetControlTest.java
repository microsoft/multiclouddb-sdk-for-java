// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code applyResultSetControl} method in
 * {@link CosmosProviderClient}.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Partition-scoped queries without an explicit {@code orderBy} get
 *       {@code ORDER BY c.id ASC} appended to match DynamoDB's implicit
 *       range-key ordering within a partition.</li>
 *   <li>Cross-partition / unfiltered queries do <em>not</em> receive a default
 *       {@code ORDER BY} — adding one would increase RU cost and alter
 *       pagination behaviour.</li>
 *   <li>An explicit {@code orderBy} is always honoured, regardless of whether
 *       {@code partitionKey} is set.</li>
 *   <li>{@code limit} is correctly rewritten to Cosmos {@code TOP N}.</li>
 * </ul>
 * <p>
 * {@code applyResultSetControl} is package-private and performs only string
 * transformations — it never makes a network call. The test constructs a
 * {@link CosmosProviderClient} pointing at the Cosmos Emulator endpoint; the
 * Azure SDK does not open a connection until the first API call is made, so
 * this is safe to use in offline unit tests.
 */
class CosmosResultSetControlTest {

    // Emulator well-known key — safe to hardcode in tests.
    private static final String EMULATOR_ENDPOINT = "https://localhost:8081";
    private static final String EMULATOR_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    private static CosmosProviderClient client;

    @BeforeAll
    static void buildClient() {
        MulticloudDbClientConfig cfg = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", EMULATOR_ENDPOINT)
                .connection("connectionMode", "gateway")
                .connection("key", EMULATOR_KEY)
                .build();
        // CosmosClient construction is lazy — no network call is made here.
        client = new CosmosProviderClient(cfg);
    }

    // ── Default ORDER BY for partition-scoped queries ─────────────────────────

    @Test
    @DisplayName("partition-scoped query without explicit orderBy appends ORDER BY c.id ASC")
    void partitionScopedQueryAppendsDefaultOrderBy() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .build();
        String result = client.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "Expected ORDER BY c.id ASC for partition-scoped query, got: " + result);
    }

    @Test
    @DisplayName("cross-partition query without explicit orderBy does NOT append ORDER BY")
    void crossPartitionQueryWithoutExplicitOrderByHasNoOrderBy() {
        QueryRequest query = QueryRequest.builder().build(); // no partitionKey
        String result = client.applyResultSetControl("SELECT * FROM c", query);
        assertFalse(result.toUpperCase().contains("ORDER BY"),
                "Cross-partition query should not receive a default ORDER BY, got: " + result);
    }

    // ── Explicit orderBy ─────────────────────────────────────────────────────

    @Test
    @DisplayName("explicit orderBy overrides default for partition-scoped query")
    void explicitOrderByOverridesDefaultForPartitionScoped() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .orderBy("name", SortDirection.DESC)
                .build();
        String result = client.applyResultSetControl("SELECT * FROM c WHERE c.partitionKey = @pk", query);
        assertTrue(result.contains("ORDER BY c.name DESC"),
                "Expected explicit ORDER BY c.name DESC, got: " + result);
        assertFalse(result.contains("ORDER BY c.id ASC"),
                "Default ORDER BY c.id ASC must not appear when explicit orderBy is set, got: " + result);
    }

    @Test
    @DisplayName("explicit orderBy applied for cross-partition query")
    void explicitOrderByAppliedForCrossPartitionQuery() {
        QueryRequest query = QueryRequest.builder()
                .orderBy("createdAt", SortDirection.ASC)
                .build();
        String result = client.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.contains("ORDER BY c.createdAt ASC"),
                "Expected ORDER BY c.createdAt ASC, got: " + result);
    }

    // ── TOP N ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("limit rewrites SELECT * to SELECT TOP N *")
    void limitAppliedToSelectStar() {
        QueryRequest query = QueryRequest.builder().limit(10).build();
        String result = client.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.startsWith("SELECT TOP 10 *"),
                "Expected SELECT TOP 10 *, got: " + result);
    }

    @Test
    @DisplayName("limit rewrites SELECT VALUE c to SELECT TOP N VALUE c")
    void limitAppliedToSelectValueC() {
        QueryRequest query = QueryRequest.builder().limit(5).build();
        String result = client.applyResultSetControl("SELECT VALUE c FROM c", query);
        assertTrue(result.startsWith("SELECT TOP 5 VALUE c"),
                "Expected SELECT TOP 5 VALUE c, got: " + result);
    }

    @Test
    @DisplayName("limit with partition-scoped query combines TOP N and ORDER BY c.id ASC")
    void limitAndPartitionScopedCombinesTopNAndDefaultOrderBy() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .limit(20)
                .build();
        String result = client.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.startsWith("SELECT TOP 20 *"),
                "Expected SELECT TOP 20 *, got: " + result);
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "Expected ORDER BY c.id ASC for partition-scoped query, got: " + result);
    }
}
