// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Detached native Map requests and responses, without an SDK-owned JSON tree. */
final class NativeDocuments {
    private NativeDocuments() {
    }

    static Map<String, Object> write(Map<String, Object> document) {
        return object(Objects.requireNonNull(document, "document"), true);
    }

    static Map<String, Object> document(Object value) {
        try {
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected document object.");
            Map<String, Object> fields = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid document key.");
                if (!CosmosConstants.SYSTEM_FIELDS.contains(key)) {
                    fields.put(key, copy(entry.getValue(), false));
                }
            }
            return fields;
        } catch (IllegalArgumentException invalid) {
            throw invalidPayload("read");
        }
    }

    static Object changeData(Object value) {
        try {
            return copy(value, false);
        } catch (IllegalArgumentException invalid) {
            throw invalidPayload("readChanges");
        }
    }

    static Map<String, Object> queryRow(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Query result is not an object row.");
        }
        return object(map, false);
    }

    private static Map<String, Object> object(Map<?, ?> map, boolean writing) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name)) throw new IllegalArgumentException("Invalid document key.");
            fields.put(name, copy(entry.getValue(), writing));
        }
        return fields;
    }

    private static Object copy(Object value, boolean writing) {
        if (value instanceof Map<?, ?> map) return object(map, writing);
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            for (Object item : list) items.add(copy(item, writing));
            return items;
        }
        if (writing && value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported native value.");
    }

    static Object get(Object value, String name) {
        return value instanceof Map<?, ?> map ? map.get(name) : null;
    }

    static String text(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean ? value.toString() : "";
    }

    static long asLong(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        if (value instanceof String text) {
            String trimmed = text.trim();
            try {
                if (trimmed.matches("[+-]?[0-9]+")) return Long.parseLong(trimmed);
                return (long) Double.parseDouble(trimmed);
            } catch (NumberFormatException invalid) {
                return fallback;
            }
        }
        return fallback;
    }

    private static MulticloudDbException invalidPayload(String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "Provider response cannot be represented as neutral data.",
                ProviderId.COSMOS, operation, false, Map.of("reason", "invalid_document_payload")));
    }
}
