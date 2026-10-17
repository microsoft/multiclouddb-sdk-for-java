// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.math.BigInteger;

/** Direct native writes and structural response conversion with legacy numeric behavior. */
final class NativeDocuments {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NativeDocuments() {
    }

    static ObjectNode write(Document document) {
        return (ObjectNode) writeValue(Objects.requireNonNull(document, "document").root());
    }

    private static JsonNode writeValue(DocumentValue value) {
        if (value instanceof ObjectValue object) {
            ObjectNode node = MAPPER.createObjectNode();
            object.fields().forEach((name, child) -> node.set(name, writeValue(child)));
            return node;
        }
        if (value instanceof ArrayValue array) {
            var node = MAPPER.createArrayNode();
            array.values().forEach(child -> node.add(writeValue(child)));
            return node;
        }
        if (value instanceof StringValue string) return MAPPER.getNodeFactory().textNode(string.value());
        if (value instanceof BooleanValue bool) return MAPPER.getNodeFactory().booleanNode(bool.value());
        if (value instanceof NumberValue number) {
            Number retained = number.value();
            var factory = MAPPER.getNodeFactory();
            if (retained instanceof Byte || retained instanceof Short || retained instanceof Integer)
                return factory.numberNode(retained.intValue());
            if (retained instanceof Long) return factory.numberNode(retained.longValue());
            if (retained instanceof Float) return factory.numberNode(retained.floatValue());
            if (retained instanceof Double) return factory.numberNode(retained.doubleValue());
            if (retained instanceof BigInteger integer) return factory.numberNode(integer);
            // BigDecimal keeps the pinned Map-to-tree normalization of scale and zero.
            return MAPPER.valueToTree(retained);
        }
        return MAPPER.getNodeFactory().nullNode();
    }

    static Document document(JsonNode node) {
        try {
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected document object.");
            var fields = new LinkedHashMap<String, DocumentValue>();
            var iterator = node.fields();
            while (iterator.hasNext()) {
                var field = iterator.next();
                if (!CosmosConstants.SYSTEM_FIELDS.contains(field.getKey())) {
                    fields.put(field.getKey(), value(field.getValue(), 1));
                }
            }
            return Document.of(ObjectValue.of(fields));
        } catch (IllegalArgumentException invalid) {
            throw invalidPayload("read");
        }
    }

    static DocumentValue changeData(JsonNode node) {
        try {
            return value(node, 0);
        } catch (IllegalArgumentException invalid) {
            throw invalidPayload("readChanges");
        }
    }

    private static MulticloudDbException invalidPayload(String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "Provider response cannot be represented as a Document value.",
                ProviderId.COSMOS, operation, false, Map.of("reason", "invalid_document_payload")));
    }

    private static DocumentValue value(JsonNode node, int depth) {
        if (node == null) return null;
        if ((node.isObject() || node.isArray()) && ++depth > 128) {
            throw new IllegalArgumentException("Provider payload exceeds document nesting limit.");
        }
        switch (node.getNodeType()) {
            case NULL: return NullValue.INSTANCE;
            case STRING: return new StringValue(node.textValue());
            case BOOLEAN: return new BooleanValue(node.booleanValue());
            case NUMBER: return NumberValue.of(node.numberValue());
            case ARRAY:
                var items = new ArrayList<DocumentValue>();
                for (JsonNode child : node) items.add(value(child, depth));
                return ArrayValue.of(items);
            case OBJECT:
                var fields = new LinkedHashMap<String, DocumentValue>();
                var iterator = node.fields();
                while (iterator.hasNext()) {
                    var field = iterator.next();
                    fields.put(field.getKey(), value(field.getValue(), depth));
                }
                return ObjectValue.of(fields);
            default: throw new IllegalArgumentException("Unsupported provider value kind.");
        }
    }
}
