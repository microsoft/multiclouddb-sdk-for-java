// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.changefeed.ChangeFeedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the build-time capability gate in
 * {@link MulticloudDbClientFactory} via the test-only
 * {@link FakeCapabilityGateAdapter} (registered through the API module's
 * {@code META-INF/services}). The gate runs after {@code adapter.createClient(...)}
 * but before any change-feed-substrate I/O is issued, so this test does not
 * require any provider or emulator — it asserts pure capability-routing
 * behaviour.
 *
 * <p>This is the abstract conformance-level assertion that pairs with the
 * concrete per-provider checks in
 * {@code multiclouddb-conformance/.../us2/{Cosmos,Dynamo,Spanner}CapabilitiesTest}.
 */
class MulticloudDbClientFactoryExtendedRetentionGateTest {

    @BeforeEach
    void resetFake() {
        FakeCapabilityGateAdapter.supportsExtended = false;
        FakeCapabilityGateAdapter.closeCount.set(0);
    }

    @AfterEach
    void clearFake() {
        FakeCapabilityGateAdapter.supportsExtended = false;
    }

    private MulticloudDbClientConfig configWithOptIn(Duration retention) {
        return MulticloudDbClientConfig.builder()
                .provider(FakeCapabilityGateAdapter.FAKE_PROVIDER)
                .changeFeed(ChangeFeedConfig.builder()
                        .extendedRetention(retention)
                        .build())
                .build();
    }

    @Test
    void gateThrowsWhenProviderDoesNotDeclareCapability() {
        FakeCapabilityGateAdapter.supportsExtended = false;

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> MulticloudDbClientFactory.create(configWithOptIn(Duration.ofDays(7))));

        MulticloudDbError err = ex.error();
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, err.category(),
                "gate must classify failure as UNSUPPORTED_CAPABILITY");
        assertEquals("create", err.operation(), "gate must surface under operation=create");
        assertFalse(err.retryable(), "config errors are not retryable");
        assertNotNull(err.providerDetails(), "gate must attach structured details");
        assertEquals("extended_retention_unavailable", err.providerDetails().get("reason"),
                "details.reason must be the canonical fingerprint");
        assertEquals(Capability.EXTENDED_CHANGE_FEED_HISTORY, err.providerDetails().get("capability"));
        assertEquals(Duration.ofDays(7).toString(), err.providerDetails().get("requestedRetention"));
        assertTrue(err.message().contains("EXTENDED_CHANGE_FEED_HISTORY"),
                "message must name the capability for grep-ability");
    }

    @Test
    void gateClosesProviderClientWhenItThrows() {
        FakeCapabilityGateAdapter.supportsExtended = false;

        assertThrows(MulticloudDbException.class,
                () -> MulticloudDbClientFactory.create(configWithOptIn(Duration.ofDays(7))));

        assertEquals(1, FakeCapabilityGateAdapter.closeCount.get(),
                "providerClient.close() must be invoked on gate-throw to avoid leaking "
                        + "control-plane gRPC channels / HTTP pools / worker threads paid for "
                        + "by adapter.createClient(...)");
    }

    @Test
    void gateIsBypassedWhenNoOptIn() throws Exception {
        FakeCapabilityGateAdapter.supportsExtended = false;
        MulticloudDbClientConfig cfg = MulticloudDbClientConfig.builder()
                .provider(FakeCapabilityGateAdapter.FAKE_PROVIDER)
                .build();

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(cfg)) {
            assertNotNull(client, "no opt-in → factory must succeed regardless of capability");
        }
    }

    @Test
    void gateAllowsConstructionWhenProviderSupportsCapability() throws Exception {
        FakeCapabilityGateAdapter.supportsExtended = true;

        try (MulticloudDbClient client =
                     MulticloudDbClientFactory.create(configWithOptIn(Duration.ofDays(7)))) {
            assertNotNull(client, "supported capability + valid opt-in → factory must succeed");
        }
        assertEquals(1, FakeCapabilityGateAdapter.closeCount.get(),
                "client.close() must reach the underlying provider client");
    }
}