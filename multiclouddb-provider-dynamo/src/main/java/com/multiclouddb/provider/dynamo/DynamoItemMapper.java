// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps neutral document values directly to DynamoDB attributes and back. */
public final class DynamoItemMapper {
    private DynamoItemMapper() {
    }

    /** Converts a document to a detached native attribute map. */
    public static Map<String, AttributeValue> mapToAttributeMap(Map<String, Object> document) {
        return objectToAttributes(document);
    }

    private static Map<String, AttributeValue> objectToAttributes(Map<?, ?> document) {
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        if (document == null) return result;
        for (var entry : document.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("Document keys must be non-null strings.");
            }
            result.put(name, documentValueToAttribute(entry.getValue()));
        }
        return result;
    }

    private static AttributeValue documentValueToAttribute(Object value) {
        if (value == null) return AttributeValue.fromNul(true);
        if (value instanceof String text) return AttributeValue.fromS(text);
        if (value instanceof Boolean bool) return AttributeValue.fromBool(bool);
        if (value instanceof Number number) {
            // Retain the released Map-to-tree decimal normalization, not a new numeric policy.
            String text = number instanceof BigDecimal decimal
                    ? decimal.stripTrailingZeros().toString() : number.toString();
            return AttributeValue.fromN(text);
        }
        if (value instanceof Map<?, ?> map) return AttributeValue.fromM(objectToAttributes(map));
        if (value instanceof List<?> list) {
            List<AttributeValue> items = new ArrayList<>(list.size());
            for (Object item : list) items.add(documentValueToAttribute(item));
            return AttributeValue.fromL(items);
        }
        throw new IllegalArgumentException("Unsupported document value.");
    }

    /** Converts native attributes into detached mutable neutral Maps and Lists. */
    public static Map<String, Object> attributeMapToMap(Map<String, AttributeValue> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : item.entrySet()) {
            result.put(entry.getKey(), attributeToValue(entry.getValue()));
        }
        return result;
    }

    private static Object attributeToValue(AttributeValue attribute) {
        if (attribute.type() == AttributeValue.Type.S && attribute.s() != null) return attribute.s();
        if (attribute.type() == AttributeValue.Type.N && attribute.n() != null) {
            String text = attribute.n();
            // Preserve the existing parser, including exponent/overflow failures.
            if (text.contains(".")) return Double.parseDouble(text);
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException integerOverflow) {
                return Long.parseLong(text);
            }
        }
        if (attribute.type() == AttributeValue.Type.BOOL) return attribute.bool();
        if (attribute.type() == AttributeValue.Type.NUL && Boolean.TRUE.equals(attribute.nul())) return null;
        if (attribute.type() == AttributeValue.Type.L && attribute.hasL()) {
            List<Object> items = new ArrayList<>(attribute.l().size());
            for (AttributeValue item : attribute.l()) items.add(attributeToValue(item));
            return items;
        }
        if (attribute.type() == AttributeValue.Type.M && attribute.hasM()) {
            return attributeMapToMap(attribute.m());
        }
        if (attribute.type() == AttributeValue.Type.SS && attribute.hasSs()) {
            return new ArrayList<>(attribute.ss());
        }
        if (attribute.type() == AttributeValue.Type.NS && attribute.hasNs()) {
            List<Object> items = new ArrayList<>(attribute.ns().size());
            for (String text : attribute.ns()) items.add(Double.parseDouble(text));
            return items;
        }
        // Retain the existing unsupported native-kind fallback.
        return null;
    }

    /** Converts a query parameter using the existing parameter-coercion policy. */
    public static AttributeValue toAttributeValue(Object value) {
        if (value == null) return AttributeValue.fromNul(true);
        if (value instanceof String text) return AttributeValue.fromS(text);
        if (value instanceof Number number) return AttributeValue.fromN(number.toString());
        if (value instanceof Boolean bool) return AttributeValue.fromBool(bool);
        return AttributeValue.fromS(value.toString());
    }
}
