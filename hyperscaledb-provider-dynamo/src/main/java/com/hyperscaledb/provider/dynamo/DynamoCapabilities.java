// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

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
            Capability.CONTINUATION_TOKEN_PAGING_CAP.withNotes("Uses LastEvaluatedKey serialized as opaque token"),
            Capability.CROSS_PARTITION_QUERY_UNSUPPORTED.withNotes("DynamoDB scans are not partition-targeted queries"),
            Capability.TRANSACTIONS_CAP.withNotes("TransactWriteItems / TransactGetItems (up to 100 items)"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("BatchWriteItem and BatchGetItem (up to 25/100 items)"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("Strongly consistent reads supported on individual items"),
            Capability.NATIVE_SQL_QUERY_UNSUPPORTED.withNotes("PartiQL is available but not SQL; filter expressions used for scans"),
            Capability.CHANGE_FEED_CAP.withNotes("DynamoDB Streams for change data capture"),
            // Query DSL capabilities
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to DynamoDB PartiQL"),
            Capability.LIKE_OPERATOR_UNSUPPORTED.withNotes("LIKE not natively supported in PartiQL on DynamoDB"),
            Capability.ORDER_BY_UNSUPPORTED.withNotes("ORDER BY not supported in DynamoDB PartiQL scans"),
            Capability.ENDS_WITH_UNSUPPORTED.withNotes("No native ends_with in DynamoDB PartiQL"),
            Capability.REGEX_MATCH_UNSUPPORTED.withNotes("No native regex support in DynamoDB PartiQL"),
            Capability.CASE_FUNCTIONS_UNSUPPORTED.withNotes("No native UPPER/LOWER in DynamoDB PartiQL")));
}
