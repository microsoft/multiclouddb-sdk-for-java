// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.*;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract conformance test that verifies capability discovery across the built-in
 * providers. Subclasses specify the provider; tests verify each effective set after
 * capability-specific API defaults are applied.
 */
public abstract class CapabilitiesConformanceTest {

    protected abstract ProviderId provider();

    protected abstract boolean partialUpdateSupported();

    @Test
    void capabilitiesReturnsNonEmptySet() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertNotNull(caps, "capabilities() must not return null");
            assertFalse(caps.all().isEmpty(), "capabilities() must not be empty");
        }
    }

    @Test
    void builtInCapabilityNamesMatchReleaseScope() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();

            String[] knownNames = {
                    Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.CROSS_PARTITION_QUERY,
                    Capability.TRANSACTIONS,
                    Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY,
                    Capability.NATIVE_SQL_QUERY,
                    Capability.CHANGE_FEED,
                    Capability.EXTENDED_CHANGE_FEED_HISTORY,
                    Capability.PORTABLE_QUERY_EXPRESSION,
                    Capability.LIKE_OPERATOR,
                    Capability.ORDER_BY,
                    Capability.ENDS_WITH,
                    Capability.REGEX_MATCH,
                    Capability.CASE_FUNCTIONS,
                    Capability.RESULT_LIMIT,
                    Capability.ROW_LEVEL_TTL,
                    Capability.WRITE_TIMESTAMP,
                    Capability.PARTIAL_UPDATE
            };
            for (String name : knownNames) {
                assertNotNull(caps.get(name),
                        "Effective set for built-in provider " + provider().id()
                                + " must contain: " + name);
            }
        }
    }

    @Test
    void builtInCapabilityCountMatchesReleaseScope() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            int expected = 18;
            assertEquals(expected, caps.all().size(),
                    "Built-in provider " + provider().id() + " should expose exactly "
                            + expected + " effective capabilities");
        }
    }

    @Test
    void partialUpdateCapabilityMatchesReleaseScope() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            Capability partialUpdate = caps.get(Capability.PARTIAL_UPDATE);
            assertNotNull(partialUpdate);
            assertEquals(partialUpdateSupported(), partialUpdate.supported(),
                    "Unexpected effective PARTIAL_UPDATE value for " + provider().id());
            assertNotNull(partialUpdate.notes());
            assertFalse(partialUpdate.notes().isBlank(),
                    "PARTIAL_UPDATE notes must describe support or the unsupported boundary");
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
