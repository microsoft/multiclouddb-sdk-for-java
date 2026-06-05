// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.OperationDiagnostics;

import java.util.Collections;
import java.util.List;

/**
 * A single page of change-feed events and diagnostics.
 * <p>
 * {@link #continuationToken()} is the opaque resumption cursor; {@code null}
 * means no further events are available <em>at this moment</em> (an empty page
 * with a non-null token indicates an idle feed; an empty page with a null
 * token means the requested time window has been fully consumed).
 */
public final class ChangeFeedPage {

    private final List<ChangeEvent> events;
    private final String continuationToken;
    private final OperationDiagnostics diagnostics;

    /** Primary constructor for change-feed page results. */
    public ChangeFeedPage(List<ChangeEvent> events, String continuationToken,
                          OperationDiagnostics diagnostics) {
        this.events = events != null ? List.copyOf(events) : Collections.emptyList();
        this.continuationToken = (continuationToken != null && !continuationToken.isEmpty())
                ? continuationToken : null;
        this.diagnostics = diagnostics;
    }

    public List<ChangeEvent> events() { return events; }
    public String continuationToken() { return continuationToken; }
    public OperationDiagnostics diagnostics() { return diagnostics; }

    public boolean hasMore() {
        return continuationToken != null;
    }
}
