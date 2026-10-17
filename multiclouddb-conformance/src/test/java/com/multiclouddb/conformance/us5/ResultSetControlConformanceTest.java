// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us5;

import static com.multiclouddb.conformance.Documents.document;

import com.multiclouddb.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for User Story 5 — Result Set Control (FR-049–FR-053).
 * <p>
 * Verifies that:
 * <ul>
 *   <li>FR-049: {@link QueryRequest#limit()} is exposed and honoured by providers that support
 *       {@link Capability#RESULT_LIMIT}.</li>
 *   <li>FR-050: {@link QueryRequest#orderBy()} is exposed and honoured by providers that support
 *       {@link Capability#ORDER_BY}.</li>
 *   <li>FR-051: Providers that do not support ORDER BY throw
 *       {@link MulticloudDbException} with
 *       {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY}.</li>
 *   <li>FR-052: {@link SortDirection#ASC} and {@link SortDirection#DESC} produce different
 *       orderings (first-item sanity check).</li>
 *   <li>FR-053: limit=0 and limit&lt;0 are rejected at construction time with
 *       {@link IllegalArgumentException}.</li>
 * </ul>
 * <p>
 * Subclass this and implement {@link #createClient()} and {@link #getAddress()}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ResultSetControlConformanceTest {

/** Seeds written before tests run — sorted ascending by {@code score}. */
    private static final int SEED_COUNT = 5;

    protected abstract MulticloudDbClient createClient();

    protected abstract ResourceAddress getAddress();

    private MulticloudDbClient client;

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

    // ------------------------------------------------------------------ setup

    /** Seed documents for result-set-control tests. */
    protected void seedDocuments() {
        for (int i = 1; i <= SEED_COUNT; i++) {
            Map<String, Object> doc = Map.of("score", i * 10, "label", "item-" + i);
            MulticloudDbKey key = MulticloudDbKey.of("rsc-" + i, "rsc-" + i);
            client.upsert(getAddress(), key, document(doc));
        }
    }

    // -------------------------------------------- FR-049: RESULT_LIMIT

    @Test
    @Order(1)
    @DisplayName("FR-049: limit() is exposed on QueryRequest")
    void limitFieldIsExposedOnQueryRequest() {
        QueryRequest q = QueryRequest.builder().limit(3).build();
        assertEquals(3, q.limit(), "limit must round-trip through builder");
    }

    @Test
    @Order(2)
    @DisplayName("FR-049: provider honours RESULT_LIMIT capability")
    void providerHonoursResultLimit() {
        CapabilitySet caps = client.capabilities();
        assumeCapability(caps, Capability.RESULT_LIMIT,
                "Skip: provider does not declare RESULT_LIMIT");

        seedDocuments();

        QueryRequest q = QueryRequest.builder().limit(2).build();
        QueryPage page = client.query(getAddress(), q);

        assertTrue(page.items().size() <= 2,
                "Provider must return at most 2 items when limit=2, got " + page.items().size());
    }

    // -------------------------------------------- FR-050: ORDER_BY

    @Test
    @Order(3)
    @DisplayName("FR-050: orderBy() is exposed on QueryRequest")
    void orderByFieldIsExposedOnQueryRequest() {
        QueryRequest q = QueryRequest.builder()
                .orderBy("score", SortDirection.ASC)
                .build();
        assertNotNull(q.orderBy());
        assertEquals(1, q.orderBy().size());
        assertEquals("score", q.orderBy().get(0).field());
        assertEquals(SortDirection.ASC, q.orderBy().get(0).direction());
    }

    // -------------------------------------------- FR-051: unsupported ORDER_BY

    @Test
    @Order(4)
    @DisplayName("FR-051: provider without ORDER_BY throws UNSUPPORTED_CAPABILITY")
    void orderByOnUnsupportedProviderThrowsStructuredError() {
        CapabilitySet caps = client.capabilities();
        if (caps.isSupported(Capability.ORDER_BY)) {
            // This test only applies to providers that do NOT support ORDER BY
            return;
        }

        seedDocuments();

        QueryRequest q = QueryRequest.builder()
                .orderBy("score", SortDirection.ASC)
                .build();

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.query(getAddress(), q),
                "ORDER BY on a provider that does not support it must throw MulticloudDbException");
        assertNotNull(ex.error(), "Exception must carry a structured error");
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category(),
                "Error category must be UNSUPPORTED_CAPABILITY, got: " + ex.error().category());
    }

    // -------------------------------------------- FR-052: ASC vs DESC

    @Test
    @Order(5)
    @DisplayName("FR-052: ASC and DESC orderings differ in first item")
    void ascAndDescProduceDifferentFirstItem() {
        CapabilitySet caps = client.capabilities();
        assumeCapability(caps, Capability.ORDER_BY,
                "Skip: provider does not declare ORDER_BY");
        assumeCapability(caps, Capability.RESULT_LIMIT,
                "Skip: need RESULT_LIMIT to reliably check first item");

        seedDocuments();

        QueryRequest asc = QueryRequest.builder()
                .orderBy("score", SortDirection.ASC)
                .limit(1)
                .build();
        QueryRequest desc = QueryRequest.builder()
                .orderBy("score", SortDirection.DESC)
                .limit(1)
                .build();

        List<?> ascItems = client.query(getAddress(), asc).items();
        List<?> descItems = client.query(getAddress(), desc).items();

        assertFalse(ascItems.isEmpty(), "ASC query must return at least one item");
        assertFalse(descItems.isEmpty(), "DESC query must return at least one item");

        // Seed documents have deterministic score values (10, 20, 30, 40, 50).
        // ASC should return the item with score=10 first; DESC should return score=50 first.
        JsonNode ascFirst = (JsonNode) ascItems.get(0);
        JsonNode descFirst = (JsonNode) descItems.get(0);
        assertNotNull(ascFirst, "ASC first item must not be null");
        assertNotNull(descFirst, "DESC first item must not be null");
        assertEquals(10, ascFirst.get("score").asInt(),
                "ASC ordering must return lowest score (10) first");
        assertEquals(50, descFirst.get("score").asInt(),
                "DESC ordering must return highest score (50) first");
    }

    // -------------------------------------------- FR-053: invalid limit

    @Test
    @Order(6)
    @DisplayName("FR-053: limit=0 is rejected at QueryRequest construction")
    void zeroLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequest.builder().limit(0).build(),
                "limit=0 must throw IllegalArgumentException");
    }

    @Test
    @Order(7)
    @DisplayName("FR-053: negative limit is rejected at QueryRequest construction")
    void negativeLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequest.builder().limit(-1).build(),
                "negative limit must throw IllegalArgumentException");
    }

    // ---------------------------------------------------- helpers

    /**
     * Skips the test (via {@link Assumptions}) when the given capability is not
     * supported by the client under test.
     */
    private static void assumeCapability(CapabilitySet caps, String capability, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(caps.isSupported(capability), message);
    }
}
