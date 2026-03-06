package com.hyperscaledb.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single page of query results.
 */
public final class QueryPage {

    private final List<JsonNode> items;
    private final String continuationToken;
    private final List<PortabilityWarning> warnings;

    public QueryPage(List<JsonNode> items, String continuationToken, List<PortabilityWarning> warnings) {
        this.items = items != null ? List.copyOf(items) : Collections.emptyList();
        this.continuationToken = continuationToken;
        this.warnings = warnings != null ? List.copyOf(warnings) : Collections.emptyList();
    }

    public QueryPage(List<JsonNode> items, String continuationToken) {
        this(items, continuationToken, null);
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

    public boolean hasMore() {
        return continuationToken != null && !continuationToken.isEmpty();
    }
}
