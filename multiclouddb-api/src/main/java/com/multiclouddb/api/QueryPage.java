// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;


import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A single page of query results with optional diagnostics metadata.
 */
public final class QueryPage {

    private final List<Map<String, Object>> items;
    private final String continuationToken;

    /**
     * Diagnostics for this query page; null when diagnostics are not available.
     */
    private final OperationDiagnostics diagnostics;

    public QueryPage(List<Map<String, Object>> items, String continuationToken) {
        this(items, continuationToken, null);
    }

    public QueryPage(List<Map<String, Object>> items, String continuationToken,
                     OperationDiagnostics diagnostics) {
        // Defensive deep copy using a null-tolerant copy at both levels:
        // Map.copyOf() and List.copyOf() reject null entries, but schemaless
        // stores (Cosmos / Dynamo) and the Spanner provider round-trip
        // explicit null field values. Use LinkedHashMap (preserves iteration
        // order and tolerates null values) wrapped in unmodifiableMap, then
        // collect to an unmodifiableList.
        this.items = items != null
                ? items.stream()
                        .map(m -> Collections.unmodifiableMap(new java.util.LinkedHashMap<>(m)))
                        .collect(java.util.stream.Collectors.toUnmodifiableList())
                : Collections.emptyList();
        this.continuationToken = (continuationToken != null && !continuationToken.isEmpty())
                ? continuationToken : null;
        this.diagnostics = diagnostics;
    }


    /**
     * Items in this page, each represented as an <em>unmodifiable</em> map of
     * field name to value.
     * <p>
     * Both the list and every document map are unmodifiable; mutations throw
     * {@link UnsupportedOperationException}.
     */
    public List<Map<String, Object>> items() {
        return items;
    }

    /**
     * Opaque continuation token for fetching the next page, or {@code null} if
     * this is the last page.
     * <p>
     * Empty strings are normalised to {@code null} — a {@code null} return
     * always means no more pages are available.
     */
    public String continuationToken() {
        return continuationToken;
    }

    /**
     * Diagnostics for this query page (duration, requestCharge, itemCount, etc.),
     * or null if diagnostics are not available.
     */
    public OperationDiagnostics diagnostics() {
        return diagnostics;
    }

    public boolean hasMore() {
        return continuationToken != null && !continuationToken.isEmpty();
    }

}
