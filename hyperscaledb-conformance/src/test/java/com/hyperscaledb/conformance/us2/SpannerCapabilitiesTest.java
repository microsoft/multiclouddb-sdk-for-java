// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.ProviderId;
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
        try (var client = com.hyperscaledb.conformance.ConformanceHarness.createClient(ProviderId.SPANNER)) {
            var caps = client.capabilities();
            assertTrue(caps.isSupported(Capability.LIKE_OPERATOR));
            assertTrue(caps.isSupported(Capability.ORDER_BY));
            assertTrue(caps.isSupported(Capability.ENDS_WITH));
            assertTrue(caps.isSupported(Capability.REGEX_MATCH));
            assertTrue(caps.isSupported(Capability.CASE_FUNCTIONS));
        }
    }
}
