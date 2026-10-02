// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.*;
import com.multiclouddb.conformance.ConformanceHarness;
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
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertNotNull(caps, "capabilities() must not return null");
            assertFalse(caps.all().isEmpty(), "capabilities() must not be empty");
        }
    }

    @Test
    void allKnownCapabilityNamesPresent() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            String[] knownNames = {
                    Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.TRANSACTIONS,
                    Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY,
                    Capability.CHANGE_FEED,
                    Capability.EXTENDED_CHANGE_FEED_HISTORY,
                    Capability.PORTABLE_QUERY_EXPRESSION,
                    Capability.ORDER_BY,
                    Capability.CROSS_PARTITION_QUERY,
                    Capability.NATIVE_SQL_QUERY,
                    Capability.RESULT_LIMIT,
                    Capability.LIKE_OPERATOR,
                    Capability.ENDS_WITH,
                    Capability.REGEX_MATCH,
                    Capability.CASE_FUNCTIONS,
                    Capability.ROW_LEVEL_TTL,
                    Capability.WRITE_TIMESTAMP
            };
            for (String name : knownNames) {
                assertNotNull(caps.get(name),
                        "Provider " + provider().id() + " must declare capability: " + name);
            }
            assertEquals(knownNames.length, caps.all().size(),
                    "Providers must declare the baseline and optional extensions, including unsupported ones");
        }
    }

    @Test
    void commonBaselineIsSupported() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            String[] baseline = {
                    Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.TRANSACTIONS,
                    Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY,
                    Capability.CHANGE_FEED,
                    Capability.PORTABLE_QUERY_EXPRESSION
            };
            for (String name : baseline) {
                assertTrue(caps.isSupported(name),
                        "Provider " + provider().id() + " must support baseline capability: " + name);
            }
        }
    }

    @Test
    void portableQueryExpressionIsSupported() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.PORTABLE_QUERY_EXPRESSION),
                    "All providers must support PORTABLE_QUERY_EXPRESSION");
        }
    }

    @Test
    void continuationTokenPagingIsSupported() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.CONTINUATION_TOKEN_PAGING),
                    "All providers must support CONTINUATION_TOKEN_PAGING");
        }
    }

    @Test
    void providerIdMatchesConfig() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertEquals(provider(), client.providerId());
        }
    }
}
