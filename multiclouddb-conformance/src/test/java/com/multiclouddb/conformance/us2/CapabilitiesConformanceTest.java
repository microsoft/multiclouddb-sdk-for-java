// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.*;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract conformance test that verifies capability discovery across
 * providers.
 * Subclasses specify the provider; tests verify the expected capability set.
 */
public abstract class CapabilitiesConformanceTest {

    private static final Map<ProviderId, Boolean> EXTENDED_PARTIAL_UPDATE_SUPPORT = Map.of(
            ProviderId.COSMOS, false,
            ProviderId.DYNAMO, false);
    private static final Map<ProviderId, Boolean> CASE_SENSITIVE_PARTIAL_UPDATE_SUPPORT = Map.of(
            ProviderId.COSMOS, true,
            ProviderId.DYNAMO, true);
    private static final Map<ProviderId, String> CASE_SENSITIVE_PARTIAL_UPDATE_NOTE = Map.of(
            ProviderId.COSMOS, "JSON property",
            ProviderId.DYNAMO, "Attribute names");
    private static final Map<ProviderId, String> EXTENDED_PARTIAL_UPDATE_NOTE = Map.of(
            ProviderId.COSMOS, "100 patch operations",
            ProviderId.DYNAMO, "update expression");

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
    void knownCapabilityNamesMatchReleaseScope() throws Exception {
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
                    Capability.PARTIAL_UPDATE,
                    Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD,
                    Capability.PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS
            };
            for (String name : knownNames) {
                boolean partialUpdateCapability = name.equals(Capability.PARTIAL_UPDATE)
                        || name.equals(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD)
                        || name.equals(Capability.PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS);
                if (!caps.isSupported(Capability.PARTIAL_UPDATE) && partialUpdateCapability) {
                    assertNull(caps.get(name),
                            "A non-participating provider must not advertise feature 002 capability: " + name);
                } else {
                    assertNotNull(caps.get(name),
                            "Provider " + provider().id() + " must declare capability: " + name);
                }
            }
        }
    }

    @Test
    void capabilityCountMatchesReleaseScope() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            int expected = caps.isSupported(Capability.PARTIAL_UPDATE) ? 20 : 17;
            assertEquals(expected, caps.all().size(),
                    "Provider " + provider().id() + " should declare exactly " + expected + " capabilities");
        }
    }

    @Test
    void partialUpdateCapabilitiesMatchProviderEnvelopes() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            if (!caps.isSupported(Capability.PARTIAL_UPDATE)) {
                assertFalse(caps.isSupported(Capability.PARTIAL_UPDATE));
                assertNull(caps.get(Capability.PARTIAL_UPDATE));
                assertNull(caps.get(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD));
                assertNull(caps.get(Capability.PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS));
                return;
            }

            assertTrue(caps.isSupported(Capability.PARTIAL_UPDATE),
                    "Feature 002 providers must support PARTIAL_UPDATE");

            Capability extended = caps.get(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
            assertNotNull(extended);
            assertEquals(EXTENDED_PARTIAL_UPDATE_SUPPORT.get(provider()).booleanValue(),
                    extended.supported(),
                    "Unexpected extended partial-update declaration for " + provider().id());
            assertNotNull(extended.notes());
            assertTrue(extended.notes().contains(EXTENDED_PARTIAL_UPDATE_NOTE.get(provider())),
                    "Extended partial-update notes must describe the provider envelope");

            Capability caseSensitive = caps.get(Capability.PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS);
            assertNotNull(caseSensitive);
            assertEquals(CASE_SENSITIVE_PARTIAL_UPDATE_SUPPORT.get(provider()).booleanValue(),
                    caseSensitive.supported(),
                    "Unexpected case-sensitive partial-update declaration for " + provider().id());
            assertNotNull(caseSensitive.notes());
            assertTrue(caseSensitive.notes().contains(CASE_SENSITIVE_PARTIAL_UPDATE_NOTE.get(provider())),
                    "Case-sensitive partial-update notes must describe field identity");
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
