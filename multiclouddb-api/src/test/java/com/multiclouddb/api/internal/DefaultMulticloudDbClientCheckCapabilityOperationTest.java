// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lock the round-6 fix: {@link DefaultMulticloudDbClient}'s
 * {@code checkCapability} must surface the caller's actual operation name
 * through {@code MulticloudDbError.operation()} — not the previously-hardcoded
 * {@code "query"}. Diagnostics consumers branch on
 * {@code e.error().operation()} to attribute capability-gate failures to the
 * right entry point.
 *
 * <p>Without the fix, {@code listCursors} / {@code readChanges} on a provider
 * that doesn't declare {@code Capability.CHANGE_FEED} surface
 * {@code error().operation() == "query"} — contradicting
 * {@code OperationNames.LIST_CURSORS} / {@code OperationNames.READ_CHANGES}
 * and breaking any per-operation observability grouping.
 */
class DefaultMulticloudDbClientCheckCapabilityOperationTest {

    /**
     * Bare-minimum fake provider client whose CapabilitySet declares only the
     * basic CRUD capability — every other capability check fails. Other SPI
     * methods throw if invoked because we never reach them; the capability
     * gate fires first.
     */
    private static final class FakeProviderClient implements MulticloudDbProviderClient {
        private final ProviderId pid = ProviderId.fromId("fake-checkcap-op-test");
        private final CapabilitySet caps = new CapabilitySet(List.of(Capability.NATIVE_SQL_QUERY_CAP));

        @Override public ProviderId providerId() { return pid; }
        @Override public CapabilitySet capabilities() { return caps; }

        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }

    private static MulticloudDbClient newClient() {
        FakeProviderClient pc = new FakeProviderClient();
        MulticloudDbClientConfig cfg = MulticloudDbClientConfig.builder()
                .provider(pc.providerId())
                .build();
        return new DefaultMulticloudDbClient(pc, cfg);
    }

    @Test
    @DisplayName("listCursors → UNSUPPORTED_CAPABILITY.error().operation() == LIST_CURSORS (was hardcoded 'query')")
    void listCursors_operationIsListCursors() {
        MulticloudDbClient client = newClient();
        MulticloudDbException me = assertThrows(MulticloudDbException.class,
                () -> client.listCursors(new ResourceAddress("db", "coll")));
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, me.error().category());
        assertEquals(OperationNames.LIST_CURSORS, me.error().operation(),
                "Round-6 fix: capability-gate failure on listCursors MUST report "
                        + "operation=" + OperationNames.LIST_CURSORS
                        + ", not the previously-hardcoded 'query'. "
                        + "Diagnostics consumers branch on this.");
        assertNotEquals("query", me.error().operation(),
                "regression check: the hard-coded 'query' must NOT come back");
    }

    @Test
    @DisplayName("readChanges → UNSUPPORTED_CAPABILITY.error().operation() == READ_CHANGES (was hardcoded 'query')")
    void readChanges_operationIsReadChanges() {
        MulticloudDbClient client = newClient();
        MulticloudDbException me = assertThrows(MulticloudDbException.class,
                () -> client.readChanges(new ResourceAddress("db", "coll"), ChangeFeedCursor.now()));
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, me.error().category());
        assertEquals(OperationNames.READ_CHANGES, me.error().operation(),
                "Round-6 fix: capability-gate failure on readChanges MUST report "
                        + "operation=" + OperationNames.READ_CHANGES);
        assertNotEquals("query", me.error().operation());
    }
}
