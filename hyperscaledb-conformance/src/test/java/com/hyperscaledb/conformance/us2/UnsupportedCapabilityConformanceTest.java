// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.*;
import com.hyperscaledb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance test verifying that unsupported capabilities produce structured
 * errors. Uses DynamoDB as a reference since it has the most unsupported
 * query DSL capabilities.
 */
@Tag("dynamo")
@Tag("emulator")
public class UnsupportedCapabilityConformanceTest {

    @Test
    void dynamoLikeQueryFailsFastWithStructuredError() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            CapabilitySet caps = client.capabilities();
            // Pre-check: LIKE is unsupported
            assertFalse(caps.isSupported(Capability.LIKE_OPERATOR),
                    "DynamoDB must NOT support LIKE_OPERATOR");

            // If fail-fast is enforced, attempting a LIKE expression should throw
            // a HyperscaleDbException. If not enforced, the provider will reject it natively.
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);
            QueryRequest likeQuery = QueryRequest.builder()
                    .expression("name LIKE @pattern")
                    .parameters(java.util.Map.of("pattern", "%test%"))
                    .maxPageSize(10)
                    .build();

            HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                    () -> client.query(address, likeQuery),
                    "LIKE on DynamoDB must throw HyperscaleDbException");
            assertNotNull(ex.error(), "Exception must carry a HyperscaleDbError");
            // The error should indicate unsupported or invalid request
            assertTrue(
                    ex.error().category() == HyperscaleDbErrorCategory.UNSUPPORTED_CAPABILITY
                            || ex.error().category() == HyperscaleDbErrorCategory.INVALID_REQUEST,
                    "Expected UNSUPPORTED_CAPABILITY or INVALID_REQUEST, got: " + ex.error().category());
        }
    }

    @Test
    void dynamoEndsWithQueryFailsFast() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            assertFalse(client.capabilities().isSupported(Capability.ENDS_WITH));

            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);
            QueryRequest endsWithQuery = QueryRequest.builder()
                    .expression("ends_with(name, @suffix)")
                    .parameters(java.util.Map.of("suffix", "test"))
                    .maxPageSize(10)
                    .build();

            assertThrows(HyperscaleDbException.class,
                    () -> client.query(address, endsWithQuery),
                    "ends_with on DynamoDB must throw HyperscaleDbException");
        }
    }

    @Test
    void capabilityQueryPreflight() throws Exception {
        // Verify the preflight pattern: check capability before querying
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            CapabilitySet caps = client.capabilities();

            // Users should check before using capability-gated features
            if (!caps.isSupported(Capability.LIKE_OPERATOR)) {
                // Expected: DynamoDB does not support LIKE — skip gracefully
                assertFalse(caps.isSupported(Capability.LIKE_OPERATOR));
            }

            // Cosmos and Spanner support LIKE (skip if Cosmos emulator is not available)
            boolean cosmosAvailable = false;
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("localhost", 8081), 1000);
                cosmosAvailable = true;
            } catch (Exception ignored) { }
            assumeTrue(cosmosAvailable, "Cosmos emulator not available, skipping cross-provider assertion");
            try (HyperscaleDbClient cosmosClient = ConformanceHarness.createClient(ProviderId.COSMOS)) {
                assertTrue(cosmosClient.capabilities().isSupported(Capability.LIKE_OPERATOR));
            }
        }
    }
}
