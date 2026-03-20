// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable, portable query request.
 * <p>
 * Encapsulates an optional filter expression, named parameters, a maximum page
 * size hint, an opaque continuation token for paging, and an optional partition
 * key for provider-native scoping.
 * <p>
 * All map accessors return <em>unmodifiable</em> views — any attempt to mutate
 * them will throw {@link UnsupportedOperationException}.
 * Use {@link #builder()} to construct instances.
 */
public final class QueryRequest {

    private final String expression;
    private final String nativeExpression;
    private final Map<String, Object> parameters;
    private final Integer maxPageSize;
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
        this.maxPageSize = builder.maxPageSize;
        this.continuationToken = builder.continuationToken;
        this.partitionKey = builder.partitionKey;
    }

    /**
     * The portable filter expression, or {@code null} for a full scan.
     * <p>
     * Mutually exclusive with {@link #nativeExpression()}.
     */
    public String expression() {
        return expression;
    }

    /**
     * A provider-native query expression that bypasses portable translation
     * entirely.
     * <p>
     * <strong>Warning — this breaks portability.</strong> A native expression
     * is specific to one provider's query language (Cosmos SQL, DynamoDB PartiQL,
     * Spanner GoogleSQL). Code using this field cannot be switched to a different
     * provider by configuration alone. Use it only as a last resort when the
     * portable {@link #expression()} cannot express what you need.
     * <p>
     * Mutually exclusive with {@link #expression()} — setting both throws
     * {@link IllegalArgumentException} at build time.
     *
     * @return the provider-native expression string, or {@code null}
     */
    public String nativeExpression() {
        return nativeExpression;
    }

    /**
     * Named query parameters bound to the expression (e.g. {@code @status → "active"}).
     * <p>
     * The returned map is <em>unmodifiable</em> — it is a deep copy taken at
     * construction time, so neither the original map passed to the builder nor
     * this accessor can be used to mutate the {@code QueryRequest}.
     * Mutations throw {@link UnsupportedOperationException}.
     * Returns an empty map when no parameters were supplied.
     */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * The maximum number of items the caller wants in a single page.
     * <p>
     * This is a <strong>hint</strong>, not a guarantee:
     * <ul>
     *   <li>Providers <em>will not</em> return more items than this value.</li>
     *   <li>Providers <em>may</em> return fewer — for example, DynamoDB caps
     *       pages by byte size regardless of item count, and any provider may
     *       apply its own internal limits.</li>
     * </ul>
     * When {@code null} the provider uses its own default page size.
     * Check {@link QueryPage#continuationToken()} to determine whether more
     * pages are available regardless of how many items were returned.
     *
     * @return the requested upper bound on items per page, or {@code null}
     */
    public Integer maxPageSize() {
        return maxPageSize;
    }

    /**
     * Opaque continuation token from a previous {@link QueryPage}.
     * {@code null} for the first page.
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
     *   <li>DynamoDB &ndash; adds a {@code partitionKey} equality condition</li>
     *   <li>Spanner &ndash; adds a {@code partitionKey} equality condition</li>
     * </ul>
     * <p>When {@code null} (the default), the query is sent cross-partition.
     *
     * @return partition key value, or {@code null}
     */
    public String partitionKey() {
        return partitionKey;
    }

    @Override
    public String toString() {
        return "QueryRequest{"
                + "expression='" + expression + '\''
                + ", nativeExpression='" + nativeExpression + '\''
                + ", partitionKey='" + partitionKey + '\''
                + ", maxPageSize=" + maxPageSize
                + ", continuationToken='" + continuationToken + '\''
                + ", parameters=" + parameters
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String expression;
        private String nativeExpression;
        private Map<String, Object> parameters;
        private Integer maxPageSize;
        private String continuationToken;
        private String partitionKey;

        /**
         * Set the portable filter expression.
         * Mutually exclusive with {@link #nativeExpression(String)}.
         */
        public Builder expression(String expression) {
            this.expression = expression;
            return this;
        }

        /**
         * Set a provider-native expression that bypasses portable translation.
         * <p>
         * <strong>Warning — this breaks portability.</strong> Use only when the
         * portable {@link #expression(String)} cannot express what you need.
         * Mutually exclusive with {@link #expression(String)}.
         */
        public Builder nativeExpression(String nativeExpression) {
            this.nativeExpression = nativeExpression;
            return this;
        }

        /**
         * Set query parameters as a bulk map, <strong>replacing</strong> any
         * parameters previously set via {@link #parameter(String, Object)}.
         * <p>
         * A defensive copy is made immediately; subsequent mutations to
         * {@code params} do not affect this builder or the built
         * {@link QueryRequest}.
         */
        public Builder parameters(Map<String, Object> params) {
            this.parameters = params != null ? new HashMap<>(params) : null;
            return this;
        }

        /**
         * Add a single named query parameter.
         * May be called multiple times to accumulate parameters.
         *
         * @param name  the parameter name (e.g. {@code "@status"})
         * @param value the parameter value
         */
        public Builder parameter(String name, Object value) {
            if (this.parameters == null) {
                this.parameters = new HashMap<>();
            }
            this.parameters.put(name, value);
            return this;
        }

        /**
         * Set the maximum number of items per page.
         * <p>
         * This is a <strong>hint</strong> — providers will not exceed this count
         * but may return fewer. See {@link QueryRequest#maxPageSize()} for details.
         */
        public Builder maxPageSize(int maxPageSize) {
            if (maxPageSize <= 0) {
                throw new IllegalArgumentException("maxPageSize must be positive");
            }
            this.maxPageSize = maxPageSize;
            return this;
        }

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        /**
         * Scope the query to items sharing this partition key value.
         *
         * @param partitionKey the partition key value ({@code null} for cross-partition)
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
