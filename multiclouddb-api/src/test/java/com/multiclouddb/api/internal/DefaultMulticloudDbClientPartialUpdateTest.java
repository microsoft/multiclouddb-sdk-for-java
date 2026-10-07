// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the ordering and zero-I/O behaviour of the default client partial-update path:
 * closed-client precedence, every shared INVALID_REQUEST path with zero delegation, a
 * supported core gate followed by exactly one delegation, a future unsupported provider
 * producing a typed UNSUPPORTED_CAPABILITY, and validation running before the gate.
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
        Map<String, Object> lastFields;

        RecordingProvider(CapabilitySet caps) { this.caps = caps; }

        @Override public ProviderId providerId() { return pid; }
        @Override public CapabilitySet capabilities() { return caps; }
        @Override public void update(ResourceAddress a, MulticloudDbKey k,
                Map<String, Object> f, OperationOptions o) {
            updateCount++;
            lastFields = new LinkedHashMap<>(f);
        }

        @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { throw new UnsupportedOperationException(); }
        @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { throw new UnsupportedOperationException(); }

        @Override public void close() { }
    }

    @JsonSerialize(using = ExpandingMapSerializer.class)
    private static final class SerializerAnnotatedMap
            extends LinkedHashMap<String, Object> {
    }

    private static final class ExpandingMapSerializer
            extends StdSerializer<SerializerAnnotatedMap> {

        private ExpandingMapSerializer() {
            super(SerializerAnnotatedMap.class);
        }

        @Override
        public void serialize(SerializerAnnotatedMap value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            for (int i = 0; i < 11; i++) {
                generator.writeStringField("emitted" + i, "value");
            }
            generator.writeEndObject();
        }
    }

    private static DefaultMulticloudDbClient client(RecordingProvider provider) {
        MulticloudDbClientConfig cfg = MulticloudDbClientConfig.builder().provider(provider.pid).build();
        return new DefaultMulticloudDbClient(provider, cfg);
    }

    private static CapabilitySet supported() {
        return new CapabilitySet(List.of(Capability.PARTIAL_UPDATE_CAP));
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

    private static Map<String, Object> fieldsOfSerializedSize(int targetBytes) throws Exception {
        int overhead = MAPPER.writeValueAsBytes(Map.of("p", "")).length;
        Map<String, Object> fields = Map.of(
                "p", "A".repeat(targetBytes - overhead));
        assertEquals(targetBytes, MAPPER.writeValueAsBytes(fields).length);
        return fields;
    }

    private static Map<String, Object> fieldsWithNestingDepth(int depth) {
        Object value = "leaf-value";
        for (int i = 0; i < depth; i++) {
            value = Map.of("level", value);
        }
        return Map.of("profile", value);
    }

    private static Map<String, Object> fieldsOverStructuralFootprint() {
        int elementCount = (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - 4) / 4 + 1;
        return Map.of("x", Collections.nCopies(elementCount, Map.of()));
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
        Map<String, Object> tooMany = new LinkedHashMap<>();
        for (int i = 0; i <= PartialUpdateValidator.MAX_FIELDS; i++) {
            tooMany.put("field" + i, i);
        }

        // null map, empty map, null/empty/blank name, reserved, underscore, field count
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of(), reserved, underscore, tooMany)) {
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

        MulticloudDbException fieldCountFailure = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, tooMany));
        assertEquals(Map.of(
                "reason", "partial_update_field_count_limit",
                "maximumFields", "10",
                "observedFields", "11"), fieldCountFailure.error().providerDetails());

        Map<String, Object> oversizedName = Map.of(
                "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 1), "v");
        MulticloudDbException nameFailure = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, oversizedName));
        assertEquals(PartialUpdateValidator.FIELD_NAME_SIZE_LIMIT_REASON,
                nameFailure.error().providerDetails().get("reason"));

        Map<String, Object> binary = Map.of("payload", new byte[] {1, 2});
        MulticloudDbException binaryFailure = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, binary));
        assertEquals(PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                binaryFailure.error().providerDetails().get("reason"));

        // update TTL is rejected before delegation
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                assertThrows(MulticloudDbException.class,
                        () -> c.update(ADDRESS, KEY, validFields(),
                                OperationOptions.builder().ttlSeconds(3600).build())).error().category());

        assertEquals(0, provider.updateCount, "no shared-validation failure may delegate to the provider");
    }

    @Test
    @DisplayName("unserializable fields fail before provider delegation")
    void unserializableFieldsFailBeforeDelegation() {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("payload", fields);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, fields));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(false, ex.error().retryable());
        assertEquals("partial_update_value_cycle",
                ex.error().providerDetails().get("reason"));
        assertNull(ex.getCause());
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("a replacement value at the 31-level boundary delegates without input mutation")
    void maximumNestingDepthDelegatesOnce() throws Exception {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        Map<String, Object> fields = fieldsWithNestingDepth(
                PartialUpdateStructureValidator.MAX_NESTING_DEPTH);
        byte[] before = MAPPER.writeValueAsBytes(fields);

        assertDoesNotThrow(() -> c.update(ADDRESS, KEY, fields));

        assertEquals(1, provider.updateCount);
        assertArrayEquals(before, MAPPER.writeValueAsBytes(fields));
    }

    @Test
    @DisplayName("a 32-level replacement fails before capability gating and provider delegation")
    void overNestingDepthFailsBeforeGate() {
        RecordingProvider provider = new RecordingProvider(coreUnsupported());
        DefaultMulticloudDbClient c = client(provider);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, fieldsWithNestingDepth(
                        PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1)));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertFalse(ex.error().retryable());
        assertNull(ex.error().provider());
        assertEquals(PartialUpdateStructureValidator.DEPTH_LIMIT_REASON,
                ex.error().providerDetails().get("reason"));
        assertEquals(String.valueOf(PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1),
                ex.error().providerDetails().get("actualNestingDepth"));
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("compact JSON over the structural footprint fails before provider delegation")
    void structuralFootprintFailureZeroDelegation() throws Exception {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        Map<String, Object> fields = fieldsOverStructuralFootprint();
        assertTrue(MAPPER.writeValueAsBytes(fields).length < DocumentSizeValidator.MAX_BYTES);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, fields));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertFalse(ex.error().retryable());
        assertNull(ex.error().provider());
        assertEquals(PartialUpdateStructureValidator.FOOTPRINT_LIMIT_REASON,
                ex.error().providerDetails().get("reason"));
        assertEquals(0, provider.updateCount);
    }

    @Test
    @DisplayName("a field map at the portable 390 KiB limit delegates exactly once")
    void exactCommonSizeDelegatesOnce() throws Exception {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        Map<String, Object> atLimit = fieldsOfSerializedSize(DocumentSizeValidator.MAX_BYTES);

        assertDoesNotThrow(() -> c.update(ADDRESS, KEY, atLimit));
        assertEquals(1, provider.updateCount);
    }

    @Test
    @DisplayName("a field map over the portable 390 KiB limit fails before provider delegation")
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
    @DisplayName("top-level Map serializers cannot rewrite validated update fields")
    void mapSerializerCannotRewriteTopLevelFields() {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient client = client(provider);
        SerializerAnnotatedMap fields = new SerializerAnnotatedMap();
        fields.put("status", "SHIPPED");

        assertDoesNotThrow(() -> client.update(ADDRESS, KEY, fields));

        assertEquals(1, provider.updateCount);
        assertEquals(Map.of("status", "SHIPPED"), provider.lastFields);
    }


    @Test
    @DisplayName("map inspection failures remain shared INVALID_REQUEST errors")
    void mapInspectionFailureDoesNotBecomeProviderError() {
        RecordingProvider provider = new RecordingProvider(supported());
        DefaultMulticloudDbClient c = client(provider);
        Map<String, Object> failing = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("iterator unavailable");
            }
        };

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> c.update(ADDRESS, KEY, failing));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals("portable_value_snapshot_failed",
                ex.error().providerDetails().get("reason"));
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

}
