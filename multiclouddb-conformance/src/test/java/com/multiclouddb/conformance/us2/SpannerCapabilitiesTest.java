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
    void spannerSupportsAllLcdCapabilities() throws Exception {
        try (var client = com.multiclouddb.conformance.ConformanceHarness.createClient(ProviderId.SPANNER)) {
            var caps = client.capabilities();
            assertTrue(caps.isSupported(Capability.CONTINUATION_TOKEN_PAGING));
            assertTrue(caps.isSupported(Capability.TRANSACTIONS));
            assertTrue(caps.isSupported(Capability.BATCH_OPERATIONS));
            assertTrue(caps.isSupported(Capability.STRONG_CONSISTENCY));
            assertTrue(caps.isSupported(Capability.CHANGE_FEED));
            assertTrue(caps.isSupported(Capability.PORTABLE_QUERY_EXPRESSION));
            assertTrue(caps.isSupported(Capability.ORDER_BY));
        }
    }
}
