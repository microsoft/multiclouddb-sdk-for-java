package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

/**
 * Cosmos DB portability warning conformance test.
 */
@Tag("cosmos")
@Tag("emulator")
public class CosmosPortabilityWarningTest extends PortabilityWarningConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    protected String warningFeatureFlagKey() {
        return "cosmos.sessionConsistency";
    }
}
