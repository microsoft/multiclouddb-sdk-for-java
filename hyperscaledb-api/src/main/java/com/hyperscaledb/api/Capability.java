// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

/**
 * A named capability that may be supported (or not) by a provider.
 */
public final class Capability {

    /** Well-known capability names */
    public static final String CONTINUATION_TOKEN_PAGING = "continuation_token_paging";
    public static final String CROSS_PARTITION_QUERY = "cross_partition_query";
    public static final String TRANSACTIONS = "transactions";
    public static final String BATCH_OPERATIONS = "batch_operations";
    public static final String STRONG_CONSISTENCY = "strong_consistency";
    public static final String NATIVE_SQL_QUERY = "native_sql_query";
    public static final String CHANGE_FEED = "change_feed";

    /** Query DSL capabilities */
    public static final String PORTABLE_QUERY_EXPRESSION = "portable_query_expression";
    public static final String LIKE_OPERATOR = "like_operator";
    public static final String ORDER_BY = "order_by";
    public static final String ENDS_WITH = "ends_with";
    public static final String REGEX_MATCH = "regex_match";
    public static final String CASE_FUNCTIONS = "case_functions";

    private final String name;
    private final boolean supported;
    private final String notes;

    public Capability(String name, boolean supported, String notes) {
        this.name = name;
        this.supported = supported;
        this.notes = notes;
    }

    public Capability(String name, boolean supported) {
        this(name, supported, null);
    }

    public String name() {
        return name;
    }

    public boolean supported() {
        return supported;
    }

    public String notes() {
        return notes;
    }

    @Override
    public String toString() {
        return "Capability{" + name + "=" + (supported ? "supported" : "unsupported")
                + (notes != null ? ", notes=" + notes : "") + "}";
    }
}
