// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;

import java.util.List;

/**
 * Spanner capabilities declaration — fully implemented provider.
 */
public final class SpannerCapabilities {

    private SpannerCapabilities() {
    }

    public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
            Capability.CONTINUATION_TOKEN_PAGING_CAP.withNotes("Offset-based continuation token paging"),
            Capability.TRANSACTIONS_CAP.withNotes("Spanner supports ACID transactions across rows"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("Spanner mutation batches"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("External consistency (linearizability)"),
            Capability.CHANGE_FEED_CAP.withNotes("Change Streams"),
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to Spanner GoogleSQL"),
            Capability.ORDER_BY_CAP.withNotes("ORDER BY restricted to sortKey field for portability")));
}
