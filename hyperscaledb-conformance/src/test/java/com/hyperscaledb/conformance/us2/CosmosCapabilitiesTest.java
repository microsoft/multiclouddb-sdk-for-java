// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

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
}
