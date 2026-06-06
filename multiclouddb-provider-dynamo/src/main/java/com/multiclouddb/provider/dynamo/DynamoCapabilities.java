// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;

import java.util.List;

/**
 * DynamoDB capabilities declaration.
 */
public final class DynamoCapabilities {

    private DynamoCapabilities() {
    }

    public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
            Capability.CONTINUATION_TOKEN_PAGING_CAP.withNotes("Uses LastEvaluatedKey serialized as opaque token"),
            Capability.TRANSACTIONS_CAP.withNotes("TransactWriteItems / TransactGetItems (up to 100 items)"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("BatchWriteItem and BatchGetItem (up to 25/100 items)"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("Strongly consistent reads supported on individual items"),
            Capability.CHANGE_FEED_CAP.withNotes("DynamoDB Streams for change data capture"),
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to DynamoDB PartiQL"),
            Capability.ORDER_BY_CAP.withNotes("ORDER BY restricted to sortKey field — emulated via ScanIndexForward")));
}
