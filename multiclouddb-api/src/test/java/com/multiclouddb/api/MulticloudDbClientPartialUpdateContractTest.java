// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.internal.DefaultMulticloudDbClient;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Public-surface contract for {@code MulticloudDbClient.update(...)}: both overloads retain
 * {@code Map<String,Object>}, the three-argument overload supplies
 * {@link OperationOptions#defaults()}, and a non-null update TTL is rejected with
 * INVALID_REQUEST.
 */
class MulticloudDbClientPartialUpdateContractTest {

    @Test
    @DisplayName("both update() overloads exist and keep Map<String,Object> payloads")
    void overloadsKeepMapPayload() throws Exception {
        Method four = MulticloudDbClient.class.getMethod("update",
                ResourceAddress.class, MulticloudDbKey.class, Map.class, OperationOptions.class);
        Method three = MulticloudDbClient.class.getMethod("update",
                ResourceAddress.class, MulticloudDbKey.class, Map.class);
        assertNotNull(four);
        assertNotNull(three);
        assertEquals(void.class, four.getReturnType());
        assertEquals(void.class, three.getReturnType());
        assertMapStringObject(four.getGenericParameterTypes()[2]);
        assertMapStringObject(three.getGenericParameterTypes()[2]);
    }

    private static void assertMapStringObject(Type t) {
        assertEquals(true, t instanceof ParameterizedType, "payload must remain a parameterized Map");
        ParameterizedType pt = (ParameterizedType) t;
        assertEquals(Map.class, pt.getRawType());
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
        assertEquals(Object.class, pt.getActualTypeArguments()[1]);
    }

    @Test
    @DisplayName("three-argument update() forwards OperationOptions.defaults()")
    void threeArgSuppliesDefaults() {
        AtomicReference<OperationOptions> captured = new AtomicReference<>();
        AtomicReference<Map<String, Object>> capturedFields = new AtomicReference<>();
        MulticloudDbClient fake = new CapturingClient(captured, capturedFields);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "SHIPPED");
        fake.update(new ResourceAddress("db", "coll"), MulticloudDbKey.of("p"), fields);

        assertSame(OperationOptions.defaults(), captured.get(),
                "three-arg overload must forward OperationOptions.defaults()");
        assertSame(fields, capturedFields.get(), "three-arg overload must forward the same fields map");
    }

    @Test
    @DisplayName("update() with a non-null ttlSeconds is rejected as INVALID_REQUEST before delegation")
    void updateTtlRejected() {
        MulticloudDbProviderClient provider = new NoopProvider(
                new CapabilitySet(List.of(Capability.PARTIAL_UPDATE_CAP)));
        MulticloudDbClientConfig cfg = MulticloudDbClientConfig.builder()
                .provider(provider.providerId()).build();
        MulticloudDbClient client = new DefaultMulticloudDbClient(provider, cfg);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "SHIPPED");
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.update(new ResourceAddress("db", "coll"), MulticloudDbKey.of("p"), fields,
                        OperationOptions.builder().ttlSeconds(3600).build()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
    }

    /** Interface fixture capturing the arguments the 3-arg default overload forwards. */
    private static final class CapturingClient implements MulticloudDbClient {
        private final AtomicReference<OperationOptions> opts;
        private final AtomicReference<Map<String, Object>> fields;
        CapturingClient(AtomicReference<OperationOptions> opts, AtomicReference<Map<String, Object>> fields) {
            this.opts = opts; this.fields = fields;
        }
        @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> f, OperationOptions o) {
            fields.set(f); opts.set(o);
        }
        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { return null; }
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) {}
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
        @Override public CapabilitySet capabilities() { return new CapabilitySet(List.of()); }
        @Override public void ensureDatabase(String database) {}
        @Override public void ensureContainer(ResourceAddress address) {}
        @Override public void provisionSchema(Map<String, List<String>> schema) {}
        @Override public List<com.multiclouddb.api.changefeed.ChangeFeedCursor> listCursors(ResourceAddress address) { return List.of(); }
        @Override public com.multiclouddb.api.changefeed.ChangeFeedPage readChanges(ResourceAddress address, com.multiclouddb.api.changefeed.ChangeFeedCursor cursor) { return null; }
        @Override public com.multiclouddb.api.changefeed.ChangeFeedPage readChanges(ResourceAddress address, com.multiclouddb.api.changefeed.ChangeFeedCursor cursor, OperationOptions options) { return null; }
        @Override public ProviderId providerId() { return ProviderId.fromId("capturing"); }
        @Override public void close() {}
    }

    /** Minimal provider client that never performs I/O; used to reach the shared TTL rejection. */
    private static final class NoopProvider implements MulticloudDbProviderClient {
        private final ProviderId pid = ProviderId.fromId("noop-contract-test");
        private final CapabilitySet caps;
        NoopProvider(CapabilitySet caps) { this.caps = caps; }
        @Override public ProviderId providerId() { return pid; }
        @Override public CapabilitySet capabilities() { return caps; }
        @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> f, OperationOptions o) {}
        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { return null; }
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) {}
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
        @Override public void close() {}
    }
}
