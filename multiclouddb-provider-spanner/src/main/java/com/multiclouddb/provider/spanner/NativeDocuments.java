// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;

/** Streaming nested-value JSON writes and structural conversion after native reads. */
final class NativeDocuments {
    private static final ObjectWriter JSON_WRITER = new ObjectMapper().registerModule(
            new SimpleModule().addSerializer(DocumentValue.class, new JsonSerializer<>() {
                @Override
                public void serialize(DocumentValue value, JsonGenerator generator, SerializerProvider provider)
                        throws IOException {
                    writeJson(value, generator, provider);
                }
            })).writerFor(DocumentValue.class);

    private NativeDocuments() {
    }

    static String json(DocumentValue value) throws JsonProcessingException {
        return JSON_WRITER.writeValueAsString(value);
    }

    private static void writeJson(DocumentValue value, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        if (value instanceof ObjectValue object) {
            generator.writeStartObject();
            for (var field : object.fields().entrySet()) {
                generator.writeFieldName(field.getKey());
                writeJson(field.getValue(), generator, provider);
            }
            generator.writeEndObject();
        } else if (value instanceof ArrayValue array) {
            generator.writeStartArray();
            for (DocumentValue child : array.values()) writeJson(child, generator, provider);
            generator.writeEndArray();
        } else if (value instanceof StringValue string) {
            generator.writeString(string.value());
        } else if (value instanceof NumberValue number) {
            provider.defaultSerializeValue(number.value(), generator);
        } else if (value instanceof BooleanValue bool) {
            generator.writeBoolean(bool.value());
        } else {
            generator.writeNull();
        }
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
