// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests verifying that query results are returned in ascending
 * sort-key order by default — regardless of insertion order.
 * <p>
 * DynamoDB Query implicitly sorts by sort key (range key) within a partition.
 * Cosmos DB has no implicit ordering without an ORDER BY clause. This test
 * ensures all providers return items sorted by sort key ASC when no explicit
 * {@code orderBy} is specified within a single partition.
 * <p>
 * Under the strict-LCD contract, {@link QueryRequest#partitionKey()} is
 * required on every query, so cross-partition scan variants are not exercised
 * by this suite.
 * <p>
 * Subclass this and implement {@link #createClient()}, {@link #getAddress()},
 * and {@link #sortKeyFieldName()}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class SortKeyOrderingConformanceTest {

    protected abstract MulticloudDbClient createClient();

    protected abstract ResourceAddress getAddress();

    /**
     * Returns the field name that holds the sort key value in query results.
     * <p>
     * Cosmos stores the sort key as {@code "id"}; DynamoDB stores it as
     * {@code "sortKey"}; Spanner stores it as {@code "sortKey"}.
     */
    protected abstract String sortKeyFieldName();

    private MulticloudDbClient client;

    @BeforeEach
    void setUp() {
        client = createClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
    }

    private static String str(Map<String, Object> item, String field) {
        Object v = item.get(field);
        return v != null ? v.toString() : "";
    }

    @Test
    @Order(1)
    @DisplayName("partition-scoped query returns items sorted by sort key ASC")
    void partitionQueryReturnsSortedBySortKey() {
        // Insert items deliberately OUT of lexicographic sort-key order
        client.upsert(getAddress(), MulticloudDbKey.of("sort-part", "sort-charlie"),
                Map.of("label", "charlie", "group", "sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("sort-part", "sort-alpha"),
                Map.of("label", "alpha", "group", "sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("sort-part", "sort-bravo"),
                Map.of("label", "bravo", "group", "sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("sort-part", "sort-echo"),
                Map.of("label", "echo", "group", "sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("sort-part", "sort-delta"),
                Map.of("label", "delta", "group", "sort-test"));

        try {
            QueryPage page = client.query(getAddress(),
                    QueryRequest.builder().partitionKey("sort-part").maxPageSize(100).build());

            assertNotNull(page, "Query page must not be null");
            List<Map<String, Object>> items = page.items();
            assertEquals(5, items.size(), "Partition 'sort-part' should contain exactly 5 items");

            List<String> expected = List.of(
                    "sort-alpha", "sort-bravo", "sort-charlie", "sort-delta", "sort-echo");
            List<String> actual = items.stream()
                    .map(item -> str(item, sortKeyFieldName()))
                    .toList();
            assertEquals(expected, actual,
                    "Items must be returned in ascending sort-key order; got: " + actual);
        } finally {
            for (String sk : List.of("sort-alpha", "sort-bravo", "sort-charlie", "sort-delta", "sort-echo")) {
                try { client.delete(getAddress(), MulticloudDbKey.of("sort-part", sk)); } catch (Exception ignored) {}
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("partition-scoped query with explicit orderBy(sortKey, DESC) reverses order")
    void partitionQueryWithExplicitDescSort() {
        client.upsert(getAddress(), MulticloudDbKey.of("sort-desc", "d-1"),
                Map.of("group", "sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("sort-desc", "d-2"),
                Map.of("group", "sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("sort-desc", "d-3"),
                Map.of("group", "sort-test"));

        try {
            QueryPage page = client.query(getAddress(),
                    QueryRequest.builder()
                            .partitionKey("sort-desc")
                            .orderBy("sortKey", SortDirection.DESC)
                            .maxPageSize(100)
                            .build());

            assertNotNull(page);
            List<String> actual = page.items().stream()
                    .map(item -> str(item, sortKeyFieldName()))
                    .toList();
            assertEquals(List.of("d-3", "d-2", "d-1"), actual,
                    "orderBy(sortKey, DESC) must return items in descending sort-key order; got: "
                            + actual);
        } finally {
            for (String sk : List.of("d-1", "d-2", "d-3")) {
                try { client.delete(getAddress(), MulticloudDbKey.of("sort-desc", sk)); } catch (Exception ignored) {}
            }
        }
    }
}
