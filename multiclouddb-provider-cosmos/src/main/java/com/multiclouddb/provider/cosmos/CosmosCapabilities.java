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
            Capability.CHANGE_FEED_CAP.withNotes("Change feed processor for real-time changes"),
            Capability.EXTENDED_CHANGE_FEED_HISTORY_CAP.withNotes(
                    "Up to 30 days via Continuous Backup 30d tier; 7d minimum (AVAD requires Continuous Backup)"),
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
                    "ETag exposed as version field in DocumentMetadata on read"),
            // Partial update (feature 002-partial-update): the core operation is universally
            // supported and gated internally by DefaultMulticloudDbClient. The extended-payload
            // guarantee is unsupported because a 100 patch-operation or 2,097,152-byte
            // transactional-batch limit, or the 2,097,152-byte resulting-document limit,
            // may bind before the common 408,576-byte field limit.
            Capability.PARTIAL_UPDATE_CAP.withNotes(
                    "Native patch: direct patchItem for <=10 fields, one same-item transactional batch for wider requests"),
            Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD_UNSUPPORTED.withNotes(
                    "Native envelope caps at 100 patch operations, 2,097,152 serialized batch bytes, "
                    + "or a 2,097,152-byte resulting document"),
            Capability.PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS_CAP.withNotes(
                    "JSON property names preserve literal case-sensitive identity")));
}
