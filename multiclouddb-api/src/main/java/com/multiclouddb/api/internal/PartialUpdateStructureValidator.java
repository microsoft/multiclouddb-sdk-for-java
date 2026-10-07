// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.module.SimpleSerializers;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces the portable structural envelope for document writes and partial-update
 * replacement values.
 *
 * <p>Serialized JSON size and this structure-aware footprint are separate limits.
 * Empty maps and lists have a native cost that compact JSON does not capture. The
 * footprint conservatively follows DynamoDB's documented attribute-name and
 * map/list-overhead accounting while remaining provider-neutral.</p>
 */
public final class PartialUpdateStructureValidator {

    /** Maximum map/list levels below the top-level document root. */
    public static final int MAX_NESTING_DEPTH = WriteLimits.MAX_NESTED_CONTAINERS;

    /** Maximum portable structural footprint for a write input or resulting document. */
    public static final int MAX_FOOTPRINT_BYTES = WriteLimits.MAX_STRUCTURAL_FOOTPRINT_BYTES;

    public static final String DEPTH_LIMIT_REASON = "partial_update_nesting_depth_limit";
    public static final String FOOTPRINT_LIMIT_REASON = "partial_update_structural_footprint_limit";
    public static final String DOCUMENT_DEPTH_LIMIT_REASON = "document_nesting_depth_limit";
    public static final String DOCUMENT_FOOTPRINT_LIMIT_REASON = "document_structural_footprint_limit";
    public static final String NON_PORTABLE_BINARY_REASON = "non_portable_binary_value";
    private static final int CONTAINER_OVERHEAD_BYTES = 3;
    private static final int NESTED_ELEMENT_OVERHEAD_BYTES = 1;
    private static final int MAX_VALUE_GRAPH_NODES = DocumentSizeValidator.MAX_BYTES;
    private static final ObjectMapper MAPPER = createMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private PartialUpdateStructureValidator() {
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper(
                JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder()
                                .maxNameLength(DocumentSizeValidator.MAX_BYTES)
                                .build())
                        .streamWriteConstraints(StreamWriteConstraints.builder()
                                .maxNestingDepth(MAX_NESTING_DEPTH + 1)
                                .build())
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .enable(StreamWriteFeature.STRICT_DUPLICATE_DETECTION)
                        .build());
        SimpleModule module = new SimpleModule();
        module.setSerializers(new PortableSerializers());
        mapper.registerModule(module);
        mapper.setAnnotationIntrospector(new PortableAnnotationIntrospector());
        return mapper;
    }

    /** Validates a serialized partial-update field map. */
    public static void validatePartialUpdate(byte[] serializedFields, String operation) {
        validateSerializedObject(serializedFields, operation, true);
    }

    /** Validates a serialized complete create/upsert document. */
    public static void validateDocument(byte[] serializedDocument, String operation) {
        validateSerializedObject(serializedDocument, operation, false);
    }

    /** Validates the caller graph and returns a bounded top-level snapshot. */
    static Map<String, Object> validateAndSnapshotDocument(Object value, String operation) {
        Object snapshot = snapshotRootMap(value, operation);
        validatePortableValues(snapshot, operation);

        byte[] serialized = serializePortableValue(snapshot, operation);
        final JsonNode serializedRoot;
        try {
            serializedRoot = MAPPER.readTree(serialized);
        } catch (IOException e) {
            throw normalizationFailure(operation, e);
        }

        boolean partialUpdate = OperationNames.UPDATE.equals(operation);
        validateSerializedRoot(serializedRoot, operation, partialUpdate);
        try {
            return MAPPER.convertValue(serializedRoot, MAP_TYPE);
        } catch (IllegalArgumentException e) {
            throw normalizationFailure(operation, e);
        }
    }

    /** Performs bounded, iterative validation before Jackson serialization. */
    static void validatePortableValues(Object value, String operation) {
        validatePortableValueGraph(value, operation);
    }

    private static void validateSerializedObject(byte[] serialized, String operation,
            boolean partialUpdate) {
        if (serialized == null) {
            return;
        }

        final JsonNode root;
        try {
            root = MAPPER.readTree(serialized);
        } catch (IOException e) {
            throw invalidRequest(
                    subject(partialUpdate) + " could not be inspected for structural limits.",
                    operation,
                    Map.of("reason", partialUpdate
                            ? "partial_update_structure_inspection_failed"
                            : "document_structure_inspection_failed"),
                    e);
        }

        validateSerializedRoot(root, operation, partialUpdate);
    }

    private static void validateSerializedRoot(JsonNode root, String operation,
            boolean partialUpdate) {
        if (root == null || !root.isObject()) {
            throw invalidRequest(
                    subject(partialUpdate) + " must serialize as a JSON object.",
                    operation,
                    Map.of("reason", partialUpdate
                            ? "partial_update_fields_not_object"
                            : "document_not_object"),
                    null);
        }

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            ValuePath path = ValuePath.field(ValuePath.ROOT, field.getKey());
            validateFieldNameSize(field.getKey(), operation, partialUpdate);
            validateDepth(field.getValue(), 1, path, operation, partialUpdate);
        }

        long footprintBytes = rootFootprint(root);
        if (footprintBytes > MAX_FOOTPRINT_BYTES) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("reason", partialUpdate
                    ? FOOTPRINT_LIMIT_REASON : DOCUMENT_FOOTPRINT_LIMIT_REASON);
            details.put("actualPortableFootprintBytes", String.valueOf(footprintBytes));
            details.put("maximumPortableFootprintBytes", String.valueOf(MAX_FOOTPRINT_BYTES));
            throw invalidRequest(
                    subject(partialUpdate) + " structural footprint " + footprintBytes
                            + " bytes exceeds the portable 390 KiB limit.",
                    operation,
                    details,
                    null);
        }
    }

    private static void validateDepth(JsonNode value, int depth, ValuePath path,
            String operation, boolean partialUpdate) {
        if (value == null || !value.isContainerNode()) {
            return;
        }
        if (depth > MAX_NESTING_DEPTH) {
            String renderedPath = path.render();
            Map<String, String> details = new LinkedHashMap<>();
            details.put("reason", partialUpdate
                    ? DEPTH_LIMIT_REASON : DOCUMENT_DEPTH_LIMIT_REASON);
            details.put("actualNestingDepth", String.valueOf(depth));
            details.put("maximumNestingDepth", String.valueOf(MAX_NESTING_DEPTH));
            details.put("valuePath", renderedPath);
            throw invalidRequest(
                    subject(partialUpdate) + " value at " + renderedPath
                            + " has map/list nesting depth " + depth
                            + "; the portable maximum is " + MAX_NESTING_DEPTH + ".",
                    operation,
                    details,
                    null);
        }

        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> children = value.fields();
            while (children.hasNext()) {
                Map.Entry<String, JsonNode> child = children.next();
                validateFieldNameSize(child.getKey(), operation, partialUpdate);
                validateDepth(child.getValue(), depth + 1,
                        ValuePath.field(path, child.getKey()), operation, partialUpdate);
            }
        } else {
            for (int i = 0; i < value.size(); i++) {
                validateDepth(value.get(i), depth + 1, ValuePath.index(path, i),
                        operation, partialUpdate);
            }
        }
    }

    private static void validateFieldNameSize(String name, String operation,
            boolean partialUpdate) {
        int nameBytes = utf8Bytes(name);
        if (nameBytes <= PartialUpdateValidator.MAX_FIELD_NAME_BYTES) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", partialUpdate
                ? PartialUpdateValidator.FIELD_NAME_SIZE_LIMIT_REASON
                : "document_field_name_size_limit");
        details.put("actualFieldNameBytes", String.valueOf(nameBytes));
        details.put("maximumFieldNameBytes",
                String.valueOf(PartialUpdateValidator.MAX_FIELD_NAME_BYTES));
        throw invalidRequest(
                subject(partialUpdate) + " field name is " + nameBytes
                        + " UTF-8 bytes; the portable maximum is "
                        + PartialUpdateValidator.MAX_FIELD_NAME_BYTES + ".",
                operation,
                details,
                null);
    }

    private static Object snapshotRootMap(Object value, String operation) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }

        Map<Object, Object> snapshot = new LinkedHashMap<>();
        int maximumEntries = OperationNames.UPDATE.equals(operation)
                ? WriteLimits.MAX_PARTIAL_UPDATE_FIELDS + 1
                : MAX_VALUE_GRAPH_NODES;
        try {
            Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
            int examinedEntries = 0;
            while (examinedEntries < maximumEntries && entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                if (snapshot.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Root map iterator returned a duplicate key");
                }
                snapshot.put(entry.getKey(), entry.getValue());
                examinedEntries++;
            }
        } catch (RuntimeException e) {
            throw invalidRequest(
                    subject(OperationNames.UPDATE.equals(operation))
                            + " could not be snapshotted safely.",
                    operation,
                    Map.of("reason", "portable_value_snapshot_failed"),
                    e);
        }
        return snapshot;
    }

    private static void validatePortableValueGraph(Object root, String operation) {
        boolean partialUpdate = OperationNames.UPDATE.equals(operation);
        Deque<TraversalStep> pending = new ArrayDeque<>();
        IdentityHashMap<Object, Boolean> activeContainers = new IdentityHashMap<>();
        pending.push(new PendingValue(root, ValuePath.ROOT, 0));
        int nodes = 0;

        while (!pending.isEmpty()) {
            TraversalStep step = pending.pop();
            if (step instanceof ExitContainer exit) {
                activeContainers.remove(exit.value());
                continue;
            }
            if (step instanceof PendingChildren children) {
                try {
                    if (children.values().hasNext()) {
                        pending.push(children);
                        pending.push(children.values().next());
                    }
                } catch (MulticloudDbException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw inspectionFailure(operation, partialUpdate, e);
                }
                continue;
            }

            PendingValue current = (PendingValue) step;
            Object value = current.value();
            if (++nodes > MAX_VALUE_GRAPH_NODES) {
                throw complexityLimit(operation, partialUpdate);
            }
            if (value == null || value instanceof char[]) {
                continue;
            }
            if (value instanceof byte[] || value instanceof ByteBuffer
                    || value instanceof JsonNode node && node.isBinary()) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", NON_PORTABLE_BINARY_REASON);
                details.put("valuePath", current.path().render());
                details.put("valueType", portableTypeName(value));
                throw invalidRequest(
                        "Binary values are not part of the portable JSON value model.",
                        operation, details, null);
            }
            if (value instanceof Iterable<?> && !(value instanceof Collection<?>)
                    && !(value instanceof JsonNode)) {
                throw invalidRequest(
                        "Portable JSON arrays must use a bounded Collection or Java array.",
                        operation,
                        Map.of("reason", "non_portable_iterable"),
                        null);
            }

            boolean container = isContainer(value);
            if (container && current.depth() > MAX_NESTING_DEPTH) {
                String renderedPath = current.path().render();
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", partialUpdate
                        ? DEPTH_LIMIT_REASON : DOCUMENT_DEPTH_LIMIT_REASON);
                details.put("actualNestingDepth", String.valueOf(current.depth()));
                details.put("maximumNestingDepth", String.valueOf(MAX_NESTING_DEPTH));
                details.put("valuePath", renderedPath);
                throw invalidRequest(
                        subject(partialUpdate) + " value at " + renderedPath
                                + " has map/list nesting depth " + current.depth()
                                + "; the portable maximum is " + MAX_NESTING_DEPTH + ".",
                        operation, details, null);
            }
            if (!container) {
                continue;
            }
            if (activeContainers.put(value, Boolean.TRUE) != null) {
                throw invalidRequest(
                        subject(partialUpdate) + " contains a self-referential value graph.",
                        operation,
                        Map.of("reason", partialUpdate
                                        ? "partial_update_value_cycle"
                                        : "document_value_cycle",
                                "valuePath", current.path().render()),
                        null);
            }

            pending.push(new ExitContainer(value));
            try {
                pending.push(new PendingChildren(childValues(
                        value, current.path(), current.depth(), operation, partialUpdate)));
            } catch (MulticloudDbException e) {
                throw e;
            } catch (RuntimeException e) {
                throw inspectionFailure(operation, partialUpdate, e);
            }
        }
    }

    private static Iterator<PendingValue> childValues(
            Object value, ValuePath path, int depth, String operation,
            boolean partialUpdate) {
        if (value instanceof JsonNode node && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return fields.hasNext();
                }

                @Override
                public PendingValue next() {
                    Map.Entry<String, JsonNode> field = fields.next();
                    validateFieldNameSize(field.getKey(), operation, partialUpdate);
                    return new PendingValue(
                            field.getValue(),
                            ValuePath.field(path, field.getKey()),
                            depth + 1);
                }
            };
        }
        if (value instanceof JsonNode node && node.isArray()) {
            return indexedChildren(node.size(), node::get, path, depth);
        }
        if (value instanceof Map<?, ?> map) {
            Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public PendingValue next() {
                    Map.Entry<?, ?> entry = entries.next();
                    if (!(entry.getKey() instanceof String name)) {
                        throw invalidRequest(
                                "Portable JSON object keys must be strings.",
                                operation,
                                Map.of("reason", "non_portable_object_key"),
                                null);
                    }
                    validateFieldNameSize(name, operation, partialUpdate);
                    return new PendingValue(
                            entry.getValue(),
                            ValuePath.field(path, name),
                            depth + 1);
                }
            };
        }
        if (value instanceof Collection<?> collection) {
            Iterator<?> elements = collection.iterator();
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return elements.hasNext();
                }

                @Override
                public PendingValue next() {
                    return new PendingValue(
                            elements.next(), ValuePath.index(path, index++), depth + 1);
                }
            };
        }
        return indexedChildren(Array.getLength(value),
                index -> Array.get(value, index), path, depth);
    }

    private static Iterator<PendingValue> indexedChildren(
            int size, java.util.function.IntFunction<Object> values,
            ValuePath path, int depth) {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public PendingValue next() {
                int current = index++;
                return new PendingValue(
                        values.apply(current), ValuePath.index(path, current), depth + 1);
            }
        };
    }

    private static MulticloudDbException inspectionFailure(
            String operation, boolean partialUpdate, RuntimeException cause) {
        return invalidRequest(
                subject(partialUpdate) + " could not be inspected safely.",
                operation,
                Map.of("reason", partialUpdate
                                ? "partial_update_value_inspection_failed"
                                : "document_value_inspection_failed"),
                cause);
    }

    private static MulticloudDbException complexityLimit(
            String operation, boolean partialUpdate) {
        return invalidRequest(
                subject(partialUpdate) + " has too many values to inspect safely.",
                operation,
                Map.of("reason", partialUpdate
                                ? "partial_update_value_complexity_limit"
                                : "document_value_complexity_limit",
                        "maximumValueNodes", String.valueOf(MAX_VALUE_GRAPH_NODES)),
                null);
    }

    private static boolean isContainer(Object value) {
        return value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value instanceof JsonNode node && node.isContainerNode()
                || value != null && value.getClass().isArray()
                        && !(value instanceof byte[] || value instanceof char[]);
    }

    private static byte[] serializePortableValue(Object value, String operation) {
        try (BoundedOutputStream output = new BoundedOutputStream(DocumentSizeValidator.MAX_BYTES);
                JsonGenerator generator = new PortableJsonGenerator(
                        MAPPER.getFactory().createGenerator(output))) {
            MAPPER.writeValue(generator, value);
            return output.toByteArray();
        } catch (IOException e) {
            SerializationSizeLimitException sizeLimit =
                    findCause(e, SerializationSizeLimitException.class);
            if (sizeLimit != null) {
                throw serializedSizeLimit(operation);
            }
            NonPortableValueException nonPortable =
                    findCause(e, NonPortableValueException.class);
            if (nonPortable != null) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", nonPortable.reason());
                String path = nonPortable.valuePath() != null
                        ? nonPortable.valuePath() : mappingPath(e);
                if (path != null) {
                    details.put("valuePath", path);
                }
                if (nonPortable.valueType() != null) {
                    details.put("valueType", nonPortable.valueType());
                }
                String message = switch (nonPortable.reason()) {
                    case "non_portable_iterable" ->
                            "Portable JSON arrays must use a bounded Collection or Java array.";
                    case NON_PORTABLE_BINARY_REASON ->
                            "Binary values are not part of the portable JSON value model.";
                    default -> "Write input contains a non-portable value.";
                };
                throw invalidRequest(message, operation, details, e);
            }
            if (isCycleFailure(e)) {
                throw valueCycleFailure(operation, e);
            }
            if (findCause(e, StreamConstraintsException.class) != null) {
                throw serializedDepthFailure(operation, e);
            }
            throw normalizationFailure(operation, e);
        } catch (StackOverflowError e) {
            throw valueCycleFailure(operation, e);
        } catch (RuntimeException e) {
            throw normalizationFailure(operation, e);
        }
    }

    private static boolean isCycleFailure(Throwable failure) {
        if (findCause(failure, StackOverflowError.class) != null) {
            return true;
        }
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Direct self-reference")
                    || message.contains("Infinite recursion"))) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MulticloudDbException valueCycleFailure(
            String operation, Throwable cause) {
        boolean partialUpdate = OperationNames.UPDATE.equals(operation);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", partialUpdate
                ? "partial_update_value_cycle" : "document_value_cycle");
        String path = mappingPath(cause);
        if (path != null) {
            details.put("valuePath", path);
        }
        return invalidRequest(
                subject(partialUpdate) + " contains a self-referential value graph.",
                operation, details, cause);
    }

    private static MulticloudDbException serializedDepthFailure(
            String operation, Throwable cause) {
        boolean partialUpdate = OperationNames.UPDATE.equals(operation);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", partialUpdate
                ? DEPTH_LIMIT_REASON : DOCUMENT_DEPTH_LIMIT_REASON);
        details.put("actualNestingDepth", String.valueOf(MAX_NESTING_DEPTH + 1));
        details.put("maximumNestingDepth", String.valueOf(MAX_NESTING_DEPTH));
        String path = mappingPath(cause);
        if (path != null) {
            details.put("valuePath", path);
        }
        return invalidRequest(
                subject(partialUpdate) + " exceeds the portable map/list nesting depth of "
                        + MAX_NESTING_DEPTH + ".",
                operation, details, cause);
    }

    private static MulticloudDbException serializedSizeLimit(String operation) {
        long actualBytes = DocumentSizeValidator.MAX_BYTES + 1L;
        long actualKiB = (actualBytes + 1023L) / 1024L;
        boolean partialUpdate = OperationNames.UPDATE.equals(operation);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", partialUpdate
                ? "partial_update_serialized_size_limit"
                : "document_serialized_size_limit");
        details.put("actualSerializedBytesAtLeast", String.valueOf(actualBytes));
        details.put("maximumSerializedBytes", String.valueOf(DocumentSizeValidator.MAX_BYTES));
        return invalidRequest(
                subject(partialUpdate) + " size " + actualKiB
                        + " KiB exceeds the portable 390 KiB serialized-input limit. "
                        + "Reduce the write input to maintain portability across all providers.",
                operation, details, null);
    }

    private static String outputPath(JsonGenerator generator) {
        Deque<String> segments = new ArrayDeque<>();
        JsonStreamContext context = generator.getOutputContext();
        while (context != null && !context.inRoot()) {
            if (context.inObject() && context.getCurrentName() != null) {
                segments.addFirst(escapeJsonPointer(context.getCurrentName()));
            } else if (context.inArray() && context.getEntryCount() >= 0) {
                segments.addFirst(String.valueOf(context.getEntryCount()));
            }
            context = context.getParent();
        }
        return segments.isEmpty() ? null : "/" + String.join("/", segments);
    }

    private static String mappingPath(Throwable failure) {
        JsonMappingException mapping = findCause(failure, JsonMappingException.class);
        if (mapping == null || mapping.getPath().isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) {
                path.append('/').append(escapeJsonPointer(reference.getFieldName()));
            } else if (reference.getIndex() >= 0) {
                path.append('/').append(reference.getIndex());
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    private static <T extends Throwable> T findCause(
            Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private static MulticloudDbException normalizationFailure(
            String operation, Throwable failure) {
        return invalidRequest(
                subject(OperationNames.UPDATE.equals(operation))
                        + " could not be normalized to the portable JSON value model.",
                operation,
                Map.of("reason", "portable_value_normalization_failed"),
                failure);
    }

    private static final class PortableAnnotationIntrospector
            extends JacksonAnnotationIntrospector {
        private static final long serialVersionUID = 1L;

        @Override
        public Object findSerializer(Annotated annotated) {
            Class<?> rawClass = annotated.getRawType();
            if (Iterable.class.isAssignableFrom(rawClass)
                    && !Collection.class.isAssignableFrom(rawClass)
                    && !JsonNode.class.isAssignableFrom(rawClass)) {
                return PortableSerializers.REJECTING_ITERABLE_SERIALIZER;
            }
            return super.findSerializer(annotated);
        }
    }

    private static final class PortableSerializers extends SimpleSerializers {
        private static final long serialVersionUID = 1L;
        private static final JsonSerializer<Object> REJECTING_ITERABLE_SERIALIZER =
                new RejectingIterableSerializer();

        @Override
        public JsonSerializer<?> findSerializer(SerializationConfig config,
                JavaType type, BeanDescription beanDescription) {
            Class<?> rawClass = type.getRawClass();
            if (Iterable.class.isAssignableFrom(rawClass)
                    && !Collection.class.isAssignableFrom(rawClass)
                    && !JsonNode.class.isAssignableFrom(rawClass)) {
                return REJECTING_ITERABLE_SERIALIZER;
            }
            return super.findSerializer(config, type, beanDescription);
        }
    }

    private static final class RejectingIterableSerializer
            extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator generator,
                SerializerProvider serializers) throws IOException {
            if (value instanceof Collection<?> || value instanceof JsonNode) {
                JsonSerializer<Object> delegate =
                        serializers.findValueSerializer(value.getClass());
                delegate.serialize(value, generator, serializers);
                return;
            }
            throw new NonPortableValueException(
                    "non_portable_iterable", value.getClass().getSimpleName(),
                    outputPath(generator));
        }
    }

    private static final class PortableJsonGenerator extends JsonGeneratorDelegate {
        private PortableJsonGenerator(JsonGenerator delegate) {
            super(delegate, false);
        }

        @Override
        public void writeBinary(Base64Variant variant, byte[] data,
                int offset, int length) throws IOException {
            throw new NonPortableValueException(
                    NON_PORTABLE_BINARY_REASON, "byte[]", outputPath(this));
        }

        @Override
        public int writeBinary(Base64Variant variant, InputStream data,
                int dataLength) throws IOException {
            throw new NonPortableValueException(
                    NON_PORTABLE_BINARY_REASON, "binary", outputPath(this));
        }

        @Override
        public void writeEmbeddedObject(Object value) throws IOException {
            if (value instanceof byte[] || value instanceof ByteBuffer) {
                throw new NonPortableValueException(
                        NON_PORTABLE_BINARY_REASON, portableTypeName(value),
                        outputPath(this));
            }
            super.writeEmbeddedObject(value);
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        private final int maximumBytes;

        private BoundedOutputStream(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            output.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            ensureCapacity(length);
            output.write(values, offset, length);
        }

        private void ensureCapacity(int additionalBytes) throws IOException {
            if ((long) output.size() + additionalBytes > maximumBytes) {
                throw new SerializationSizeLimitException();
            }
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    private static final class SerializationSizeLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class NonPortableValueException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final String valueType;
        private final String valuePath;

        private NonPortableValueException(
                String reason, String valueType, String valuePath) {
            super(reason);
            this.reason = reason;
            this.valueType = valueType;
            this.valuePath = valuePath;
        }

        private String reason() {
            return reason;
        }

        private String valueType() {
            return valueType;
        }

        private String valuePath() {
            return valuePath;
        }

    }

    private interface TraversalStep { }

    private static final class ValuePath {
        private static final ValuePath ROOT = new ValuePath(null, null, -1);

        private final ValuePath parent;
        private final String fieldName;
        private final int index;

        private ValuePath(ValuePath parent, String fieldName, int index) {
            this.parent = parent;
            this.fieldName = fieldName;
            this.index = index;
        }

        private static ValuePath field(ValuePath parent, String fieldName) {
            return new ValuePath(parent, fieldName, -1);
        }

        private static ValuePath index(ValuePath parent, int index) {
            return new ValuePath(parent, null, index);
        }

        private String render() {
            if (this == ROOT) {
                return "/";
            }

            Deque<ValuePath> segments = new ArrayDeque<>();
            ValuePath current = this;
            while (current != ROOT) {
                segments.push(current);
                current = current.parent;
            }

            StringBuilder rendered = new StringBuilder();
            while (!segments.isEmpty()) {
                ValuePath segment = segments.pop();
                rendered.append('/');
                if (segment.fieldName != null) {
                    rendered.append(escapeJsonPointer(segment.fieldName));
                } else {
                    rendered.append(segment.index);
                }
            }
            return rendered.toString();
        }
    }

    private record PendingValue(
            Object value, ValuePath path, int depth) implements TraversalStep { }

    private record PendingChildren(
            Iterator<PendingValue> values) implements TraversalStep { }

    private record ExitContainer(Object value) implements TraversalStep { }


    private static String portableTypeName(Object value) {
        if (value instanceof byte[]) {
            return "byte[]";
        }
        if (value instanceof ByteBuffer) {
            return "ByteBuffer";
        }
        return value.getClass().getSimpleName();
    }

    static long rootFootprint(JsonNode root) {
        long bytes = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            bytes += utf8Bytes(field.getKey()) + valueFootprint(field.getValue());
        }
        return bytes;
    }

    private static long valueFootprint(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean()) {
            return 1;
        }
        if (value.isTextual()) {
            return utf8Bytes(value.textValue());
        }
        if (value.isNumber()) {
            return utf8Bytes(value.asText());
        }
        if (value.isArray()) {
            long bytes = CONTAINER_OVERHEAD_BYTES;
            for (JsonNode element : value) {
                bytes += NESTED_ELEMENT_OVERHEAD_BYTES + valueFootprint(element);
            }
            return bytes;
        }
        if (value.isObject()) {
            long bytes = CONTAINER_OVERHEAD_BYTES;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                bytes += NESTED_ELEMENT_OVERHEAD_BYTES + utf8Bytes(field.getKey())
                        + valueFootprint(field.getValue());
            }
            return bytes;
        }
        return utf8Bytes(value.asText());
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String escapeJsonPointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String subject(boolean partialUpdate) {
        return partialUpdate ? "Partial-update field map" : "Document";
    }

    private static MulticloudDbException invalidRequest(String message, String operation,
            Map<String, String> details, Throwable cause) {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message,
                null,
                operation,
                false,
                details);
        return cause == null ? new MulticloudDbException(error)
                : new MulticloudDbException(error, cause);
    }
}
