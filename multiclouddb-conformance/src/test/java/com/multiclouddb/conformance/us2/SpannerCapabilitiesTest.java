// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spanner capability conformance test — verifies all capabilities are
 * supported.
 */
@Tag("spanner")
@Tag("emulator")
public class SpannerCapabilitiesTest extends CapabilitiesConformanceTest {
    @Override
    protected ProviderId provider() {
        return ProviderId.SPANNER;
    }

    @Test
    void spannerSupportsAllQueryDsl() throws Exception {
        try (var client = com.multiclouddb.conformance.ConformanceHarness.createClient(ProviderId.SPANNER)) {
            var caps = client.capabilities();
            assertTrue(caps.isSupported(Capability.LIKE_OPERATOR));
            assertTrue(caps.isSupported(Capability.ORDER_BY));
            assertTrue(caps.isSupported(Capability.ENDS_WITH));
            assertTrue(caps.isSupported(Capability.REGEX_MATCH));
            assertTrue(caps.isSupported(Capability.CASE_FUNCTIONS));
        }
    }
    @Test
    void spannerExtendedChangeFeedHistorySupported() throws Exception {
        try (var client = com.multiclouddb.conformance.ConformanceHarness.createClient(ProviderId.SPANNER)) {
            assertTrue(client.capabilities().isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY),
                    "Spanner must support EXTENDED_CHANGE_FEED_HISTORY — declared via CREATE CHANGE STREAM ... OPTIONS(retention_period)");
        }
    }
}