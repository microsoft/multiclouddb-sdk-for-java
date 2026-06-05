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
            Capability.CROSS_PARTITION_QUERY_CAP.withNotes("Supported via SQL API with cross-partition cost"),
            Capability.TRANSACTIONS_CAP.withNotes("Transactional batch within a single partition key"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("Bulk and transactional batch"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("Configurable consistency levels including Strong"),
            Capability.NATIVE_SQL_QUERY_CAP.withNotes("SQL-like query language"),
            Capability.CHANGE_FEED_CAP.withNotes("Change feed via queryChangeFeed; for distinct CREATE/UPDATE/DELETE events, container must be configured with AllVersionsAndDeletes mode"),
            // Query DSL capabilities
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to Cosmos SQL"),
            Capability.LIKE_OPERATOR_CAP.withNotes("LIKE operator supported via Cosmos SQL"),
            Capability.ORDER_BY_CAP.withNotes("ORDER BY supported in Cosmos SQL queries"),
            Capability.ENDS_WITH_CAP.withNotes("ENDSWITH function available in Cosmos SQL"),
            Capability.REGEX_MATCH_CAP.withNotes("RegexMatch function available in Cosmos SQL"),
            Capability.CASE_FUNCTIONS_CAP.withNotes("UPPER/LOWER functions available in Cosmos SQL"),
            Capability.of(Capability.RESULT_LIMIT, true, "TOP N supported in Cosmos SQL (SELECT TOP N)"),
            Capability.of(Capability.ROW_LEVEL_TTL, true,
                    "Document-level TTL via _ttl field; requires the container to have TTL enabled "
                    + "(set container default TTL to -1 or a positive value in the portal)"),
            Capability.of(Capability.WRITE_TIMESTAMP, true,
                    "ETag exposed as version field in DocumentMetadata on read")));
}
