// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test harness utilities.
 * <p>
 * Provides helper methods to build clients from config, create unique test
 * resource names, and clean up test data.
 */
public final class ConformanceHarness {

    private ConformanceHarness() {
    }

    /**
     * Create a {@link MulticloudDbClient} for the given provider using
     * {@link ConformanceConfig}.
     *
     * @param provider the target provider
     * @return a ready-to-use client
     */
    public static MulticloudDbClient createClient(ProviderId provider) {
        Objects.requireNonNull(provider, "provider");
        MulticloudDbClientConfig config = ConformanceConfig.forProvider(provider);
        return MulticloudDbClientFactory.create(config);
    }

    /**
     * Build a {@link ResourceAddress} using the default database/table for the
     * given provider.
     *
     * @param provider the target provider
     * @return a resource address pointing to the default conformance test table
     */
    public static ResourceAddress defaultAddress(ProviderId provider) {
        return new ResourceAddress(
                ConformanceConfig.databaseFor(provider),
                ConformanceConfig.tableFor(provider));
    }

    /** Assert only the common completed-operation contract, not optional native availability. */
    public static void assertDiagnostics(OperationDiagnostics diagnostics, ProviderId provider,
                                         String operation, OperationOutcome outcome) {
        assertNotNull(diagnostics, "Completed operations must carry diagnostics");
        assertEquals(provider, diagnostics.provider());
        assertEquals(operation, diagnostics.operation());
        assertEquals(outcome, diagnostics.outcome());
        assertNotNull(diagnostics.startTime());
        assertNotNull(diagnostics.endTime());
        assertNotNull(diagnostics.duration());
        assertFalse(diagnostics.duration().isNegative());
        // Wall clocks may move backward; elapsed duration uses a monotonic clock.
        if (diagnostics.statusCode() != null) {
            assertNotNull(diagnostics.statusCodeDomain());
        }
        ResourceConsumption consumption = diagnostics.consumption();
        if (consumption != null) {
            assertTrue(Double.isFinite(consumption.amount()));
            assertTrue(consumption.amount() >= 0);
            assertNotNull(consumption.unit());
            assertNotNull(consumption.coverage());
        }
    }

    /** Validate a non-page response without discarding its payload. */
    public static <T> T assertResponse(OperationResponse<T> response, ProviderId provider,
                                       String operation, OperationOutcome outcome) {
        assertNotNull(response, "A missing read still returns a response");
        assertDiagnostics(response.diagnostics(), provider, operation, outcome);
        return response.result();
    }

    /**
     * Generate a unique test key with a random suffix to avoid collisions between
     * test runs.
     * Uses the same value for both id and partition key.
     *
     * @param prefix a human-readable prefix for the key
     * @return a unique {@link MulticloudDbKey}
     */
    public static MulticloudDbKey uniqueKey(String prefix) {
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return MulticloudDbKey.of(id, id);
    }

    /**
     * Generate a unique string suitable for resource names or identifiers.
     *
     * @param prefix a human-readable prefix
     * @return a unique string
     */
    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Silently delete a document, ignoring any errors (useful for test cleanup).
     *
     * @param client  the client to use
     * @param address the resource address
     * @param key     the key to delete
     */
    public static void safeDelete(MulticloudDbClient client, ResourceAddress address, MulticloudDbKey key) {
        try {
            client.delete(address, key);
        } catch (Exception ignored) {
            // Cleanup is best-effort
        }
    }
}
