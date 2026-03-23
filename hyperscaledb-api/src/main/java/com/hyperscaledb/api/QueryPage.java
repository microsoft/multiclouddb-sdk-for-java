package com.hyperscaledb.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;

/**
 * A single page of query results with optional diagnostics metadata.
 */
public final class QueryPage {

    private final List<JsonNode> items;
    private final String continuationToken;
    private final List<PortabilityWarning> warnings;

    /**
     * Diagnostics for this query page; null when diagnostics are not available.
     */
    private final OperationDiagnostics diagnostics;

    public QueryPage(List<JsonNode> items, String continuationToken,
                     List<PortabilityWarning> warnings, OperationDiagnostics diagnostics) {
        this.items = items != null ? List.copyOf(items) : Collections.emptyList();
        this.continuationToken = continuationToken;
        this.warnings = warnings != null ? List.copyOf(warnings) : Collections.emptyList();
        this.diagnostics = diagnostics;
    }

    public QueryPage(List<JsonNode> items, String continuationToken, List<PortabilityWarning> warnings) {
        this(items, continuationToken, warnings, null);
    }

    public QueryPage(List<JsonNode> items, String continuationToken) {
        this(items, continuationToken, null, null);
    }

    /**
     * Items in this page.
     */
    public List<JsonNode> items() {
        return items;
    }

    /**
     * Opaque continuation token for fetching the next page, or null if this is the
     * last page.
     */
    public String continuationToken() {
        return continuationToken;
    }

    /**
     * Portability warnings emitted for this page (e.g., provider-specific behavior
     * was activated).
     */
    public List<PortabilityWarning> warnings() {
        return warnings;
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
