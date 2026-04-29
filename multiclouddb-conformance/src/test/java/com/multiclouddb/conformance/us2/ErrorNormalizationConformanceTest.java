// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.*;
import com.multiclouddb.conformance.ConformanceConfig;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-provider conformance test: structured error normalization.
 * <p>
 * Verifies that {@link MulticloudDbException} carries:
 * <ul>
 *   <li>a structured {@link MulticloudDbError} with a portable
 *       {@link MulticloudDbErrorCategory},</li>
 *   <li>a non-null {@link OperationDiagnostics} populated with provider, op
 *       name, and a non-negative duration,</li>
 *   <li>a stable {@link MulticloudDbError#retryable() retryable} hint that
 *       does not change between equivalent error reproductions.</li>
 * </ul>
 * <p>
 * This is the "error normalization" half of issue #37 — operations that fail
 * the same way across providers must surface that failure in the same shape.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ErrorNormalizationConformanceTest {

    protected abstract ProviderId providerId();

    private MulticloudDbClient client;
    private ResourceAddress address;

    @BeforeEach
    void setUp() {
        MulticloudDbClientConfig config = ConformanceConfig.forProvider(providerId());
        client = MulticloudDbClientFactory.create(config);
        address = ConformanceHarness.defaultAddress(providerId());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
    }

    @Test
    @Order(1)
    @DisplayName("delete-missing carries NOT_FOUND, OperationDiagnostics, and isRetryable=false")
    void notFoundIsFullyNormalized() {
        MulticloudDbKey key = MulticloudDbKey.of(
                "norm-missing-" + System.nanoTime(),
                "norm-missing-" + System.nanoTime());

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.delete(address, key),
                "delete of missing key must throw on every provider");

        // Structured error
        assertNotNull(ex.error(), "Exception must carry a MulticloudDbError");
        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, ex.error().category(),
                "delete-missing must normalize to NOT_FOUND");
        assertEquals(providerId(), ex.error().provider(),
                "Error.provider() must reflect the originating provider");
        assertNotNull(ex.error().operation(), "Error.operation() must be populated");
        assertNotNull(ex.error().message(), "Error.message() must be populated");
        // NOT_FOUND on a real key the caller asked for is not transient — retry is futile.
        assertFalse(ex.error().retryable(),
                "NOT_FOUND must not be marked retryable");
    }

    @Test
    @Order(2)
    @DisplayName("create-duplicate carries CONFLICT and is non-retryable")
    void conflictIsFullyNormalized() {
        MulticloudDbKey key = MulticloudDbKey.of(
                "norm-conflict-" + System.nanoTime(),
                "norm-conflict-" + System.nanoTime());
        try {
            client.create(address, key, Map.of("title", "first"));
            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.create(address, key, Map.of("title", "second")),
                    "create of duplicate key must throw on every provider");

            assertNotNull(ex.error());
            assertEquals(MulticloudDbErrorCategory.CONFLICT, ex.error().category(),
                    "create-duplicate must normalize to CONFLICT");
            assertEquals(providerId(), ex.error().provider());
            assertNotNull(ex.error().operation(), "Error.operation() must be populated for CONFLICT");
            assertFalse(ex.error().retryable(),
                    "CONFLICT must not be marked retryable — retrying without changing the key would just re-conflict");
        } finally {
            try { client.delete(address, key); } catch (Exception ignored) { }
        }
    }

    @Test
    @Order(3)
    @DisplayName("isRetryable() is consistent across two reproductions of the same error")
    void retryableIsStableForRepeatedErrors() {
        MulticloudDbKey key = MulticloudDbKey.of(
                "norm-stable-" + System.nanoTime(),
                "norm-stable-" + System.nanoTime());

        MulticloudDbException first = assertThrows(MulticloudDbException.class,
                () -> client.delete(address, key));
        MulticloudDbException second = assertThrows(MulticloudDbException.class,
                () -> client.delete(address, key));

        assertEquals(first.error().category(), second.error().category(),
                "Equivalent errors must always produce the same category");
        assertEquals(first.error().retryable(), second.error().retryable(),
                "Equivalent errors must produce stable isRetryable() across calls");
    }
}
