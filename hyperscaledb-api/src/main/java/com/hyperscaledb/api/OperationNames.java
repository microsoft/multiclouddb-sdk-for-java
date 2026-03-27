// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

/**
 * Canonical operation name constants shared across all provider adapters.
 * <p>
 * These strings appear in {@link OperationDiagnostics}, {@link HyperscaleDbError},
 * and provider-level diagnostic log lines. Using a single source of truth ensures
 * consistent naming across Cosmos DB, DynamoDB, Spanner, and any future provider.
 * <p>
 * Provider-specific operation variants (e.g. DynamoDB scan sub-types) should be
 * defined in the provider's own constants class, following the pattern
 * {@code OP_QUERY_<variant>}.
 */
public final class OperationNames {

    private OperationNames() {}

    /** Insert a new document; fails if the key already exists. */
    public static final String CREATE = "create";

    /** Read a document by key. */
    public static final String READ = "read";

    /** Replace an existing document; fails if the key does not exist. */
    public static final String UPDATE = "update";

    /** Create or replace a document (upsert semantics). */
    public static final String UPSERT = "upsert";

    /** Delete a document by key. */
    public static final String DELETE = "delete";

    /** Execute a query and return a page of results. */
    public static final String QUERY = "query";

    /** Execute a query via portable expression translation. */
    public static final String QUERY_WITH_TRANSLATION = "queryWithTranslation";

    /** Ensure a logical database exists, creating it if absent. */
    public static final String ENSURE_DATABASE = "ensureDatabase";

    /** Ensure a container or table exists, creating it if absent. */
    public static final String ENSURE_CONTAINER = "ensureContainer";
}

