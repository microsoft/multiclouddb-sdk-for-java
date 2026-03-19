// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us1;

import com.hyperscaledb.api.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for query paging with continuation tokens.
 * <p>
 * Verifies that providers correctly implement page-size limits and
 * continuation token–based pagination to retrieve all results.
 * <p>
 * Subclass and implement {@link #createClient()} and {@link #getAddress()}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class QueryPagingConformanceTest {

    private static final int TOTAL_ITEMS = 7;
    private static final int PAGE_SIZE = 3;
    private static final String KEY_PREFIX = "paging-test-";

    protected abstract HyperscaleDbClient createClient();

    protected abstract ResourceAddress getAddress();

    private HyperscaleDbClient client;

    @BeforeEach
    void setUp() {
        client = createClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("setup: insert test items for paging")
    void setupItems() {
        for (int i = 1; i <= TOTAL_ITEMS; i++) {
            Key key = Key.of(KEY_PREFIX + i, KEY_PREFIX + i);
            client.upsert(getAddress(), key,
                    Map.of("title", "Paging Item " + i, "batch", "paging-conformance"));
        }
    }

    @Test
    @Order(2)
    @DisplayName("first page respects pageSize limit")
    void firstPageRespectsPageSize() {
        QueryRequest query = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .pageSize(PAGE_SIZE)
                .build();

        QueryPage page = client.query(getAddress(), query);
        assertNotNull(page);
        assertTrue(page.items().size() <= PAGE_SIZE,
                "First page should contain at most " + PAGE_SIZE + " items, got " + page.items().size());
    }

    @Test
    @Order(3)
    @DisplayName("continuation token allows fetching all pages")
    void continuationTokenPagination() {
        List<String> allIds = new ArrayList<>();
        String continuationToken = null;
        int pageCount = 0;
        int maxPages = 20; // safety limit

        do {
            QueryRequest.Builder qb = QueryRequest.builder()
                    .expression("SELECT * FROM c")
                    .pageSize(PAGE_SIZE);
            if (continuationToken != null) {
                qb.continuationToken(continuationToken);
            }

            QueryPage page = client.query(getAddress(), qb.build());
            assertNotNull(page, "Page should not be null");

            for (var item : page.items()) {
                // sortKey field for DynamoDB/Spanner, id field for Cosmos
                Object sk = item.get("sortKey");
                Object id = item.get("id");
                if (sk != null) allIds.add(sk.toString());
                else if (id != null) allIds.add(id.toString());
            }

            continuationToken = page.continuationToken();
            pageCount++;
        } while (continuationToken != null && pageCount < maxPages);

        // We should have retrieved items across multiple pages
        assertTrue(pageCount > 1 || allIds.size() <= PAGE_SIZE,
                "Should paginate across multiple pages for " + TOTAL_ITEMS + " items with pageSize " + PAGE_SIZE);

        // Verify we got at least the items we inserted (there may be other items from
        // other tests)
        Set<String> idSet = new HashSet<>(allIds);
        for (int i = 1; i <= TOTAL_ITEMS; i++) {
            assertTrue(idSet.contains(KEY_PREFIX + i),
                    "Should find item " + KEY_PREFIX + i + " across all pages");
        }
    }

    @Test
    @Order(4)
    @DisplayName("empty continuation token means no more pages")
    void nullTokenMeansComplete() {
        // Query with a very large page size to get all items at once
        QueryRequest query = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .pageSize(1000)
                .build();

        QueryPage page = client.query(getAddress(), query);
        assertNotNull(page);
        assertNull(page.continuationToken(),
                "Continuation token should be null when all results fit in one page");
    }

    @Test
    @Order(5)
    @DisplayName("page size of 1 returns one item per page")
    void pageSizeOne() {
        QueryRequest query = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .pageSize(1)
                .build();

        QueryPage page = client.query(getAddress(), query);
        assertNotNull(page);
        assertTrue(page.items().size() <= 1,
                "Page with pageSize=1 should return at most 1 item");
    }

    @Test
    @Order(10)
    @DisplayName("cleanup: remove paging test items")
    void cleanup() {
        for (int i = 1; i <= TOTAL_ITEMS; i++) {
            try {
                client.delete(getAddress(), Key.of(KEY_PREFIX + i, KEY_PREFIX + i));
            } catch (Exception ignored) {
            }
        }
    }
}
