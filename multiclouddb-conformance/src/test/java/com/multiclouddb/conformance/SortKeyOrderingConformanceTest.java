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
 * ensures both providers (and any future provider) return items sorted by
 * sort key ASC when no explicit {@code orderBy} is specified.
 * <p>
 * Three scenarios are tested:
 * <ol>
 *   <li>Partition-scoped query — items within a single partition</li>
 *   <li>Cross-partition scan — items across multiple partitions</li>
 *   <li>Filtered cross-partition scan — items matching a filter expression</li>
 * </ol>
 * <p>
 * Subclass this and implement {@link #createClient()} and {@link #getAddress()}.
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

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> item, String field) {
        Object v = item.get(field);
        return v != null ? v.toString() : "";
    }

    // ── Test 1: partition-scoped query returns items in sort-key ASC order ──

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

            // Verify ascending sort-key order
            List<String> expected = List.of(
                    "sort-alpha", "sort-bravo", "sort-charlie", "sort-delta", "sort-echo");
            List<String> actual = items.stream()
                    .map(item -> str(item, sortKeyFieldName()))
                    .toList();
            assertEquals(expected, actual,
                    "Items must be returned in ascending sort-key order; got: " + actual);
        } finally {
            // Cleanup
            for (String sk : List.of("sort-alpha", "sort-bravo", "sort-charlie", "sort-delta", "sort-echo")) {
                try { client.delete(getAddress(), MulticloudDbKey.of("sort-part", sk)); } catch (Exception ignored) {}
            }
        }
    }

    // ── Test 2: cross-partition scan returns items in sort-key ASC order ──

    @Test
    @Order(2)
    @DisplayName("cross-partition scan returns items sorted by sort key ASC")
    void crossPartitionScanReturnsSortedBySortKey() {
        // Insert items across DIFFERENT partitions, deliberately out of order
        client.upsert(getAddress(), MulticloudDbKey.of("xp-c", "xp-scan-charlie"),
                Map.of("label", "charlie", "batch", "xp-sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("xp-a", "xp-scan-alpha"),
                Map.of("label", "alpha", "batch", "xp-sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("xp-b", "xp-scan-bravo"),
                Map.of("label", "bravo", "batch", "xp-sort-test"));

        try {
            // Cross-partition query — no partitionKey set
            QueryPage page = client.query(getAddress(),
                    QueryRequest.builder()
                            .expression("batch = @batch")
                            .parameters(Map.of("batch", "xp-sort-test"))
                            .maxPageSize(100)
                            .build());

            assertNotNull(page, "Query page must not be null");
            List<Map<String, Object>> items = page.items();
            assertEquals(3, items.size(),
                    "Cross-partition query should return exactly 3 items with batch=xp-sort-test");

            List<String> expected = List.of("xp-scan-alpha", "xp-scan-bravo", "xp-scan-charlie");
            List<String> actual = items.stream()
                    .map(item -> str(item, sortKeyFieldName()))
                    .toList();
            assertEquals(expected, actual,
                    "Cross-partition scan must return items in ascending sort-key order; got: " + actual);
        } finally {
            for (String[] pair : new String[][] {
                    {"xp-a", "xp-scan-alpha"}, {"xp-b", "xp-scan-bravo"}, {"xp-c", "xp-scan-charlie"}}) {
                try { client.delete(getAddress(), MulticloudDbKey.of(pair[0], pair[1])); } catch (Exception ignored) {}
            }
        }
    }

    // ── Test 3: unfiltered cross-partition scan (no expression) ──

    @Test
    @Order(3)
    @DisplayName("unfiltered cross-partition scan returns items sorted by sort key ASC")
    void unfilteredScanReturnsSortedBySortKey() {
        // Use a unique partition key prefix so we can identify our items in the
        // result set (other tests may have left data in the container)
        client.upsert(getAddress(), MulticloudDbKey.of("uscan-c", "uscan-charlie"),
                Map.of("label", "charlie", "scangroup", "unfiltered-sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("uscan-a", "uscan-alpha"),
                Map.of("label", "alpha", "scangroup", "unfiltered-sort-test"));
        client.upsert(getAddress(), MulticloudDbKey.of("uscan-b", "uscan-bravo"),
                Map.of("label", "bravo", "scangroup", "unfiltered-sort-test"));

        try {
            // Completely unfiltered query — no expression, no partitionKey
            QueryPage page = client.query(getAddress(),
                    QueryRequest.builder().maxPageSize(200).build());

            assertNotNull(page, "Query page must not be null");
            List<Map<String, Object>> items = page.items();

            // Filter to only our test items (container may have other data)
            List<String> ourSortKeys = items.stream()
                    .map(item -> str(item, sortKeyFieldName()))
                    .filter(sk -> sk.startsWith("uscan-"))
                    .toList();

            assertTrue(ourSortKeys.size() >= 3,
                    "Should find at least 3 uscan- items, found: " + ourSortKeys.size());

            // Verify our items appear in ascending sort-key order relative to each other
            for (int i = 1; i < ourSortKeys.size(); i++) {
                assertTrue(ourSortKeys.get(i - 1).compareTo(ourSortKeys.get(i)) <= 0,
                        "Items must be in ascending sort-key order, but '"
                                + ourSortKeys.get(i - 1) + "' > '" + ourSortKeys.get(i) + "'");
            }
        } finally {
            for (String[] pair : new String[][] {
                    {"uscan-a", "uscan-alpha"}, {"uscan-b", "uscan-bravo"}, {"uscan-c", "uscan-charlie"}}) {
                try { client.delete(getAddress(), MulticloudDbKey.of(pair[0], pair[1])); } catch (Exception ignored) {}
            }
        }
    }
}
