// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.spi;

import com.hyperscaledb.api.CapabilitySet;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.HyperscaleDbKey;
import com.hyperscaledb.api.OperationOptions;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.hyperscaledb.api.query.TranslatedQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SPI contract for a provider client that implements CRUD + query operations.
 * Provider adapters create instances of this interface.
 */
public interface HyperscaleDbProviderClient extends AutoCloseable {

    /**
     * Insert a new document. Fails if a document with the same key already exists.
     *
     * @throws HyperscaleDbException with category CONFLICT if the key already exists
     */
    void create(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Read a document by key.
     *
     * @return the document as a map, or null if not found
     */
    Map<String, Object> read(ResourceAddress address, HyperscaleDbKey key, OperationOptions options);

    /**
     * Update an existing document. Fails if the key does not exist.
     *
     * @throws HyperscaleDbException with category NOT_FOUND if the key does not exist
     */
    void update(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Upsert (create or replace) a document.
     */
    void upsert(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Delete a document by key.
     */
    void delete(ResourceAddress address, HyperscaleDbKey key, OperationOptions options);

    /**
     * Execute a query and return a single page of results.
     */
    QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options);

    /**
     * Execute a query using a pre-translated portable expression.
     * Providers that support portable expressions should override this method.
     * Default implementation falls back to
     * {@link #query(ResourceAddress, QueryRequest, OperationOptions)}.
     *
     * @param address    the resource address
     * @param translated the translated query (provider-native syntax)
     * @param query      the original query request (for pageSize,
     *                   continuationToken)
     * @param options    operation options
     * @return a page of results
     */
    default QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        return query(address, query, options);
    }

    /**
     * Ensure a logical database exists, creating it if absent.
     * <p>
     * Default is a no-op — providers that have a native database concept override
     * this method.
     *
     * @param database the logical database name
     */
    default void ensureDatabase(String database) {
        // Default is no-op — providers that have a native database concept override
    }

    /**
     * Ensure a container (or table) exists within the given database, creating it
     * if absent.
     * <p>
     * Default is a no-op — providers override with their creation logic.
     *
     * @param address the database + collection identifying the container
     */
    default void ensureContainer(ResourceAddress address) {
        // Default is no-op — providers override with their creation logic
    }

    /**
     * Provision a full schema of databases and containers in parallel.
     * <p>
     * Default implementation creates all databases concurrently (Phase 1),
     * waits for completion, then creates all containers concurrently (Phase 2),
     * using a bounded thread pool (max 10 threads). Providers may override for
     * provider-specific optimisations.
     *
     * @param schema map of database name → list of collection/table names
     */
    default void provisionSchema(Map<String, List<String>> schema) {
        List<String> databases = new ArrayList<>(schema.keySet());
        List<ResourceAddress> containers = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : schema.entrySet()) {
            for (String collection : entry.getValue()) {
                containers.add(new ResourceAddress(entry.getKey(), collection));
            }
        }

        int parallelism = Math.min(databases.size() + containers.size(), 10);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            // Phase 1: databases in parallel
            CompletableFuture<?>[] dbFutures = databases.stream()
                    .map(db -> CompletableFuture.runAsync(() -> ensureDatabase(db), pool))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(dbFutures).join();

            // Phase 2: containers in parallel
            CompletableFuture<?>[] containerFutures = containers.stream()
                    .map(addr -> CompletableFuture.runAsync(() -> ensureContainer(addr), pool))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(containerFutures).join();
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Return the set of capabilities supported by this provider.
     */
    CapabilitySet capabilities();

    /**
     * Return the provider id.
     */
    ProviderId providerId();
}
