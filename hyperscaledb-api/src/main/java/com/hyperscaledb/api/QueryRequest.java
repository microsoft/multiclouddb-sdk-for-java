// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable, portable query request.
 * <p>
 * Encapsulates an optional filter expression (portable or native), named
 * parameters, page size, an opaque continuation token for paging, and an
 * optional partition key for provider-native scoping.
 * <p>
 * All map accessors return <em>unmodifiable</em> views — any attempt to mutate
 * them will throw {@link UnsupportedOperationException}.
 * Use {@link #builder()} to construct instances.
 */
public final class QueryRequest {

    private final String expression;
    private final String nativeExpression;
    private final Map<String, Object> parameters;
    private final Integer pageSize;
    private final String continuationToken;
    private final String partitionKey;

    private QueryRequest(Builder builder) {
        if (builder.expression != null && builder.nativeExpression != null) {
            throw new IllegalArgumentException(
                    "expression and nativeExpression are mutually exclusive; set only one");
        }
        this.expression = builder.expression;
        this.nativeExpression = builder.nativeExpression;
        this.parameters = builder.parameters != null ? Map.copyOf(builder.parameters) : Collections.emptyMap();
        this.pageSize = builder.pageSize;
        this.continuationToken = builder.continuationToken;
        this.partitionKey = builder.partitionKey;
    }

    public String expression() {
        return expression;
    }

    /**
     * Provider-native query expression (mutually exclusive with
     * {@link #expression()}).
     * When set, the expression is passed directly to the provider without
     * translation.
     */
    public String nativeExpression() {
        return nativeExpression;
    }

    /**
     * Named query parameters bound to the expression (e.g. {@code @status → "active"}).
     * <p>
     * The returned map is <em>unmodifiable</em>; mutations throw
     * {@link UnsupportedOperationException}. Returns an empty map when no parameters
     * were supplied.
     */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * Preferred maximum items per page. Providers may return fewer or more.
     */
    public Integer pageSize() {
        return pageSize;
    }

    /**
     * Opaque continuation token from a previous QueryPage. Null for the first page.
     */
    public String continuationToken() {
        return continuationToken;
    }

    /**
     * Optional partition key value to scope the query.
     * <p>When set, each provider uses its native mechanism to restrict the query
     * to items sharing this partition key value:
     * <ul>
     *   <li>Cosmos&nbsp;DB &ndash; {@code CosmosQueryRequestOptions.setPartitionKey()}</li>
     *   <li>DynamoDB &ndash; adds a {@code sortKey} equality condition</li>
     *   <li>Spanner &ndash; adds a {@code sortKey} equality condition</li>
     * </ul>
     * <p>When {@code null} (the default), the query is sent cross-partition as before.
     * This field may be combined with {@link #expression()} or
     * {@link #nativeExpression()} for further filtering.
     *
     * @return partition key value, or {@code null}
     */
    public String partitionKey() {
        return partitionKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String expression;
        private String nativeExpression;
        private Map<String, Object> parameters;
        private Integer pageSize;
        private String continuationToken;
        private String partitionKey;

        public Builder expression(String expression) {
            this.expression = expression;
            return this;
        }

        /**
         * Set a provider-native expression (mutually exclusive with
         * {@link #expression(String)}).
         */
        public Builder nativeExpression(String nativeExpression) {
            this.nativeExpression = nativeExpression;
            return this;
        }

        /**
         * Set query parameters.
         * A defensive copy is made immediately; subsequent mutations to
         * {@code parameters} do not affect this builder.
         */
        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters != null ? new HashMap<>(parameters) : null;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        /**
         * Scope the query to items sharing this partition key value.
         * Each provider maps this to its native partition-scoping mechanism.
         *
         * @param partitionKey the partition key value (pass {@code null} for cross-partition)
         * @return this builder
         */
        public Builder partitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
            return this;
        }

        public QueryRequest build() {
            return new QueryRequest(this);
        }
    }
}
