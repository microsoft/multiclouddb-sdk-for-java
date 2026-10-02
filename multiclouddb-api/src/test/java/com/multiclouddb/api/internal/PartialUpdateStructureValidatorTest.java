// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartialUpdateStructureValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record IterableBean(Iterable<Integer> values) { }


    private record LargeCollectionBean(List<String> values) { }


    @JsonSerialize(using = DirectObjectSerializer.class)
    private record DirectObjectValue(Object value) { }

    private static final class DirectObjectSerializer
            extends StdSerializer<DirectObjectValue> {
        private DirectObjectSerializer() {
            super(DirectObjectValue.class);
        }

        @Override
        public void serialize(DirectObjectValue value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeObject(value.value());
        }
    }

    @JsonSerialize(using = DuplicateFieldSerializer.class)
    private static final class DuplicateFieldValue { }

    private static final class DuplicateFieldSerializer
            extends StdSerializer<DuplicateFieldValue> {
        private DuplicateFieldSerializer() {
            super(DuplicateFieldValue.class);
        }

        @Override
        public void serialize(DuplicateFieldValue value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("value", "first");
            generator.writeStringField("value", "second");
            generator.writeEndObject();
        }
    }

    @JsonSerialize(using = ReenteringSerializer.class)
    private static final class ReenteringValue { }

    private static final class ReenteringSerializer
            extends StdSerializer<ReenteringValue> {
        private ReenteringSerializer() {
            super(ReenteringValue.class);
        }

        @Override
        public void serialize(ReenteringValue value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeObject(value);
        }
    }

    @JsonSerialize(using = StatefulSerializer.class)
    private static final class StatefulValue {
        private int serializationCount;
    }

    private static final class StatefulSerializer
            extends StdSerializer<StatefulValue> {
        private StatefulSerializer() {
            super(StatefulValue.class);
        }

        @Override
        public void serialize(StatefulValue value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            value.serializationCount++;
            generator.writeString("snapshot-" + value.serializationCount);
        }
    }

    @JsonSerialize(using = AnnotatedIterableSerializer.class)
    private static final class AnnotatedIterable implements Iterable<Integer> {
        @Override
        public Iterator<Integer> iterator() {
            throw new AssertionError("annotated iterable must not be traversed");
        }
    }

    private static final class AnnotatedIterableSerializer
            extends StdSerializer<AnnotatedIterable> {
        private AnnotatedIterableSerializer() {
            super(AnnotatedIterable.class);
        }

        @Override
        public void serialize(AnnotatedIterable value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeString("custom");
        }
    }

    @Test
    @DisplayName("portable structure constants match the DynamoDB-safe envelope")
    void constantsMatchPortableEnvelope() {
        assertEquals(31, PartialUpdateStructureValidator.MAX_NESTING_DEPTH);
        assertEquals(DocumentSizeValidator.MAX_BYTES,
                PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES);
    }

    @Test
    @DisplayName("31 nested replacement containers pass shared preflight")
    void maximumNestingDepthPasses() throws Exception {
        Map<String, Object> fields = Map.of("profile", nestedMaps(31));

        assertDoesNotThrow(() -> PartialUpdateStructureValidator.validatePartialUpdate(
                MAPPER.writeValueAsBytes(fields), OperationNames.UPDATE));
    }

    @Test
    @DisplayName("32 nested replacement containers fail with stable limit details")
    void oneOverNestingDepthFails() throws Exception {
        Map<String, Object> fields = Map.of("profile", nestedMaps(32));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> PartialUpdateStructureValidator.validatePartialUpdate(
                        MAPPER.writeValueAsBytes(fields), OperationNames.UPDATE));

        assertInvalid(ex, PartialUpdateStructureValidator.DEPTH_LIMIT_REASON);
        assertEquals("32", ex.error().providerDetails().get("actualNestingDepth"));
        assertEquals("31", ex.error().providerDetails().get("maximumNestingDepth"));
        assertTrue(ex.error().providerDetails().get("valuePath").startsWith("/profile"));
        assertFalse(ex.error().message().contains("leaf-value"));
    }

    @Test
    @DisplayName("complete documents share the 31-level nesting boundary")
    void completeDocumentUsesSameNestingBoundary() throws Exception {
        byte[] accepted = MAPPER.writeValueAsBytes(Map.of("profile", nestedMaps(31)));
        byte[] rejected = MAPPER.writeValueAsBytes(Map.of("profile", nestedMaps(32)));

        assertDoesNotThrow(() -> PartialUpdateStructureValidator.validateDocument(
                accepted, OperationNames.CREATE));
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> PartialUpdateStructureValidator.validateDocument(
                        rejected, OperationNames.UPSERT));

        assertInvalid(ex, PartialUpdateStructureValidator.DOCUMENT_DEPTH_LIMIT_REASON);
        assertEquals("32", ex.error().providerDetails().get("actualNestingDepth"));
        assertEquals("31", ex.error().providerDetails().get("maximumNestingDepth"));
    }

    @Test
    @DisplayName("all nested field names share the 50,000-byte portable bound")
    void nestedFieldNameBoundaryIsValidated() throws Exception {
        String maximumName = "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES);
        String oversizedName = maximumName + "a";
        byte[] accepted = MAPPER.writeValueAsBytes(
                Map.of("profile", Map.of(maximumName, "value")));
        byte[] rejected = MAPPER.writeValueAsBytes(
                Map.of("profile", Map.of(oversizedName, "value")));

        assertDoesNotThrow(() -> PartialUpdateStructureValidator.validatePartialUpdate(
                accepted, OperationNames.UPDATE));
        assertDoesNotThrow(() -> PartialUpdateStructureValidator.validateDocument(
                accepted, OperationNames.CREATE));
        MulticloudDbException update = assertThrows(MulticloudDbException.class,
                () -> PartialUpdateStructureValidator.validatePartialUpdate(
                        rejected, OperationNames.UPDATE));
        MulticloudDbException document = assertThrows(MulticloudDbException.class,
                () -> PartialUpdateStructureValidator.validateDocument(
                        rejected, OperationNames.UPSERT));

        assertInvalid(update, PartialUpdateValidator.FIELD_NAME_SIZE_LIMIT_REASON);
        assertInvalid(document, "document_field_name_size_limit");
        assertEquals(String.valueOf(PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 1),
                update.error().providerDetails().get("actualFieldNameBytes"));
        assertEquals(String.valueOf(PartialUpdateValidator.MAX_FIELD_NAME_BYTES),
                update.error().providerDetails().get("maximumFieldNameBytes"));
        assertNull(update.error().providerDetails().get("valuePath"));
        assertFalse(update.error().message().contains(oversizedName));
    }

    @Test
    @DisplayName("a structural footprint at exactly 390 KiB passes both write shapes")
    void exactStructuralFootprintPasses() throws Exception {
        int elementCount = (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - 4) / 4;
        assertEquals(PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES,
                1 + 3 + (4 * elementCount), "fixture footprint must be exact");
        byte[] serialized = MAPPER.writeValueAsBytes(denseEmptyMapFields(elementCount));
        assertTrue(serialized.length < DocumentSizeValidator.MAX_BYTES,
                "fixture must pass the independent serialized-JSON limit");

        assertDoesNotThrow(() -> PartialUpdateStructureValidator.validatePartialUpdate(
                serialized, OperationNames.UPDATE));
        assertDoesNotThrow(() -> PartialUpdateStructureValidator.validateDocument(
                serialized, OperationNames.CREATE));
    }

    @Test
    @DisplayName("compact JSON over native structural overhead fails for every write shape")
    void nativeContainerOverheadCannotBypassPortableFootprint() throws Exception {
        int elementCount = (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - 4) / 4 + 1;
        byte[] serialized = MAPPER.writeValueAsBytes(denseEmptyMapFields(elementCount));
        assertTrue(serialized.length < DocumentSizeValidator.MAX_BYTES,
                "fixture must pass the independent serialized-JSON limit");

        MulticloudDbException update = assertThrows(MulticloudDbException.class,
                () -> PartialUpdateStructureValidator.validatePartialUpdate(
                        serialized, OperationNames.UPDATE));
        MulticloudDbException create = assertThrows(MulticloudDbException.class,
                () -> PartialUpdateStructureValidator.validateDocument(
                        serialized, OperationNames.CREATE));

        assertInvalid(update, PartialUpdateStructureValidator.FOOTPRINT_LIMIT_REASON);
        assertInvalid(create, PartialUpdateStructureValidator.DOCUMENT_FOOTPRINT_LIMIT_REASON);
        assertEquals(String.valueOf(PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES),
                update.error().providerDetails().get("maximumPortableFootprintBytes"));
        assertTrue(Long.parseLong(update.error().providerDetails()
                .get("actualPortableFootprintBytes"))
                > PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES);
    }

    @Test
    @DisplayName("maximum field names do not multiply allocation across wide lists")
    void maximumFieldNameWithWideListRemainsBounded() {
        String fieldName = "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES);
        int elementCount = (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES
                - PartialUpdateValidator.MAX_FIELD_NAME_BYTES - 3) / 4;
        Map<String, Object> fields = Map.of(
                fieldName, Collections.nCopies(elementCount, Map.of()));

        Map<String, Object> normalized = assertDoesNotThrow(() ->
                DocumentSizeValidator.validateAndSnapshotPartialUpdate(
                        fields, OperationNames.UPDATE));

        assertEquals(elementCount, ((List<?>) normalized.get(fieldName)).size());
        assertEquals(PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - 1,
                PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 3L + 4L * elementCount);
    }
    @Test
    @DisplayName("char arrays follow Jackson string semantics at the depth boundary")
    void charArrayIsAJsonScalarForDepth() {
        Object value = new char[] {'x'};
        for (int i = 0; i < PartialUpdateStructureValidator.MAX_NESTING_DEPTH; i++) {
            value = Map.of("level", value);
        }
        Map<String, Object> document = Map.of("profile", value);

        assertDoesNotThrow(() -> DocumentSizeValidator.validate(
                document, OperationNames.CREATE));
    }

    @Test
    @DisplayName("unbounded nested maps stop at the graph complexity limit")
    void unboundedNestedMapIsBounded() {
        Map<String, Object> unbounded = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Object>> iterator() {
                        return new Iterator<>() {
                            private int index;

                            @Override
                            public boolean hasNext() {
                                return true;
                            }

                            @Override
                            public Entry<String, Object> next() {
                                return Map.entry("field" + index++, "value");
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return Integer.MAX_VALUE;
                    }
                };
            }
        };

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("nested", unbounded), OperationNames.CREATE));

        assertInvalid(ex, "document_value_complexity_limit");
    }

    @Test
    @DisplayName("very deep raw maps fail without overflowing the Java stack")
    void deepRawMapFailsIteratively() {
        Object value = "leaf";
        for (int i = 0; i < 5_000; i++) {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("level", value);
            value = parent;
        }
        Map<String, Object> document = Map.of("profile", value);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, OperationNames.CREATE));

        assertInvalid(ex, PartialUpdateStructureValidator.DOCUMENT_DEPTH_LIMIT_REASON);
        assertEquals("32", ex.error().providerDetails().get("actualNestingDepth"));
    }

    @Test
    @DisplayName("unbounded iterables are rejected before iteration or serialization")
    void unboundedIterableIsRejected() {
        Iterable<Integer> unbounded = () -> new Iterator<>() {
            private int value;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Integer next() {
                return value++;
            }
        };

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("values", unbounded), OperationNames.UPSERT));

        assertInvalid(ex, "non_portable_iterable");
    }

    @Test
    @DisplayName("iterables hidden in POJOs are rejected before their iterator is opened")
    void hiddenNonCollectionIterableIsRejected() {
        Iterable<Integer> mustNotIterate = () -> {
            throw new AssertionError("non-collection iterable must not be traversed");
        };

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", new IterableBean(mustNotIterate)),
                        OperationNames.CREATE));

        assertInvalid(ex, "non_portable_iterable");
        assertEquals("/payload/values", ex.error().providerDetails().get("valuePath"));
    }

    @Test
    @DisplayName("non-collection iterables stay invalid despite custom serializers")
    void annotatedNonCollectionIterableIsRejected() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", new IterableBean(new AnnotatedIterable())),
                        OperationNames.CREATE));

        assertInvalid(ex, "non_portable_iterable");
    }

    @Test
    @DisplayName("POJO serialization stops at the serialized byte limit")
    void hiddenLargeCollectionIsSerializedWithABound() {
        LargeCollectionBean value = new LargeCollectionBean(
                Collections.nCopies(DocumentSizeValidator.MAX_BYTES, "x"));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", value), OperationNames.UPSERT));

        assertInvalid(ex, "document_serialized_size_limit");
        assertEquals(String.valueOf(DocumentSizeValidator.MAX_BYTES),
                ex.error().providerDetails().get("maximumSerializedBytes"));
    }

    @Test
    @DisplayName("direct generator object writes cannot bypass binary guards")
    void directObjectWritesCannotBypassBinaryGuards() {
        MulticloudDbException binary = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", new DirectObjectValue(new byte[] {1, 2})),
                        OperationNames.CREATE));
        assertInvalid(binary, PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON);
        assertEquals("/payload", binary.error().providerDetails().get("valuePath"));
    }

    @Test
    @DisplayName("duplicate properties emitted by custom serializers are rejected")
    void duplicateSerializerPropertiesAreRejected() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", new DuplicateFieldValue()),
                        OperationNames.CREATE));

        assertInvalid(ex, "portable_value_normalization_failed");
    }


    @Test
    @DisplayName("serializer re-entry fails as a typed cycle instead of raw stack overflow")
    void serializerReentryIsRejectedAsCycle() {
        ReenteringValue value = new ReenteringValue();

        MulticloudDbException document = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(
                        Map.of("payload", value), OperationNames.CREATE));
        MulticloudDbException update = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validatePartialUpdate(
                        Map.of("payload", value), OperationNames.UPDATE));

        assertInvalid(document, "document_value_cycle");
        assertInvalid(update, "partial_update_value_cycle");
    }

    @Test
    @DisplayName("bounded serialization runs once and becomes the delegated snapshot")
    void boundedSerializationIsAuthoritative() {
        StatefulValue value = new StatefulValue();

        Map<String, Object> snapshot = DocumentSizeValidator
                .validateAndSnapshotDocument(
                        Map.of("payload", value), OperationNames.CREATE);

        assertEquals(1, value.serializationCount);
        assertEquals("snapshot-1", snapshot.get("payload"));
    }

    @Test
    @DisplayName("duplicate root-map entries fail after bounded inspection")
    void duplicateRootEntriesAreRejectedPromptly() {
        int[] reads = {0};
        Map<String, Object> repeated = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Object>> iterator() {
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                return true;
                            }

                            @Override
                            public Entry<String, Object> next() {
                                reads[0]++;
                                return Map.entry("same", "value");
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return Integer.MAX_VALUE;
                    }
                };
            }
        };

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(repeated, OperationNames.CREATE));

        assertInvalid(ex, "portable_value_snapshot_failed");
        assertEquals(2, reads[0]);
    }


    private static void assertInvalid(MulticloudDbException ex, String reason) {
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertFalse(ex.error().retryable());
        assertNull(ex.error().provider());
        assertEquals(reason, ex.error().providerDetails().get("reason"));
    }

    private static Map<String, Object> denseEmptyMapFields(int elementCount) {
        return Map.of("x", Collections.nCopies(elementCount, Map.of()));
    }

    private static Object nestedMaps(int depth) {
        Object value = "leaf-value";
        for (int i = 0; i < depth; i++) {
            value = Map.of("level", value);
        }
        return value;
    }
}
