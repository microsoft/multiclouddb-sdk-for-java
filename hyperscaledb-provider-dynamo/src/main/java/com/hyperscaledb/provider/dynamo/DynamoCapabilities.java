package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.CapabilitySet;

import java.util.List;

/**
 * DynamoDB capabilities declaration.
 */
public final class DynamoCapabilities {

        private DynamoCapabilities() {
        }

        public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
                        new Capability(Capability.CONTINUATION_TOKEN_PAGING, true,
                                        "Uses LastEvaluatedKey serialized as opaque token"),
                        new Capability(Capability.CROSS_PARTITION_QUERY, false,
                                        "DynamoDB scans are not partition-targeted queries"),
                        new Capability(Capability.TRANSACTIONS, true,
                                        "TransactWriteItems / TransactGetItems (up to 100 items)"),
                        new Capability(Capability.BATCH_OPERATIONS, true,
                                        "BatchWriteItem and BatchGetItem (up to 25/100 items)"),
                        new Capability(Capability.STRONG_CONSISTENCY, true,
                                        "Strongly consistent reads supported on individual items"),
                        new Capability(Capability.NATIVE_SQL_QUERY, false,
                                        "PartiQL is available but not SQL; filter expressions used for scans"),
                        new Capability(Capability.CHANGE_FEED, true, "DynamoDB Streams for change data capture"),
                        // Query DSL capabilities
                        new Capability(Capability.PORTABLE_QUERY_EXPRESSION, true,
                                        "Portable expression translation to DynamoDB PartiQL"),
                        new Capability(Capability.LIKE_OPERATOR, false,
                                        "LIKE not natively supported in PartiQL on DynamoDB"),
                        new Capability(Capability.ORDER_BY, false, "ORDER BY not supported in DynamoDB PartiQL scans"),
                        new Capability(Capability.ENDS_WITH, false, "No native ends_with in DynamoDB PartiQL"),
                        new Capability(Capability.REGEX_MATCH, false, "No native regex support in DynamoDB PartiQL"),
                        new Capability(Capability.CASE_FUNCTIONS, false, "No native UPPER/LOWER in DynamoDB PartiQL")));
}
