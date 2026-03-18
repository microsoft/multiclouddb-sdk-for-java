package com.hyperscaledb.provider.dynamo;

import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * All hardcoded string keys, values, and numeric constants used by the
 * DynamoDB provider in one place.
 * <p>
 * Using this class avoids magic strings scattered across the implementation
 * and makes every tunable value easy to find and change.
 */
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

    // ── Table naming ─────────────────────────────────────────────────────────

    /**
     * Separator used to encode the database dimension into a DynamoDB table name.
     * Table names follow the pattern: {@code database + TABLE_NAME_SEPARATOR + collection}.
     */
    public static final String TABLE_NAME_SEPARATOR = "__";

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

    /** Partition key scoping condition for DynamoDB scan filter expressions. */
    public static final String SCAN_PARTITION_KEY_CONDITION =
            ATTR_PARTITION_KEY + " = :_pkval";

    /** Scan filter parameter name for partition key scoping. */
    public static final String SCAN_PARTITION_KEY_PARAM = ":_pkval";

    /** Internal parameter key used to pass partition key value through query parameter maps. */
    public static final String QUERY_PARTITION_KEY_PARAM = "_pkval";
}

