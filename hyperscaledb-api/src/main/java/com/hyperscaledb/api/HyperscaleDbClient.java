// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Map;

/**
 * Portable client interface for CRUD + query operations across cloud database
 * providers.
 * <p>
 * All operations use a provider-neutral <strong>synchronous</strong> contract.
 * Provider selection is configuration-only — no code changes are required to
 * switch providers. Async APIs are out of scope for v1.
 * <p>
 * There are no code-level escape hatches. Diagnostics and provider-specific
 * opt-ins are controlled via {@link HyperscaleDbClientConfig} only.
 */
public interface HyperscaleDbClient extends AutoCloseable {

    /**
     * Insert a new document. Fails if a document with the same key already exists.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document document payload
     * @param options  operation options (timeout, etc.)
     * @throws HyperscaleDbException with category CONFLICT if the key already exists
     */
    void create(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Insert a new document using default options. Fails if key already exists.
     */
    default void create(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document) {
        create(address, key, document, OperationOptions.defaults());
    }

    /**
     * Read a document by key.
     *
     * @param address target database + collection
     * @param key     document key
     * @param options operation options
     * @return the document as a map, or null if not found
     */
    Map<String, Object> read(ResourceAddress address, HyperscaleDbKey key, OperationOptions options);

    /**
     * Read a document by key, using default options.
     */
    default Map<String, Object> read(ResourceAddress address, HyperscaleDbKey key) {
        return read(address, key, OperationOptions.defaults());
    }

    /**
     * Update an existing document. Fails if a document with the given key does not
     * exist.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document document payload
     * @param options  operation options
     * @throws HyperscaleDbException with category NOT_FOUND if the key does not exist
     */
    void update(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Update an existing document using default options. Fails if key does not
     * exist.
     */
    default void update(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document) {
        update(address, key, document, OperationOptions.defaults());
    }

    /**
     * Upsert (create or replace) a document identified by key.
     *
     * @param address  target database + collection
     * @param key      document key
     * @param document document payload
     * @param options  operation options (timeout, etc.)
     */
    void upsert(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options);

    /**
     * Upsert (create or replace) a document identified by key, using default
     * options.
     */
    default void upsert(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document) {
        upsert(address, key, document, OperationOptions.defaults());
    }

    /**
     * Delete a document by key.
     *
     * @param address target database + collection
     * @param key     document key
     * @param options operation options
     */
    void delete(ResourceAddress address, HyperscaleDbKey key, OperationOptions options);

    /**
     * Delete a document by key, using default options.
     */
    default void delete(ResourceAddress address, HyperscaleDbKey key) {
        delete(address, key, OperationOptions.defaults());
    }

    /**
     * Execute a query and return a single page of results.
     *
     * @param address target database + collection
     * @param query   query request (expression, parameters, page size, continuation
     *                token)
     * @param options operation options
     * @return a page of results with optional continuation token
     */
    QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options);

    /**
     * Execute a query using default options.
     */
    default QueryPage query(ResourceAddress address, QueryRequest query) {
        return query(address, query, OperationOptions.defaults());
    }

    /**
     * Discover capabilities supported by the current provider.
     */
    CapabilitySet capabilities();

    /**
     * Ensure a logical database exists, creating it if it does not already exist.
     * <p>
     * This is an idempotent operation — if the database already exists the call
     * succeeds silently. Use this at application startup to guarantee the required
     * databases are in place before performing data operations.
     * <p>
     * For providers without a native database concept (e.g., DynamoDB), this is a
     * no-op.
     * <p>
     * <b>Permission note:</b> this operation uses each provider's standard
     * data-plane SDK and is subject to the caller's runtime permissions. When
     * the caller lacks sufficient permissions (e.g., Cosmos DB data-plane RBAC
     * without a control-plane role), the SDK throws a
     * {@link HyperscaleDbException} with category {@code PERMISSION_DENIED}.
     * Provision the database out-of-band (portal, CLI, IaC) if needed.
     *
     * @param database the logical database name to create if absent
     * @throws HyperscaleDbException with category {@code PERMISSION_DENIED} when
     *                               the caller lacks permissions, or
     *                               {@code CONFLICT} / {@code INTERNAL_ERROR} for
     *                               other failures
     */
    void ensureDatabase(String database);

    /**
     * Ensure a container (table) exists within the given database, creating it if
     * it does not already exist.
     * <p>
     * This is an idempotent operation — if the container already exists the call
     * succeeds silently. Use this at application startup to guarantee the required
     * containers are in place before performing data operations.
     * <p>
     * Containers are always created with the SDK's standard schema conventions
     * (partition key path {@code /partitionKey}, sort key column {@code sortKey}).
     *
     * @param address the database + collection identifying the container to create
     *                if absent
     * @throws HyperscaleDbException if the creation fails for a reason other than
     *                               the resource already existing
     */
    void ensureContainer(ResourceAddress address);

    /**
     * Provision a full schema of databases and containers in a single call.
     * <p>
     * Equivalent to calling {@link #ensureDatabase} for every database key and
     * {@link #ensureContainer} for every collection, but executes both phases in
     * parallel using a bounded thread pool (max 10 threads) for efficiency.
     * <p>
     * All operations are idempotent — existing resources are left unchanged.
     * Use this at application startup to guarantee the entire required schema is
     * in place before performing data operations.
     *
     * @param schema map of database name → list of collection/table names to ensure
     * @throws HyperscaleDbException if any database or container creation fails
     */
    void provisionSchema(java.util.Map<String, java.util.List<String>> schema);

    /**
     * Get the provider ID for this client.
     */
    ProviderId providerId();
}
