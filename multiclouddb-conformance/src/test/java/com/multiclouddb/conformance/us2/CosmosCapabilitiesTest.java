// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cosmos DB capability conformance test.
 */
@Tag("cosmos")
@Tag("emulator")
public class CosmosCapabilitiesTest extends CapabilitiesConformanceTest {
    @Override
    protected ProviderId provider() {
        return ProviderId.COSMOS;
    }

    @Test
    void cosmosExtendedChangeFeedHistorySupported() throws Exception {
        try (var client = com.multiclouddb.conformance.ConformanceHarness.createClient(ProviderId.COSMOS)) {
            assertTrue(client.capabilities().isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY),
                    "Cosmos must support EXTENDED_CHANGE_FEED_HISTORY — declared via AVAD ChangeFeedPolicy");
        }
    }
}