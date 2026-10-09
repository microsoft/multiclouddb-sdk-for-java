// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structural conversion after the existing native mapping, without mapper coercion. */
final class NativeDocuments {
    private NativeDocuments() {
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

    static MulticloudDbException invalidPayload(String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "Provider response cannot be represented as a Document value.",
                ProviderId.SPANNER, operation, false, Map.of("reason", "invalid_document_payload")));
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
