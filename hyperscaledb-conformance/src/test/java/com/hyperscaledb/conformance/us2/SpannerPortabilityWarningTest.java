package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

/**
 * Spanner portability warning conformance test.
 */
@Tag("spanner")
@Tag("emulator")
public class SpannerPortabilityWarningTest extends PortabilityWarningConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    protected String warningFeatureFlagKey() {
        return "spanner.readOnlyTransactions";
    }
}
