// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.Struct;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link SpannerChangeFeedReader}'s private {@code extractKey}
 * method, exercised via reflection. Locks the round-6 portability fix that
 * makes Spanner throw on malformed envelopes instead of silently minting
 * {@code MulticloudDbKey.of("")} — matching Cosmos and Dynamo.
 *
 * <p>The wire-format {@code reason} token must match Dynamo's
 * ({@code "missing_partition_key"} / {@code "malformed_envelope"}) so
 * application-level retry / dead-letter logic that branches on
 * {@code providerDetails.reason} stays portable across providers.
 */
class SpannerChangeFeedReaderExtractKeyTest {

    private static final SpannerChangeFeedReader READER =
            new SpannerChangeFeedReader(ProviderId.SPANNER, null, java.util.Map.of());

    private static MulticloudDbException invokeExpectThrow(Struct mod) {
        try {
            Method m = SpannerChangeFeedReader.class.getDeclaredMethod("extractKey", Struct.class);
            m.setAccessible(true);
            m.invoke(READER, mod);
            throw new AssertionError("extractKey did not throw");
        } catch (InvocationTargetException ite) {
            Throwable c = ite.getCause();
            if (c instanceof MulticloudDbException me) return me;
            throw new AssertionError("Unexpected throwable: " + c, c);
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError(roe);
        }
    }

    private static MulticloudDbKey invokeExpectKey(Struct mod) {
        try {
            Method m = SpannerChangeFeedReader.class.getDeclaredMethod("extractKey", Struct.class);
            m.setAccessible(true);
            return (MulticloudDbKey) m.invoke(READER, mod);
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError(roe);
        }
    }

    @Test
    @DisplayName("happy path: well-formed keys JSON with partitionKey only")
    void happyPath_partitionKeyOnly() {
        Struct mod = Struct.newBuilder()
                .set("keys").to("{\"partitionKey\":\"pk-1\"}")
                .build();
        MulticloudDbKey key = invokeExpectKey(mod);
        assertEquals("pk-1", key.partitionKey());
        assertNull(key.sortKey(), "sort key absent → null per MulticloudDbKey contract");
    }

    @Test
    @DisplayName("happy path: well-formed keys JSON with partitionKey + sortKey")
    void happyPath_partitionAndSort() {
        Struct mod = Struct.newBuilder()
                .set("keys").to("{\"partitionKey\":\"pk-1\",\"sortKey\":\"sk-1\"}")
                .build();
        MulticloudDbKey key = invokeExpectKey(mod);
        assertEquals("pk-1", key.partitionKey());
        assertEquals("sk-1", key.sortKey());
    }

    @Test
    @DisplayName("missing 'keys' field throws PROVIDER_ERROR(reason=missing_partition_key)")
    void missingKeysField_throws() {
        Struct mod = Struct.newBuilder().set("other").to("ignored").build();
        MulticloudDbException me = invokeExpectThrow(mod);
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, me.error().category(),
                "category must be PROVIDER_ERROR for malformed change-stream envelopes (matches Dynamo)");
        assertEquals("missing_partition_key", me.error().providerDetails().get("reason"),
                "reason token must match Dynamo's wire literal exactly for portable dead-letter logic");
        assertEquals(OperationNames.READ_CHANGES, me.error().operation(),
                "operation name must be the change-feed read op so diagnostics consumers attribute correctly");
        assertEquals(ProviderId.SPANNER, me.error().provider());
    }

    @Test
    @DisplayName("keys JSON present but missing partitionKey throws PROVIDER_ERROR(reason=missing_partition_key)")
    void presentButMissingPartitionKey_throws() {
        Struct mod = Struct.newBuilder()
                .set("keys").to("{\"sortKey\":\"sk-1\"}")
                .build();
        MulticloudDbException me = invokeExpectThrow(mod);
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, me.error().category());
        assertEquals("missing_partition_key", me.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("keys JSON present with null partitionKey throws PROVIDER_ERROR(reason=missing_partition_key)")
    void presentButNullPartitionKey_throws() {
        Struct mod = Struct.newBuilder()
                .set("keys").to("{\"partitionKey\":null}")
                .build();
        MulticloudDbException me = invokeExpectThrow(mod);
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, me.error().category());
        assertEquals("missing_partition_key", me.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("unparseable keys JSON throws PROVIDER_ERROR(reason=malformed_envelope)")
    void unparseableKeysJson_throws() {
        Struct mod = Struct.newBuilder()
                .set("keys").to("{not json}")
                .build();
        MulticloudDbException me = invokeExpectThrow(mod);
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, me.error().category());
        assertEquals("malformed_envelope", me.error().providerDetails().get("reason"),
                "distinct reason from missing_partition_key so callers can differentiate the two failure modes");
    }
}
