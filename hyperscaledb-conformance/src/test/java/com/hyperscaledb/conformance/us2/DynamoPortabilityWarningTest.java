package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

/**
 * DynamoDB portability warning conformance test.
 */
@Tag("dynamo")
@Tag("emulator")
public class DynamoPortabilityWarningTest extends PortabilityWarningConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.DYNAMO;
    }

    @Override
    protected String warningFeatureFlagKey() {
        return "dynamo.strongConsistentReads";
    }
}
