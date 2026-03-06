package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;

/**
 * Cosmos DB capability conformance test.
 */
public class CosmosCapabilitiesTest extends CapabilitiesConformanceTest {
    @Override
    protected ProviderId provider() {
        return ProviderId.COSMOS;
    }
}
