// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Map;

/**
 * Portable client interface for CRUD + query operations across cloud database
 * providers.
 * <p>
 * Operations use the portable contract by default. Provider selection is
 * configuration-only.
 * Operations may emit {@link PortabilityWarning}s when opt-in extensions affect
 * behavior.
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
    void create(ResourceAddress address, Key key, Map<String, Object> document, OperationOptions options);

    /**
     * Insert a new document using default options. Fails if key already exists.
     */
    default void create(ResourceAddress address, Key key, Map<String, Object> document) {
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
    Map<String, Object> read(ResourceAddress address, Key key, OperationOptions options);

    /**
     * Read a document by key, using default options.
     */
    default Map<String, Object> read(ResourceAddress address, Key key) {
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
    void update(ResourceAddress address, Key key, Map<String, Object> document, OperationOptions options);

    /**
     * Update an existing document using default options. Fails if key does not
     * exist.
     */
    default void update(ResourceAddress address, Key key, Map<String, Object> document) {
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
    void upsert(ResourceAddress address, Key key, Map<String, Object> document, OperationOptions options);

    /**
     * Upsert (create or replace) a document identified by key, using default
     * options.
     */
    default void upsert(ResourceAddress address, Key key, Map<String, Object> document) {
        upsert(address, key, document, OperationOptions.defaults());
    }

    /**
     * Delete a document by key.
     *
     * @param address target database + collection
     * @param key     document key
     * @param options operation options
     */
    void delete(ResourceAddress address, Key key, OperationOptions options);

    /**
     * Delete a document by key, using default options.
     */
    default void delete(ResourceAddress address, Key key) {
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
     * Ensure a logical database exists.
     * <p>
     * Creates the database if it does not already exist.
     * For providers without a native database concept (e.g., DynamoDB), this is a
     * no-op.
     *
     * @param database the logical database name
     */
    void ensureDatabase(String database);

    /**
     * Ensure a container (or table) exists within the given database.
     * <p>
     * Creates the container/table if it does not already exist, using the
     * provider's default schema conventions (key columns, partition key path,
     * etc.).
     *
     * @param address the database + collection identifying the container
     */
    void ensureContainer(ResourceAddress address);

    /**
     * Provision a full schema of databases and containers/tables.
     * <p>
     * Creates all databases concurrently, then all containers concurrently.
     * This is the recommended way to provision multiple resources — the SDK
     * handles parallelism internally so application code does not need to
     * manage threading.
     *
     * @param schema map of database name → list of collection/table names
     */
    void provisionSchema(java.util.Map<String, java.util.List<String>> schema);

    /**
     * Access the underlying native provider client for escape-hatch scenarios.
     * Returns null if the requested type doesn't match the provider's native client
     * type.
     *
     * @param clientType the expected native client class
     * @return the native client instance, or null
     */
    <T> T nativeClient(Class<T> clientType);

    /**
     * Get the provider ID for this client.
     */
    ProviderId providerId();
}
