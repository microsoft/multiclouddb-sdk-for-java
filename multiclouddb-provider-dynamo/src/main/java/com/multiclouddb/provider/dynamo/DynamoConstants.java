// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.ResourceAddress;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * All hardcoded string keys, values, and numeric constants used by the
 * DynamoDB provider in one place.
 * <p>
 * Using this class avoids magic strings scattered across the implementation
 * and makes every tunable value easy to find and change.
 */
@SuppressWarnings("unused") // constants are referenced from DynamoProviderClient and DynamoConstantsTest
public final class DynamoConstants {

    private DynamoConstants() {}

    // ── Config property keys (match properties file / System property names) ─

    /** Connection config key for the AWS region (e.g. {@code us-east-1}). */
    public static final String CONFIG_REGION = "region";

    /** Connection config key for a custom endpoint URL (used for DynamoDB Local). */
    public static final String CONFIG_ENDPOINT = "endpoint";

    /** Auth config key for the AWS access key ID. */
    public static final String CONFIG_ACCESS_KEY_ID = "accessKeyId";

    /** Auth config key for the AWS secret access key. */
    public static final String CONFIG_SECRET_ACCESS_KEY = "secretAccessKey";

    // ── Default values ────────────────────────────────────────────────────────

    /** Default AWS region applied when {@code region} is not configured. */
    public static final String REGION_DEFAULT = "us-east-1";

    // ── DynamoDB attribute (field) names ──────────────────────────────────────

    /** DynamoDB attribute name for the partition / hash key. */
    public static final String ATTR_PARTITION_KEY = "partitionKey";

    /** DynamoDB attribute name for the sort / range key. */
    public static final String ATTR_SORT_KEY = "sortKey";

    /**
     * DynamoDB attribute name that holds the Unix epoch expiry timestamp for item TTL.
     * DynamoDB's TTL feature must be enabled on the table and configured to use this
     * attribute name (via {@code UpdateTimeToLive} or the console).  When TTL is not
     * enabled on the table, DynamoDB silently ignores the attribute — writes succeed
     * and items are never auto-deleted.  The value written is epoch-seconds
     * ({@code Instant.now().getEpochSecond() + ttlSeconds}).
     */
    public static final String ATTR_TTL_EXPIRY = "ttlExpiry";

    // ── Table naming ─────────────────────────────────────────────────────────

    /**
     * Separator used to encode the database dimension into a DynamoDB table name.
     * Table names follow the pattern: {@code database + TABLE_NAME_SEPARATOR + collection}.
     */
    public static final String TABLE_NAME_SEPARATOR = "__";

    /**
     * Resolve the physical DynamoDB table name for a logical
     * {@link ResourceAddress}. Table names are encoded as
     * {@code database + TABLE_NAME_SEPARATOR + collection}; every Dynamo
     * provider call site (CRUD, query, change-feed) must use this helper so
     * the encoding stays consistent.
     */
    public static String tableNameFor(ResourceAddress address) {
        return address.database() + TABLE_NAME_SEPARATOR + address.collection();
    }

    // ── Table schema ─────────────────────────────────────────────────────────

    /** Billing mode used when creating new tables. */
    public static final BillingMode TABLE_BILLING_MODE = BillingMode.PAY_PER_REQUEST;

    /** Attribute type for both partition key and sort key columns. */
    public static final ScalarAttributeType KEY_ATTRIBUTE_TYPE = ScalarAttributeType.S;

    // ── Paging ────────────────────────────────────────────────────────────────

    /** Default page size used when the caller does not specify one. */
    public static final int PAGE_SIZE_DEFAULT = 100;

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Cosmos-style select-all expression that maps to a full DynamoDB scan. */
    public static final String QUERY_SELECT_ALL_COSMOS = "SELECT * FROM c";

    /** Prefix for DynamoDB filter expression parameter names. */
    public static final String FILTER_PARAM_PREFIX = ":";

    /** Quoted partition key used in PartiQL WHERE clauses. */
    public static final String PARTIQL_PARTITION_KEY = "\"" + ATTR_PARTITION_KEY + "\"";

    /** PartiQL positional parameter placeholder. */
    public static final String PARTIQL_PARAM_PLACEHOLDER = "?";

    /** SQL keyword used to detect existing WHERE clauses when appending conditions. */
    public static final String SQL_WHERE = "WHERE";

    /** Partition key scoping condition appended to PartiQL statements. */
    public static final String PARTIQL_PARTITION_KEY_CONDITION =
            PARTIQL_PARTITION_KEY + " = " + PARTIQL_PARAM_PLACEHOLDER;

    /** Scan filter parameter name for partition key scoping. */
    public static final String SCAN_PARTITION_KEY_PARAM = ":_pkval";

    /** Internal parameter key used to pass partition key value through query parameter maps. */
    public static final String QUERY_PARTITION_KEY_PARAM = "_pkval";

    // ── HTTP / response metadata ──────────────────────────────────────────────

    /**
     * HTTP response header name used to extract the AWS request ID from
     * responses that do not expose {@code responseMetadata()} directly (e.g. Scan).
     */
    public static final String HEADER_REQUEST_ID = "x-amzn-requestid";

    // ── Operation names (DynamoDB-specific query variants) ────────────────────
    //
    // Shared operation names (create, read, update, upsert, delete, query,
    // queryWithTranslation, ensureDatabase, ensureContainer) are defined in
    // com.multiclouddb.api.OperationNames and should be referenced from there.

    /** Operation name used in diagnostics for native PartiQL passthrough queries. */
    public static final String OP_QUERY_PARTIQL = "query(partiql)";

    /** Operation name used in diagnostics for full-table scan queries. */
    public static final String OP_QUERY_SCAN = "query(scan)";

    /** Operation name used in diagnostics for filtered scan queries (no partition key scoping). */
    public static final String OP_QUERY_SCAN_FILTER = "query(scan+filter)";

    /**
     * Operation name used in diagnostics for partition-key-scoped queries via
     * DynamoDB's Query API ({@code KeyConditionExpression}).
     * This is O(partition size) — preferred over Scan+FilterExpression which is O(table size).
     */
    public static final String OP_QUERY_KEY_CONDITION = "query(key-condition)";

    /**
     * {@code KeyConditionExpression} used to scope a DynamoDB Query to a single
     * partition (hash key). Used with the DynamoDB {@code QueryRequest} API
     * (hash-key index lookup, O(partition size)).
     */
    public static final String KEY_CONDITION_EXPRESSION =
            ATTR_PARTITION_KEY + " = :_pkval";

    /**
     * Expression attribute value key bound to the partition key value in
     * {@link #KEY_CONDITION_EXPRESSION}.
     */
    public static final String KEY_CONDITION_PK_PARAM = ":_pkval";
}

