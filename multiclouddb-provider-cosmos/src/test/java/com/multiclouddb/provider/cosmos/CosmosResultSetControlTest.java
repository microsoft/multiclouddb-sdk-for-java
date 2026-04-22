// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code applyResultSetControl} method in
 * {@link CosmosProviderClient}.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>All queries without an explicit {@code orderBy} get
 *       {@code ORDER BY c.id ASC} appended — both partition-scoped and
 *       cross-partition — so that Cosmos results are always sorted, matching
 *       DynamoDB's per-page sort behaviour.</li>
 *   <li>An explicit {@code orderBy} is always honoured, overriding the default.</li>
 *   <li>{@code limit} is correctly rewritten to Cosmos {@code TOP N}.</li>
 * </ul>
 * <p>
 * {@code applyResultSetControl} is package-private and static — it performs only
 * string transformations with no network calls, so no Cosmos emulator is needed.
 */
class CosmosResultSetControlTest {

    // ── Default ORDER BY ─────────────────────────────────────────────────────

    @Test
    @DisplayName("partition-scoped query without explicit orderBy appends ORDER BY c.id ASC")
    void partitionScopedQueryAppendsDefaultOrderBy() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .build();
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "Expected ORDER BY c.id ASC for partition-scoped query, got: " + result);
    }

    @Test
    @DisplayName("cross-partition query without explicit orderBy also appends ORDER BY c.id ASC")
    void crossPartitionQueryAlsoAppendsDefaultOrderBy() {
        QueryRequest query = QueryRequest.builder().build(); // no partitionKey
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "Cross-partition query should also get ORDER BY c.id ASC, got: " + result);
    }

    // ── Explicit orderBy ─────────────────────────────────────────────────────

    @Test
    @DisplayName("explicit orderBy overrides default for partition-scoped query")
    void explicitOrderByOverridesDefault() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .orderBy("name", SortDirection.DESC)
                .build();
        String result = CosmosProviderClient.applyResultSetControl(
                "SELECT * FROM c WHERE c.partitionKey = @pk", query);
        assertTrue(result.contains("ORDER BY c.name DESC"),
                "Expected explicit ORDER BY c.name DESC, got: " + result);
        assertFalse(result.contains("ORDER BY c.id ASC"),
                "Default ORDER BY c.id ASC must not appear when explicit orderBy is set, got: " + result);
    }

    @Test
    @DisplayName("explicit orderBy applied for cross-partition query")
    void explicitOrderByAppliedForCrossPartition() {
        QueryRequest query = QueryRequest.builder()
                .orderBy("createdAt", SortDirection.ASC)
                .build();
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.contains("ORDER BY c.createdAt ASC"),
                "Expected ORDER BY c.createdAt ASC, got: " + result);
    }

    // ── TOP N ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("limit rewrites SELECT * to SELECT TOP N *")
    void limitAppliedToSelectStar() {
        QueryRequest query = QueryRequest.builder().limit(10).build();
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.startsWith("SELECT TOP 10 *"),
                "Expected SELECT TOP 10 *, got: " + result);
    }

    @Test
    @DisplayName("limit rewrites SELECT VALUE c to SELECT TOP N VALUE c")
    void limitAppliedToSelectValueC() {
        QueryRequest query = QueryRequest.builder().limit(5).build();
        String result = CosmosProviderClient.applyResultSetControl("SELECT VALUE c FROM c", query);
        assertTrue(result.startsWith("SELECT TOP 5 VALUE c"),
                "Expected SELECT TOP 5 VALUE c, got: " + result);
    }

    @Test
    @DisplayName("limit with partition-scoped query combines TOP N and ORDER BY c.id ASC")
    void limitCombinesTopNAndDefaultOrderBy() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .limit(20)
                .build();
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.startsWith("SELECT TOP 20 *"),
                "Expected SELECT TOP 20 *, got: " + result);
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "Expected ORDER BY c.id ASC, got: " + result);
    }

    // ── Idempotency: existing ORDER BY must not be duplicated ─────────────────

    @Test
    @DisplayName("SQL that already contains ORDER BY does not get a second ORDER BY appended")
    void existingOrderByIsNotDuplicated() {
        QueryRequest query = QueryRequest.builder().build(); // no explicit orderBy
        String sql = "SELECT * FROM c ORDER BY c.name ASC";
        String result = CosmosProviderClient.applyResultSetControl(sql, query);
        long count = result.chars()
                .filter(c -> result.indexOf("ORDER BY", result.indexOf("ORDER BY") + 1) > 0)
                .count();
        // A simple check: result must start with the original SQL unchanged and contain ORDER BY exactly once
        assertEquals(sql, result,
                "SQL with existing ORDER BY must be returned unchanged; got: " + result);
    }

    @Test
    @DisplayName("SQL with ORDER BY from native expression passes through without modification")
    void nativeExpressionWithOrderByPassesThrough() {
        QueryRequest query = QueryRequest.builder().build();
        String sql = "SELECT * FROM c WHERE c.category = 'books' ORDER BY c.price DESC";
        String result = CosmosProviderClient.applyResultSetControl(sql, query);
        assertEquals(sql, result,
                "Native expression SQL with ORDER BY must not be modified; got: " + result);
    }
}
