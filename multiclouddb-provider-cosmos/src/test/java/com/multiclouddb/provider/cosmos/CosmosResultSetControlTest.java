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
 * Under the strict-LCD contract:
 * <ul>
 *   <li>{@link QueryRequest#partitionKey()} is required on every request.</li>
 *   <li>{@link QueryRequest#orderBy()} is restricted to the {@code "sortKey"}
 *       field, which Cosmos maps to {@code c.id}.</li>
 *   <li>{@code limit} / TOP N is no longer supported — pagination via
 *       {@code maxPageSize} (and the client-side {@code maxResults} cap) is the
 *       only result-shaping mechanism.</li>
 * </ul>
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Queries without an explicit {@code orderBy} get {@code ORDER BY c.id ASC}
 *       appended so Cosmos results match DynamoDB's implicit sort-key ordering.</li>
 *   <li>An explicit {@code orderBy("sortKey", DESC)} maps to
 *       {@code ORDER BY c.id DESC}.</li>
 *   <li>SQL that already contains {@code ORDER BY} is not modified (idempotent).</li>
 *   <li>Aggregate and {@code GROUP BY} queries are exempt — Cosmos rejects
 *       {@code ORDER BY} on them.</li>
 * </ul>
 * <p>
 * {@code applyResultSetControl} is package-private and static — it performs only
 * string transformations with no network calls, so no Cosmos emulator is needed.
 */
class CosmosResultSetControlTest {

    private static QueryRequest queryWithPk() {
        return QueryRequest.builder().partitionKey("pk-001").build();
    }

    // ── Default ORDER BY ─────────────────────────────────────────────────────

    @Test
    @DisplayName("query without explicit orderBy appends ORDER BY c.id ASC")
    void queryAppendsDefaultOrderBy() {
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", queryWithPk());
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "Expected ORDER BY c.id ASC, got: " + result);
    }

    // ── Explicit orderBy ─────────────────────────────────────────────────────

    @Test
    @DisplayName("explicit orderBy(sortKey, DESC) maps to ORDER BY c.id DESC")
    void explicitDescOrderByMapsToCosmosId() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .orderBy("sortKey", SortDirection.DESC)
                .build();
        String result = CosmosProviderClient.applyResultSetControl(
                "SELECT * FROM c WHERE c.partitionKey = @pk", query);
        assertTrue(result.contains("ORDER BY c.id DESC"),
                "Expected ORDER BY c.id DESC, got: " + result);
        assertFalse(result.contains("ORDER BY c.id ASC"),
                "Default ORDER BY c.id ASC must not appear when explicit orderBy is set, got: " + result);
    }

    @Test
    @DisplayName("explicit orderBy(sortKey, ASC) maps to ORDER BY c.id ASC")
    void explicitAscOrderByMapsToCosmosId() {
        QueryRequest query = QueryRequest.builder()
                .partitionKey("pk-001")
                .orderBy("sortKey", SortDirection.ASC)
                .build();
        String result = CosmosProviderClient.applyResultSetControl("SELECT * FROM c", query);
        assertTrue(result.contains("ORDER BY c.id ASC"),
                "Expected ORDER BY c.id ASC, got: " + result);
    }

    // ── Idempotency: existing ORDER BY must not be duplicated ─────────────────

    @Test
    @DisplayName("SQL that already contains ORDER BY does not get a second ORDER BY appended")
    void existingOrderByIsNotDuplicated() {
        String sql = "SELECT * FROM c ORDER BY c.name ASC";
        String result = CosmosProviderClient.applyResultSetControl(sql, queryWithPk());
        assertEquals(sql, result,
                "SQL with existing ORDER BY must be returned unchanged; got: " + result);
    }

    // ── Aggregate / GROUP BY guard ────────────────────────────────────────────

    @Test
    @DisplayName("SELECT VALUE COUNT(1) does not get ORDER BY appended (Cosmos rejects it)")
    void aggregateCountDoesNotGetOrderBy() {
        String sql = "SELECT VALUE COUNT(1) FROM c";
        String result = CosmosProviderClient.applyResultSetControl(sql, queryWithPk());
        assertEquals(sql, result,
                "COUNT aggregate must not have ORDER BY appended; got: " + result);
        assertFalse(result.contains("ORDER BY"),
                "ORDER BY must not appear in COUNT aggregate query; got: " + result);
    }

    @Test
    @DisplayName("SELECT VALUE SUM(c.price) does not get ORDER BY appended")
    void aggregateSumDoesNotGetOrderBy() {
        String sql = "SELECT VALUE SUM(c.price) FROM c WHERE c.category = 'books'";
        String result = CosmosProviderClient.applyResultSetControl(sql, queryWithPk());
        assertEquals(sql, result,
                "SUM aggregate must not have ORDER BY appended; got: " + result);
    }

    @Test
    @DisplayName("GROUP BY query does not get ORDER BY appended")
    void groupByQueryDoesNotGetOrderBy() {
        String sql = "SELECT c.category, COUNT(1) AS cnt FROM c GROUP BY c.category";
        String result = CosmosProviderClient.applyResultSetControl(sql, queryWithPk());
        assertEquals(sql, result,
                "GROUP BY query must not have ORDER BY appended; got: " + result);
    }

    @Test
    @DisplayName("String literal containing 'order by' does not suppress the default ORDER BY")
    void stringLiteralWithOrderByPhraseDoesNotSuppressDefault() {
        // The string literal 'place order by friday' must NOT be mistaken for a SQL ORDER BY clause
        String sql = "SELECT * FROM c WHERE c.note = 'place order by friday'";
        String result = CosmosProviderClient.applyResultSetControl(sql, queryWithPk());
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "String literal containing 'order by' must not suppress the default ORDER BY; got: " + result);
    }

    @Test
    @DisplayName("String literal with SQL-escaped single quotes (doubled '') is fully stripped before keyword detection")
    void escapedQuoteStringLiteralDoesNotSuppressDefault() {
        // 'it''s order by' uses SQL escaped quote (''). The naive '[^']*' regex strips only
        // 'it' and leaves "s order by'" unstripped, falsely suppressing the default ORDER BY.
        String sql = "SELECT * FROM c WHERE c.note = 'it''s order by'";
        String result = CosmosProviderClient.applyResultSetControl(sql, queryWithPk());
        assertTrue(result.endsWith("ORDER BY c.id ASC"),
                "SQL-escaped single quote literal must be fully stripped; got: " + result);
    }
}
