package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.*;
import com.hyperscaledb.conformance.ConformanceConfig;
import com.hyperscaledb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract conformance test that verifies capability discovery across
 * providers.
 * Subclasses specify the provider; tests verify the expected capability set.
 */
public abstract class CapabilitiesConformanceTest {

    protected abstract ProviderId provider();

    @Test
    void capabilitiesReturnsNonEmptySet() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertNotNull(caps, "capabilities() must not return null");
            assertFalse(caps.all().isEmpty(), "capabilities() must not be empty");
        }
    }

    @Test
    void allKnownCapabilityNamesPresent() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            // All 13 well-known capability names must be declared
            String[] knownNames = {
                    Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.CROSS_PARTITION_QUERY,
                    Capability.TRANSACTIONS,
                    Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY,
                    Capability.NATIVE_SQL_QUERY,
                    Capability.CHANGE_FEED,
                    Capability.PORTABLE_QUERY_EXPRESSION,
                    Capability.LIKE_OPERATOR,
                    Capability.ORDER_BY,
                    Capability.ENDS_WITH,
                    Capability.REGEX_MATCH,
                    Capability.CASE_FUNCTIONS
            };
            for (String name : knownNames) {
                assertNotNull(caps.get(name),
                        "Provider " + provider().id() + " must declare capability: " + name);
            }
        }
    }

    @Test
    void capabilityCountIs13() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertEquals(13, caps.all().size(),
                    "Provider " + provider().id() + " should declare exactly 13 capabilities");
        }
    }

    @Test
    void portableQueryExpressionIsSupported() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.PORTABLE_QUERY_EXPRESSION),
                    "All providers must support PORTABLE_QUERY_EXPRESSION");
        }
    }

    @Test
    void continuationTokenPagingIsSupported() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.CONTINUATION_TOKEN_PAGING),
                    "All providers must support CONTINUATION_TOKEN_PAGING");
        }
    }

    @Test
    void providerIdMatchesConfig() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(provider())) {
            assertEquals(provider(), client.providerId());
        }
    }
}
