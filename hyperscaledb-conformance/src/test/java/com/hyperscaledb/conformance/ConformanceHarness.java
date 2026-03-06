package com.hyperscaledb.conformance;

import com.hyperscaledb.api.*;

import java.util.Objects;
import java.util.UUID;

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
     * Create a {@link HyperscaleDbClient} for the given provider using
     * {@link ConformanceConfig}.
     *
     * @param provider the target provider
     * @return a ready-to-use client
     */
    public static HyperscaleDbClient createClient(ProviderId provider) {
        Objects.requireNonNull(provider, "provider");
        HyperscaleDbClientConfig config = ConformanceConfig.forProvider(provider);
        return HyperscaleDbClientFactory.create(config);
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

    /**
     * Generate a unique test key with a random suffix to avoid collisions between
     * test runs.
     * Uses the same value for both id and partition key.
     *
     * @param prefix a human-readable prefix for the key
     * @return a unique {@link Key}
     */
    public static Key uniqueKey(String prefix) {
        String id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return Key.of(id, id);
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
    public static void safeDelete(HyperscaleDbClient client, ResourceAddress address, Key key) {
        try {
            client.delete(address, key);
        } catch (Exception ignored) {
            // Cleanup is best-effort
        }
    }
}
