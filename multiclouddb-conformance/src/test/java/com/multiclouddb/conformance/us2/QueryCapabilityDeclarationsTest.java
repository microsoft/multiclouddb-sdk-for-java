// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.provider.cosmos.CosmosCapabilities;
import com.multiclouddb.provider.dynamo.DynamoCapabilities;
import com.multiclouddb.provider.spanner.SpannerCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryCapabilityDeclarationsTest {

    @Test
    void queryExtensionsRetainProviderSpecificSupport() {
        for (String name : List.of(
                Capability.CROSS_PARTITION_QUERY, Capability.NATIVE_SQL_QUERY,
                Capability.ORDER_BY, Capability.LIKE_OPERATOR, Capability.ENDS_WITH,
                Capability.REGEX_MATCH, Capability.CASE_FUNCTIONS, Capability.RESULT_LIMIT)) {
            assertTrue(CosmosCapabilities.CAPABILITIES.isSupported(name), name);
            assertTrue(SpannerCapabilities.CAPABILITIES.isSupported(name), name);
            assertNotNull(DynamoCapabilities.CAPABILITIES.get(name), name);
            assertFalse(DynamoCapabilities.CAPABILITIES.isSupported(name), name);
        }
    }

    @Test
    void commonBaselineDoesNotRequireOrdering() {
        for (CapabilitySet caps : List.of(CosmosCapabilities.CAPABILITIES,
                DynamoCapabilities.CAPABILITIES, SpannerCapabilities.CAPABILITIES)) {
            for (String name : List.of(Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.TRANSACTIONS, Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY, Capability.CHANGE_FEED,
                    Capability.PORTABLE_QUERY_EXPRESSION)) {
                assertTrue(caps.isSupported(name), name);
            }
        }
    }

    @Test
    void extendedHistoryRemainsAnOptionalExtension() {
        assertTrue(CosmosCapabilities.CAPABILITIES.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY));
        assertTrue(SpannerCapabilities.CAPABILITIES.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY));
        assertNotNull(DynamoCapabilities.CAPABILITIES.get(Capability.EXTENDED_CHANGE_FEED_HISTORY));
        assertFalse(DynamoCapabilities.CAPABILITIES.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY));
    }

    @Test
    void ttlAndMetadataRetainMainSupportDeclarations() {
        assertTrue(CosmosCapabilities.CAPABILITIES.isSupported(Capability.ROW_LEVEL_TTL));
        assertTrue(DynamoCapabilities.CAPABILITIES.isSupported(Capability.ROW_LEVEL_TTL));
        assertNotNull(SpannerCapabilities.CAPABILITIES.get(Capability.ROW_LEVEL_TTL));
        assertFalse(SpannerCapabilities.CAPABILITIES.isSupported(Capability.ROW_LEVEL_TTL));
        assertTrue(CosmosCapabilities.CAPABILITIES.isSupported(Capability.WRITE_TIMESTAMP));
        assertNotNull(DynamoCapabilities.CAPABILITIES.get(Capability.WRITE_TIMESTAMP));
        assertNotNull(SpannerCapabilities.CAPABILITIES.get(Capability.WRITE_TIMESTAMP));
        assertFalse(DynamoCapabilities.CAPABILITIES.isSupported(Capability.WRITE_TIMESTAMP));
        assertFalse(SpannerCapabilities.CAPABILITIES.isSupported(Capability.WRITE_TIMESTAMP));
    }
}
