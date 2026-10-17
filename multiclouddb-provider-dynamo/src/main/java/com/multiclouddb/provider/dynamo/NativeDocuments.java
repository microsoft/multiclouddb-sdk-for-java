// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;

/** Direct native writes and structural response conversion with legacy numeric behavior. */
final class NativeDocuments {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NativeDocuments() {
    }

    static Map<String, AttributeValue> write(Document document) {
        return writeObject(Objects.requireNonNull(document, "document").root());
    }

    private static Map<String, AttributeValue> writeObject(ObjectValue object) {
        var fields = new LinkedHashMap<String, AttributeValue>();
        object.fields().forEach((name, child) -> fields.put(name, writeValue(child)));
        return fields;
    }

    private static AttributeValue writeValue(DocumentValue value) {
        if (value instanceof ObjectValue object) return AttributeValue.fromM(writeObject(object));
        if (value instanceof ArrayValue array) {
            var items = new ArrayList<AttributeValue>(array.values().size());
            array.values().forEach(child -> items.add(writeValue(child)));
            return AttributeValue.fromL(items);
        }
        if (value instanceof StringValue string) return AttributeValue.fromS(string.value());
        if (value instanceof BooleanValue bool) return AttributeValue.fromBool(bool.value());
        if (value instanceof NumberValue number) {
            Number retained = number.value();
            // Only decimals need the pinned mapper's scale/zero normalization.
            if (retained instanceof BigDecimal) {
                JsonNode scalar = MAPPER.valueToTree(retained);
                retained = scalar.numberValue();
            }
            return AttributeValue.fromN(retained.toString());
        }
        return AttributeValue.fromNul(true);
    }

    static Document document(JsonNode node) {
        try {
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected document object.");
            return Document.of((ObjectValue) value(node, 0));
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
                ProviderId.DYNAMO, operation, false, Map.of("reason", "invalid_document_payload")));
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
