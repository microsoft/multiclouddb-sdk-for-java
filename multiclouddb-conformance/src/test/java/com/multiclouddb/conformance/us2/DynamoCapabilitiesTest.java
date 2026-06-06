// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamoDB capability conformance test — under the strict-LCD contract,
 * DynamoDB must support every published capability.
 */
@Tag("dynamo")
@Tag("emulator")
public class DynamoCapabilitiesTest extends CapabilitiesConformanceTest {
    @Override
    protected ProviderId provider() {
        return ProviderId.DYNAMO;
    }

    @Test
    void dynamoSupportsAllLcdCapabilities() throws Exception {
        try (var client = com.multiclouddb.conformance.ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            String[] lcd = {
                    Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.TRANSACTIONS,
                    Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY,
                    Capability.CHANGE_FEED,
                    Capability.PORTABLE_QUERY_EXPRESSION,
                    Capability.ORDER_BY
            };
            for (String c : lcd) {
                assertTrue(client.capabilities().isSupported(c),
                        "DynamoDB must support strict-LCD capability: " + c);
            }
        }
    }
}
