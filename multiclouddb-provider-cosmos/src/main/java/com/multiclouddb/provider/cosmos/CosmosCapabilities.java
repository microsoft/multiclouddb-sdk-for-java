// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;

import java.util.List;

/**
 * Cosmos DB capabilities declaration.
 */
public final class CosmosCapabilities {

    private CosmosCapabilities() {
    }

    public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
            Capability.CONTINUATION_TOKEN_PAGING_CAP.withNotes("Native Cosmos continuation tokens"),
            Capability.TRANSACTIONS_CAP.withNotes("Transactional batch within a single partition key"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("Bulk and transactional batch"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("Configurable consistency levels including Strong"),
            Capability.CHANGE_FEED_CAP.withNotes("Change feed processor for real-time changes"),
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to Cosmos SQL"),
            Capability.ORDER_BY_CAP.withNotes("ORDER BY restricted to sortKey field for portability")));
}
