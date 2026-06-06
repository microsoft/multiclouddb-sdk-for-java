// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, portable query request.
 * <p>
 * Encapsulates an optional portable filter expression, named parameters,
 * a maximum page size hint, an opaque continuation token for paging, and a
 * <strong>required</strong> partition key for provider-native scoping.
 * <p>
 * All map accessors return <em>unmodifiable</em> views — any attempt to mutate
 * them will throw {@link UnsupportedOperationException}.
 * Use {@link #builder()} to construct instances.
 *
 * <h3>Portability contract (strict LCD)</h3>
 * <ul>
 *   <li>{@link #partitionKey()} is <strong>required</strong> on every query
 *       (no cross-partition scans — DynamoDB does not support them natively).</li>
 *   <li>{@link #expression()} must be a portable expression that all providers
 *       can translate (no provider-native SQL passthrough).</li>
 *   <li>{@link #orderBy()} is restricted to the {@code sortKey} field —
 *       arbitrary-field ordering is not supported by DynamoDB across pages.</li>
 *   <li>{@link #maxResults()} caps the <em>total</em> number of items returned
 *       across paginated calls. Providers paginate normally; the cap is
 *       enforced client-side by the SDK.</li>
 * </ul>
 */
public final class QueryRequest {

    private final String expression;
    private final Map<String, Object> parameters;
    private final Integer maxPageSize;
    private final String continuationToken;
    private final String partitionKey;
    private final Integer maxResults;
    private final List<SortOrder> orderBy;

    private QueryRequest(Builder builder) {
        if (builder.partitionKey == null || builder.partitionKey.isBlank()) {
            throw new IllegalArgumentException(
                    "partitionKey is required — cross-partition queries are not portable across providers");
        }
        if (builder.maxResults != null && builder.maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1 when set");
        }
        if (builder.orderBy != null) {
            for (SortOrder so : builder.orderBy) {
                if (!"sortKey".equals(so.field())) {
                    throw new IllegalArgumentException(
                            "orderBy is restricted to the 'sortKey' field for portability; got: " + so.field());
                }
            }
        }
        this.expression = builder.expression;
        this.parameters = builder.parameters != null ? Map.copyOf(builder.parameters) : Collections.emptyMap();
        this.maxPageSize = builder.maxPageSize;
        this.continuationToken = builder.continuationToken;
        this.partitionKey = builder.partitionKey;
        this.maxResults = builder.maxResults;
        this.orderBy = builder.orderBy != null ? List.copyOf(builder.orderBy) : Collections.emptyList();
    }

    /**
     * The portable filter expression, or {@code null} for a full partition scan.
     * <p>
     * Must be parsable by the portable expression grammar. Provider-specific SQL
     * is not supported.
     */
    public String expression() {
        return expression;
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
     *       pages by byte size regardless of item count.</li>
     * </ul>
     * When {@code null} the provider uses its own default page size.
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
     * The partition key value to scope the query (required).
     * <p>Every provider uses its native mechanism to restrict the query
     * to items sharing this partition key value:
     * <ul>
     *   <li>Cosmos&nbsp;DB — {@code CosmosQueryRequestOptions.setPartitionKey()}</li>
     *   <li>DynamoDB — adds a {@code partitionKey} equality condition</li>
     *   <li>Spanner — adds a {@code partitionKey} equality condition</li>
     * </ul>
     *
     * @return non-null partition key value
     */
    public String partitionKey() {
        return partitionKey;
    }

    /**
     * Optional total cap on the number of items returned across all pages.
     * <p>
     * Enforced client-side by the SDK: pagination stops once the cumulative
     * item count reaches this value. {@code null} means no cap.
     *
     * @return the maximum total items, or {@code null}
     */
    public Integer maxResults() {
        return maxResults;
    }

    /**
     * Optional list of sort specifications. Restricted to the {@code sortKey}
     * field for portability — arbitrary-field ordering is not supported by
     * DynamoDB across pages. Empty list means provider default ordering
     * (partition key, then sort key ascending).
     *
     * @return unmodifiable list of sort orders, never null
     */
    public List<SortOrder> orderBy() {
        return orderBy;
    }

    @Override
    public String toString() {
        return "QueryRequest{"
                + "expression='" + expression + '\''
                + ", partitionKey='" + partitionKey + '\''
                + ", maxPageSize=" + maxPageSize
                + ", maxResults=" + maxResults
                + ", continuationToken='" + continuationToken + '\''
                + ", parameters=" + parameters
                + ", orderBy=" + orderBy
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String expression;
        private Map<String, Object> parameters;
        private Integer maxPageSize;
        private String continuationToken;
        private String partitionKey;
        private Integer maxResults;
        private List<SortOrder> orderBy;

        /**
         * Set the portable filter expression. The expression must parse with the
         * portable expression grammar; provider-specific SQL is not supported.
         */
        public Builder expression(String expression) {
            this.expression = expression;
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
         * Scope the query to items sharing this partition key value. Required.
         *
         * @param partitionKey the partition key value (non-null, non-blank)
         */
        public Builder partitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
            return this;
        }

        /**
         * Cap the total number of items returned across all pages. Enforced
         * client-side by the SDK.
         *
         * @param maxResults maximum total items (must be >= 1)
         * @return this builder
         */
        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        /**
         * Append a sort specification. Restricted to the {@code sortKey} field
         * for portability — passing any other field name throws
         * {@link IllegalArgumentException} at {@link #build()}.
         *
         * @param field     must be {@code "sortKey"}
         * @param direction the sort direction
         * @return this builder
         */
        public Builder orderBy(String field, SortDirection direction) {
            if (this.orderBy == null) {
                this.orderBy = new ArrayList<>();
            }
            this.orderBy.add(SortOrder.of(Objects.requireNonNull(field), direction));
            return this;
        }

        public QueryRequest build() {
            return new QueryRequest(this);
        }
    }
}
