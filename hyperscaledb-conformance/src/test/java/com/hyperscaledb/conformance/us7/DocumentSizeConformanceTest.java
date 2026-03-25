package com.hyperscaledb.conformance.us7;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperscaledb.api.*;
import com.hyperscaledb.conformance.ConformanceHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for User Story 7 — Uniform Document Size Enforcement (FR-061–FR-063).
 * <p>
 * Verifies that:
 * <ul>
 *   <li>FR-061: Documents within the 400 KB limit are accepted by all providers.</li>
 *   <li>FR-062: Documents exceeding 400 KB are rejected at the SDK layer with
 *       {@link HyperscaleDbErrorCategory#INVALID_REQUEST} before reaching the provider.</li>
 *   <li>FR-063: The rejection is consistent across {@code create()} and {@code upsert()}.</li>
 * </ul>
 * <p>
 * These tests run against a mock/in-process provider via the conformance harness
 * so that oversized document rejection is verifiable without a live provider.
 */
public class DocumentSizeConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 400 KB limit — same as {@code DocumentSizeValidator.MAX_BYTES}. */
    private static final int MAX_BYTES = 400 * 1024;

    // -------------------------------------------- FR-061: within limit

    @Test
    @DisplayName("FR-061: document within 400 KB limit is accepted on upsert")
    void documentWithinLimitIsAccepted() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);

            // Build a document just under 400 KB
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("payload", "A".repeat(MAX_BYTES - 200));
            Key key = Key.of("size-test-within", "size-test-within");

            // Should not throw
            assertDoesNotThrow(() -> client.upsert(address, key, doc),
                    "Document within 400 KB limit must be accepted");
        }
    }

    // -------------------------------------------- FR-062: exceeds limit on upsert

    @Test
    @DisplayName("FR-062: document exceeding 400 KB is rejected on upsert")
    void documentExceedingLimitIsRejectedOnUpsert() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);

            // Build a document well over 400 KB
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("payload", "B".repeat(MAX_BYTES + 1000));
            Key key = Key.of("size-test-over", "size-test-over");

            HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                    () -> client.upsert(address, key, doc),
                    "Document exceeding 400 KB must throw HyperscaleDbException");
            assertNotNull(ex.error(), "Exception must carry structured error");
            assertEquals(HyperscaleDbErrorCategory.INVALID_REQUEST, ex.error().category(),
                    "Category must be INVALID_REQUEST for oversized documents");
        }
    }

    // -------------------------------------------- FR-063: consistent on create

    @Test
    @DisplayName("FR-063: document exceeding 400 KB is rejected on create")
    void documentExceedingLimitIsRejectedOnCreate() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);

            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("payload", "C".repeat(MAX_BYTES + 1000));
            Key key = Key.of("size-test-create-over", "size-test-create-over");

            HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                    () -> client.create(address, key, doc),
                    "Oversized document must throw on create too");
            assertEquals(HyperscaleDbErrorCategory.INVALID_REQUEST, ex.error().category());
        }
    }
}
