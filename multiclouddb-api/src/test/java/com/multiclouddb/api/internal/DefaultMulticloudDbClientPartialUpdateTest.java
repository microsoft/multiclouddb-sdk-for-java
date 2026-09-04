// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentResult;
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
import com.multiclouddb.spi.MulticloudDbProviderClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the ordering and zero-I/O behaviour of the default client partial-update path:
 * closed-client precedence, every shared INVALID_REQUEST path with zero delegation, a
 * supported core gate followed by exactly one delegation, a future unsupported provider
 * producing a typed UNSUPPORTED_CAPABILITY, validation running before the gate, and no
 * consultation of the extended-payload capability.
 */
class DefaultMulticloudDbClientPartialUpdateTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "coll");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("p", "s");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Recording provider that counts delegated update() calls and returns a supplied CapabilitySet. */
    private static final class RecordingProvider implements MulticloudDbProviderClient {
        final ProviderId pid = ProviderId.fromId("recording-partial-update");
        final CapabilitySet caps;
        int updateCount = 0;

        RecordingProvider(CapabilitySet caps) { this.caps = caps; }

        @Override public ProviderId providerId() { return pid; }
        @Override public CapabilitySet capabilities() { return caps; }
        @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> f, OperationOptions o) { updateCount++; }

        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }

    private static DefaultMulticloudDbClient client(RecordingProvider provider) {
        MulticloudDbClientConfig cfg = MulticloudDbClientConfig.builder().provider(provider.pid).build();
        return new DefaultMulticloudDbClient(provider, cfg);
    }

    private static CapabilitySet supported() {
        return new CapabilitySet(List.of(Capability.PARTIAL_UPDATE_CAP,
                Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD_UNSUPPORTED));
    }

    private static CapabilitySet coreUnsupported() {
        return new CapabilitySet(List.of(Capability.PARTIAL_UPDATE_UNSUPPORTED));
    }

    private static CapabilitySet coreMissing() {
        return new CapabilitySet(List.of(Capability.CONTINUATION_TOKEN_PAGING_CAP));
    }

    private static Map<String, Object> validFields() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "SHIPPED");
        return f;
    }

    @Test
    @DisplayName("closed client wins over validation and delegation")
    void closedClientPrecedence() throws Exception {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        c.close();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, validFields()));
        assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, ex.error().category());
        assertEquals(0, provider.updateCount, "closed client must not delegate");
    }

    @Test
    @DisplayName("every shared INVALID_REQUEST path delegates zero provider operations")
    void sharedInvalidPathsZeroDelegation() {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);

        Map<String, Object> nullName = new LinkedHashMap<>();
        nullName.put(null, "v");
        Map<String, Object> blankName = new LinkedHashMap<>();
        blankName.put("   ", "v");
        Map<String, Object> reserved = new LinkedHashMap<>();
        reserved.put("partitionKey", "v");
        Map<String, Object> underscore = new LinkedHashMap<>();
        underscore.put("_x", "v");
        Map<String, Object> collide = new LinkedHashMap<>();
        collide.put("foo", 1);
        collide.put("Foo", 2);

        // null map, empty map, null/empty/blank name, reserved, underscore, collision
        for (Map<String, Object> bad : List.of(Map.<String, Object>of(), reserved, underscore, collide)) {
            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> c.update(ADDRESS, KEY, bad));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        }
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                assertThrows(MulticloudDbException.class, () -> c.update(ADDRESS, KEY, (Map<String, Object>) null)).error().category());
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                assertThrows(MulticloudDbException.class, () -> c.update(ADDRESS, KEY, nullName)).error().category());
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                assertThrows(MulticloudDbException.class, () -> c.update(ADDRESS, KEY, blankName)).error().category());

        // update TTL is rejected before delegation
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                assertThrows(MulticloudDbException.class,
                        () -> c.update(ADDRESS, KEY, validFields(),
                                OperationOptions.builder().ttlSeconds(3600).build())).error().category());

        assertEquals(0, provider.updateCount, "no shared-validation failure may delegate to the provider");
    }

    @Test
    @DisplayName("a 408,577-byte field map fails before provider delegation")
    void commonSizeFailureZeroDelegation() throws Exception {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        int overhead = MAPPER.writeValueAsBytes(Map.of("p", "")).length;
        Map<String, Object> tooLarge = Map.of(
                "p", "A".repeat(DocumentSizeValidator.MAX_BYTES + 1 - overhead));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, tooLarge));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("a supported PARTIAL_UPDATE declaration delegates exactly once")
    void supportedGateDelegatesOnce() {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        assertDoesNotThrow(() -> c.update(ADDRESS, KEY, validFields()));
        assertEquals(1, provider.updateCount);
    }

    @Test
    @DisplayName("a future unsupported provider fails with typed UNSUPPORTED_CAPABILITY and zero delegation")
    void unsupportedGateIsTyped() {
        RecordingProvider provider = new RecordingProvider(coreUnsupported());
        DefaultMulticloudDbClient c = client(provider);
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, validFields()));
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        assertEquals(false, ex.error().retryable());
        assertEquals(OperationNames.UPDATE, ex.error().operation());
        assertEquals(Capability.PARTIAL_UPDATE, ex.error().providerDetails().get("capability"));
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("a provider missing PARTIAL_UPDATE fails with typed UNSUPPORTED_CAPABILITY")
    void missingCoreCapabilityIsTyped() {
        RecordingProvider provider = new RecordingProvider(coreMissing());
        DefaultMulticloudDbClient c = client(provider);
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, validFields()));
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        assertEquals(Capability.PARTIAL_UPDATE, ex.error().providerDetails().get("capability"));
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("shared validation runs before the core capability gate")
    void validationBeforeGate() {
        RecordingProvider provider = new RecordingProvider(coreUnsupported());
        DefaultMulticloudDbClient c = client(provider);
        // Even though the provider declares the core capability unsupported, an invalid field
        // map fails as INVALID_REQUEST (validation first), not UNSUPPORTED_CAPABILITY.
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, Map.of()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("the default client never consults PARTIAL_UPDATE_EXTENDED_PAYLOAD")
    void noExtendedPayloadLookup() {
        CapabilitySet caps = mock(CapabilitySet.class);
        when(caps.isSupported(Capability.PARTIAL_UPDATE)).thenReturn(true);
        RecordingProvider provider = new RecordingProvider(caps);
        DefaultMulticloudDbClient c = client(provider);

        assertDoesNotThrow(() -> c.update(ADDRESS, KEY, validFields()));
        assertEquals(1, provider.updateCount);
        verify(caps).isSupported(Capability.PARTIAL_UPDATE);
        verify(caps, never()).isSupported(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
    }
}
