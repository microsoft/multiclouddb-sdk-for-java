// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.CapabilitySet;

import java.util.List;

/**
 * Spanner capabilities declaration — fully implemented provider.
 */
public final class SpannerCapabilities {

        private SpannerCapabilities() {
        }

        public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
                        new Capability(Capability.CONTINUATION_TOKEN_PAGING, true,
                                        "Offset-based continuation token paging"),
                        new Capability(Capability.CROSS_PARTITION_QUERY, true,
                                        "Spanner supports distributed queries natively"),
                        new Capability(Capability.TRANSACTIONS, true, "Spanner supports ACID transactions across rows"),
                        new Capability(Capability.BATCH_OPERATIONS, true, "Spanner mutation batches"),
                        new Capability(Capability.STRONG_CONSISTENCY, true, "External consistency (linearizability)"),
                        new Capability(Capability.NATIVE_SQL_QUERY, true, "Full GoogleSQL or PostgreSQL-dialect SQL"),
                        new Capability(Capability.CHANGE_FEED, true, "Change Streams"),
                        // Query DSL capabilities
                        new Capability(Capability.PORTABLE_QUERY_EXPRESSION, true,
                                        "Portable expression translation to Spanner GoogleSQL"),
                        new Capability(Capability.LIKE_OPERATOR, true, "LIKE operator supported in GoogleSQL"),
                        new Capability(Capability.ORDER_BY, true, "ORDER BY supported in GoogleSQL queries"),
                        new Capability(Capability.ENDS_WITH, true, "ENDS_WITH function available in GoogleSQL"),
                        new Capability(Capability.REGEX_MATCH, true, "REGEXP_CONTAINS available in GoogleSQL"),
                        new Capability(Capability.CASE_FUNCTIONS, true,
                                        "UPPER/LOWER functions available in GoogleSQL")));
}
