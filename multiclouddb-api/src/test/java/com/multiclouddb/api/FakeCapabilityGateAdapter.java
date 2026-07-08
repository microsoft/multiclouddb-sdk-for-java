// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;
import com.multiclouddb.spi.MulticloudDbProviderClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link MulticloudDbProviderAdapter} registered through the API
 * module's {@code META-INF/services} so that
 * {@code MulticloudDbClientFactoryExtendedRetentionGateTest} can exercise the
 * build-time capability gate without any provider module or emulator.
 *
 * <p>The adapter's {@link MulticloudDbProviderClient} returns a
 * {@link CapabilitySet} whose declaration for
 * {@link Capability#EXTENDED_CHANGE_FEED_HISTORY} is toggled by the static
 * {@link #supportsExtended} field — tests flip the toggle to exercise the
 * gate's supported / unsupported branches.
 *
 * <p>{@link FakeProviderClient#close()} increments {@link #closeCount} so
 * tests can assert the factory releases provider resources on gate-throw.
 */
public final class FakeCapabilityGateAdapter implements MulticloudDbProviderAdapter {

    /** Synthetic provider id used by the fake. Not registered with any real adapter. */
    public static final ProviderId FAKE_PROVIDER =
            ProviderId.fromId("fake-capability-gate");

    /** Toggle: declare EXTENDED_CHANGE_FEED_HISTORY as supported (true) or unsupported (false). */
    public static volatile boolean supportsExtended = false;

    /** Number of times the latest fake provider client has been closed. */
    public static final AtomicInteger closeCount = new AtomicInteger();

    @Override public ProviderId providerId() { return FAKE_PROVIDER; }

    @Override
    public MulticloudDbProviderClient createClient(MulticloudDbClientConfig config) {
        return new FakeProviderClient(supportsExtended);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return null;
    }

    static final class FakeProviderClient implements MulticloudDbProviderClient {
        private final CapabilitySet capabilities;

        FakeProviderClient(boolean supportsExtended) {
            Capability cap = supportsExtended
                    ? Capability.EXTENDED_CHANGE_FEED_HISTORY_CAP
                    : Capability.EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED;
            this.capabilities = new CapabilitySet(List.of(cap));
        }

        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { return null; }
        @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) {}
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
        @Override public CapabilitySet capabilities() { return capabilities; }
        @Override public ProviderId providerId() { return FAKE_PROVIDER; }
        @Override public void close() { closeCount.incrementAndGet(); }
    }
}