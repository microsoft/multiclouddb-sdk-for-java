package com.hyperscaledb.provider.cosmos;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.CapabilitySet;

import java.util.List;

/**
 * Cosmos DB capabilities declaration.
 */
public final class CosmosCapabilities {

    private CosmosCapabilities() {
    }

    public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
            new Capability(Capability.CONTINUATION_TOKEN_PAGING, true, "Native Cosmos continuation tokens"),
            new Capability(Capability.CROSS_PARTITION_QUERY, true, "Supported via SQL API with cross-partition cost"),
            new Capability(Capability.TRANSACTIONS, true, "Transactional batch within a single partition key"),
            new Capability(Capability.BATCH_OPERATIONS, true, "Bulk and transactional batch"),
            new Capability(Capability.STRONG_CONSISTENCY, true, "Configurable consistency levels including Strong"),
            new Capability(Capability.NATIVE_SQL_QUERY, true, "SQL-like query language"),
            new Capability(Capability.CHANGE_FEED, true, "Change feed processor for real-time changes"),
            // Query DSL capabilities
            new Capability(Capability.PORTABLE_QUERY_EXPRESSION, true, "Portable expression translation to Cosmos SQL"),
            new Capability(Capability.LIKE_OPERATOR, true, "LIKE operator supported via Cosmos SQL"),
            new Capability(Capability.ORDER_BY, true, "ORDER BY supported in Cosmos SQL queries"),
            new Capability(Capability.ENDS_WITH, true, "ENDSWITH function available in Cosmos SQL"),
            new Capability(Capability.REGEX_MATCH, true, "RegexMatch function available in Cosmos SQL"),
            new Capability(Capability.CASE_FUNCTIONS, true, "UPPER/LOWER functions available in Cosmos SQL")));
}
