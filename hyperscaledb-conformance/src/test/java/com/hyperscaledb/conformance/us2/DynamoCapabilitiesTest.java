// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamoDB capability conformance test — verifies unsupported capabilities
 * are correctly declared.
 */
@Tag("dynamo")
@Tag("emulator")
public class DynamoCapabilitiesTest extends CapabilitiesConformanceTest {
    @Override
    protected ProviderId provider() {
        return ProviderId.DYNAMO;
    }

    @Test
    void dynamoLikeNotSupported() throws Exception {
        try (var client = com.hyperscaledb.conformance.ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            assertFalse(client.capabilities().isSupported(Capability.LIKE_OPERATOR),
                    "DynamoDB must NOT support LIKE_OPERATOR");
        }
    }

    @Test
    void dynamoOrderByNotSupported() throws Exception {
        try (var client = com.hyperscaledb.conformance.ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            assertFalse(client.capabilities().isSupported(Capability.ORDER_BY),
                    "DynamoDB must NOT support ORDER_BY");
        }
    }

    @Test
    void dynamoEndsWithNotSupported() throws Exception {
        try (var client = com.hyperscaledb.conformance.ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            assertFalse(client.capabilities().isSupported(Capability.ENDS_WITH),
                    "DynamoDB must NOT support ENDS_WITH");
        }
    }

    @Test
    void dynamoRegexNotSupported() throws Exception {
        try (var client = com.hyperscaledb.conformance.ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            assertFalse(client.capabilities().isSupported(Capability.REGEX_MATCH),
                    "DynamoDB must NOT support REGEX_MATCH");
        }
    }

    @Test
    void dynamoCrosPartitionQueryNotSupported() throws Exception {
        try (var client = com.hyperscaledb.conformance.ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            assertFalse(client.capabilities().isSupported(Capability.CROSS_PARTITION_QUERY),
                    "DynamoDB must NOT support CROSS_PARTITION_QUERY");
        }
    }
}
