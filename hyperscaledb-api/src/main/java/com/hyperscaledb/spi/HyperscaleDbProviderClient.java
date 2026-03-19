// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.spi;

import com.hyperscaledb.api.*;
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
     * Ensure a logical database exists.
     * <p>
     * Provider semantics:
     * <ul>
     * <li><b>Cosmos DB</b>: creates the Cosmos database if it does not exist</li>
     * <li><b>DynamoDB</b>: no-op (DynamoDB has no native database concept; the
     * database dimension is encoded in table names)</li>
     * <li><b>Spanner</b>: no-op (the database is set at client construction
     * time)</li>
     * </ul>
     *
     * @param database the logical database name
     */
    default void ensureDatabase(String database) {
        // Default is no-op — providers that have a native database concept override
    }

    /**
     * Ensure a container (or table) exists within the given database.
     * <p>
     * Provider semantics:
     * <ul>
     * <li><b>Cosmos DB</b>: creates a container with partition key path
     * {@code /partitionKey}</li>
     * <li><b>DynamoDB</b>: creates a table named
     * {@code database__collection} with hash key {@code partitionKey} and sort key
     * {@code sortKey}</li>
     * <li><b>Spanner</b>: creates a table with columns
     * {@code partitionKey STRING(MAX)}
     * and {@code sortKey STRING(MAX)} as the primary key, plus a {@code data}
     * column for the JSON document</li>
     * </ul>
     *
     * @param address the database + collection identifying the container
     */
    default void ensureContainer(ResourceAddress address) {
        // Default is no-op — providers override with their creation logic
    }

    /**
     * Provision a full schema of databases and containers/tables in parallel.
     * <p>
     * Default implementation creates all databases concurrently, waits for
     * completion, then creates all containers concurrently. Providers may
     * override for provider-specific optimisations.
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
     * Return the native provider client (for escape hatch), or null.
     */
    <T> T nativeClient(Class<T> clientType);

    /**
     * Return the provider id.
     */
    ProviderId providerId();
}
