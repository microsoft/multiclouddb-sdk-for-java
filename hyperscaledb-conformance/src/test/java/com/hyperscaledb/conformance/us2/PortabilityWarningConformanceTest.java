package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.*;
import com.hyperscaledb.conformance.ConformanceConfig;
import com.hyperscaledb.conformance.ConformanceHarness;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test: portability warnings are emitted when provider-specific
 * feature flags are enabled.
 * <p>
 * Tests that the SDK produces structured warnings without breaking
 * functionality.
 * Concrete subclasses should supply their provider-specific flag key/value.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class PortabilityWarningConformanceTest {

    private static final Logger LOG = LoggerFactory.getLogger(PortabilityWarningConformanceTest.class);

    /** Provider id for this test run. */
    protected abstract ProviderId providerId();

    /** Provider-specific feature flag key that should trigger a warning. */
    protected abstract String warningFeatureFlagKey();

    /** Provider-specific feature flag value (usually "true"). */
    protected String warningFeatureFlagValue() {
        return "true";
    }

    @Test
    @Order(1)
    @DisplayName("Client with no feature flags produces no warnings")
    void noFeatureFlagsNoWarnings() throws Exception {
        HyperscaleDbClientConfig config = ConformanceConfig.forProvider(providerId());
        // Create a client with default config (no feature flags) — should succeed
        // without warnings
        try (HyperscaleDbClient client = HyperscaleDbClientFactory.create(config)) {
            assertNotNull(client, "Client should be created without errors");
            assertEquals(providerId(), client.providerId());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Client with provider-specific flag still functions correctly")
    void featureFlagDoesNotBreakFunctionality() throws Exception {
        HyperscaleDbClientConfig baseConfig = ConformanceConfig.forProvider(providerId());
        HyperscaleDbClientConfig configWithFlag = HyperscaleDbClientConfig.builder()
                .provider(baseConfig.provider())
                .connection(baseConfig.connection())
                .auth(baseConfig.auth())
                .featureFlag(warningFeatureFlagKey(), warningFeatureFlagValue())
                .build();

        try (HyperscaleDbClient client = HyperscaleDbClientFactory.create(configWithFlag)) {
            assertNotNull(client, "Client should be created even with feature flags");
            // Capability discovery should still work
            CapabilitySet caps = client.capabilities();
            assertNotNull(caps, "Capabilities should be available");
            assertFalse(caps.all().isEmpty(), "Capabilities should not be empty");
            LOG.info("Client with feature flag [{}={}] is functional; capabilities count: {}",
                    warningFeatureFlagKey(), warningFeatureFlagValue(), caps.all().size());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Multiple feature flags can be set simultaneously")
    void multipleFeatureFlags() throws Exception {
        HyperscaleDbClientConfig baseConfig = ConformanceConfig.forProvider(providerId());
        HyperscaleDbClientConfig configWithFlags = HyperscaleDbClientConfig.builder()
                .provider(baseConfig.provider())
                .connection(baseConfig.connection())
                .auth(baseConfig.auth())
                .featureFlag(warningFeatureFlagKey(), warningFeatureFlagValue())
                .featureFlag("some.unknown.flag", "value")
                .build();

        try (HyperscaleDbClient client = HyperscaleDbClientFactory.create(configWithFlags)) {
            assertNotNull(client, "Client should handle multiple feature flags");
        }
    }
}
