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

    /**
     * Tracks which providers have already had their default DB/container
     * provisioned in this JVM. ensure* is idempotent on every provider, but
     * Spanner DDL still costs an information_schema query per call, and Cosmos
     * issues a control-plane RPC. Memoising once per provider keeps the
     * @BeforeEach cost flat for the rest of the suite. We use
     * {@code computeIfAbsent} so the provisioning runs atomically — under
     * parallel test execution, only one thread per provider performs the
     * ensure* calls; others wait on the map's segment lock and observe the
     * cached marker. A failure inside the lambda propagates and leaves the
     * map empty for that provider, so the next attempt retries (rather than
     * masking the failure with a stale "provisioned" marker).
     */
    private static final java.util.concurrent.ConcurrentMap<ProviderId, Boolean> PROVISIONED =
            new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        MulticloudDbClientConfig config = ConformanceConfig.forProvider(providerId());
        client = MulticloudDbClientFactory.create(config);
        address = ConformanceHarness.defaultAddress(providerId());
        PROVISIONED.computeIfAbsent(providerId(), pid -> {
            client.ensureDatabase(address.database());
            client.ensureContainer(address);
            return Boolean.TRUE;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
    }


    /**
     * Asserts the structured diagnostics contract claimed by this suite's class
     * Javadoc: every {@link MulticloudDbException} surfaced through the public client
     * must carry an {@link OperationDiagnostics} populated with provider, op name,
     * and a non-negative duration.
     */
    private void assertDiagnosticsPopulated(MulticloudDbException ex, String expectedOp) {
        OperationDiagnostics diag = ex.diagnostics();
        assertNotNull(diag, "Exception must carry OperationDiagnostics");
        assertEquals(providerId(), diag.provider(),
                "Diagnostics.provider() must reflect the originating provider");
        assertEquals(expectedOp, diag.operation(),
                "Diagnostics.operation() must record the failing operation");
        assertNotNull(diag.duration(), "Diagnostics.duration() must be populated");
        assertFalse(diag.duration().isNegative(),
                "Diagnostics.duration() must be non-negative; was " + diag.duration());
    }

    @Test
    @Order(1)
    @DisplayName("update-missing carries NOT_FOUND, OperationDiagnostics, and isRetryable=false")
    void notFoundIsFullyNormalized() {
        // Update is the LCD probe for NOT_FOUND across providers: Cosmos replaceItem
        // returns 404, Dynamo update with attribute_exists fails the condition, and
        // Spanner Mutation.newUpdateBuilder rejects with NOT_FOUND. Delete is silent
        // on missing across all three providers (idempotent), so it cannot be used
        // to assert NOT_FOUND normalization.
        MulticloudDbKey key = MulticloudDbKey.of(
                "norm-missing-" + System.nanoTime(),
                "norm-missing-" + System.nanoTime());

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.update(address, key, Map.of("title", "x")),
                "update of missing key must throw on every provider");

        // Structured error
        assertNotNull(ex.error(), "Exception must carry a MulticloudDbError");
        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, ex.error().category(),
                "update-missing must normalize to NOT_FOUND");
        assertEquals(providerId(), ex.error().provider(),
                "Error.provider() must reflect the originating provider");
        assertNotNull(ex.error().operation(), "Error.operation() must be populated");
        assertNotNull(ex.error().message(), "Error.message() must be populated");
        // NOT_FOUND on a real key the caller asked for is not transient — retry is futile.
        assertFalse(ex.error().retryable(),
                "NOT_FOUND must not be marked retryable");

        // Diagnostics contract — claimed in the class Javadoc above.
        assertDiagnosticsPopulated(ex, "update");
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

            // Diagnostics contract — claimed in the class Javadoc above.
            assertDiagnosticsPopulated(ex, "create");

            // Stability check: a second equivalent reproduction must surface the
            // same category and the same retryable hint. The class Javadoc claims
            // stability as a general property, but the dedicated stability test
            // (retryableIsStableForRepeatedErrors) only exercises NOT_FOUND — so
            // we pin CONFLICT here to make sure a provider cannot legitimately
            // flip its retryable hint between equivalent CONFLICT reproductions.
            MulticloudDbException ex2 = assertThrows(MulticloudDbException.class,
                    () -> client.create(address, key, Map.of("title", "third")),
                    "second equivalent create-duplicate must also throw");
            assertEquals(ex.error().category(), ex2.error().category(),
                    "Equivalent CONFLICT errors must always produce the same category");
            assertEquals(ex.error().retryable(), ex2.error().retryable(),
                    "Equivalent CONFLICT errors must produce stable isRetryable() across calls");
            assertDiagnosticsPopulated(ex2, "create");
        } finally {
            // delete() is idempotent across providers — silent on missing — so a
            // plain call is safe even if the create above failed before persisting.
            client.delete(address, key);
        }
    }

    @Test
    @Order(3)
    @DisplayName("isRetryable() is consistent across two reproductions of the same error")
    void retryableIsStableForRepeatedErrors() {
        // Reproduce the same NOT_FOUND twice via update on a missing key. Delete
        // cannot be used here because it is idempotent — silent on missing across
        // all providers — so it never produces an exception to compare.
        MulticloudDbKey key = MulticloudDbKey.of(
                "norm-stable-" + System.nanoTime(),
                "norm-stable-" + System.nanoTime());

        MulticloudDbException first = assertThrows(MulticloudDbException.class,
                () -> client.update(address, key, Map.of("title", "x")));
        MulticloudDbException second = assertThrows(MulticloudDbException.class,
                () -> client.update(address, key, Map.of("title", "x")));

        assertEquals(first.error().category(), second.error().category(),
                "Equivalent errors must always produce the same category");
        assertEquals(first.error().retryable(), second.error().retryable(),
                "Equivalent errors must produce stable isRetryable() across calls");

        // Diagnostics must be populated on every reproduction.
        assertDiagnosticsPopulated(first, "update");
        assertDiagnosticsPopulated(second, "update");
    }
}
