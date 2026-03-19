// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A single page of query results.
 */
public final class QueryPage {

    private final List<Map<String, Object>> items;
    private final String continuationToken;
    private final List<PortabilityWarning> warnings;

    public QueryPage(List<Map<String, Object>> items, String continuationToken, List<PortabilityWarning> warnings) {
        this.items = items != null ? List.copyOf(items) : Collections.emptyList();
        this.continuationToken = continuationToken;
        this.warnings = warnings != null ? List.copyOf(warnings) : Collections.emptyList();
    }

    public QueryPage(List<Map<String, Object>> items, String continuationToken) {
        this(items, continuationToken, null);
    }

    /**
     * Items in this page, each represented as a map of field name to value.
     */
    public List<Map<String, Object>> items() {
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
