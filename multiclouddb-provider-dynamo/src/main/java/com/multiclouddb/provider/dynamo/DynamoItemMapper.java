// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.*;

/**
 * Bidirectional mapper between Jackson {@link JsonNode} and DynamoDB
 * {@link AttributeValue}.
 */
public final class DynamoItemMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private DynamoItemMapper() {
    }

    /**
     * Convert a JsonNode (object) into a DynamoDB attribute map.
     */
    public static Map<String, AttributeValue> jsonNodeToAttributeMap(JsonNode node) {
        Map<String, AttributeValue> map = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            map.put(field.getKey(), jsonNodeToAttributeValue(field.getValue()));
        }
        return map;
    }

    /**
     * Convert a single JsonNode value into a DynamoDB AttributeValue.
     */
    public static AttributeValue jsonNodeToAttributeValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return AttributeValue.fromNul(true);
        }
        if (value.isTextual()) {
            return AttributeValue.fromS(value.textValue());
        }
        if (value.isBoolean()) {
            return AttributeValue.fromBool(value.booleanValue());
        }
        if (value.isNumber()) {
            return AttributeValue.fromN(value.numberValue().toString());
        }
        if (value.isArray()) {
            List<AttributeValue> list = new ArrayList<>();
            for (JsonNode element : value) {
                list.add(jsonNodeToAttributeValue(element));
            }
            return AttributeValue.fromL(list);
        }
        if (value.isObject()) {
            Map<String, AttributeValue> nested = jsonNodeToAttributeMap(value);
            return AttributeValue.fromM(nested);
        }
        return AttributeValue.fromS(value.toString());
    }

    /**
     * Convert a DynamoDB attribute map back to a JsonNode.
     */
    public static JsonNode attributeMapToJsonNode(Map<String, AttributeValue> item) {
        ObjectNode node = MAPPER.createObjectNode();
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            node.set(entry.getKey(), attributeValueToJsonNode(entry.getValue()));
        }
        return node;
    }

    /**
     * Convert a single DynamoDB AttributeValue to a JsonNode.
     */
    public static JsonNode attributeValueToJsonNode(AttributeValue av) {
        if (av.s() != null && av.type() == AttributeValue.Type.S) {
            return TextNode.valueOf(av.s());
        }
        if (av.n() != null && av.type() == AttributeValue.Type.N) {
            String numStr = av.n();
            if (numStr.contains(".")) {
                return new DoubleNode(Double.parseDouble(numStr));
            }
            try {
                return new IntNode(Integer.parseInt(numStr));
            } catch (NumberFormatException e) {
                return new LongNode(Long.parseLong(numStr));
            }
        }
        if (av.type() == AttributeValue.Type.BOOL) {
            return BooleanNode.valueOf(av.bool());
        }
        if (av.type() == AttributeValue.Type.NUL && Boolean.TRUE.equals(av.nul())) {
            return NullNode.getInstance();
        }
        if (av.hasL() && av.type() == AttributeValue.Type.L) {
            ArrayNode array = MAPPER.createArrayNode();
            for (AttributeValue element : av.l()) {
                array.add(attributeValueToJsonNode(element));
            }
            return array;
        }
        if (av.hasM() && av.type() == AttributeValue.Type.M) {
            return attributeMapToJsonNode(av.m());
        }
        if (av.hasSs() && av.type() == AttributeValue.Type.SS) {
            ArrayNode array = MAPPER.createArrayNode();
            for (String s : av.ss()) {
                array.add(TextNode.valueOf(s));
            }
            return array;
        }
        if (av.hasNs() && av.type() == AttributeValue.Type.NS) {
            ArrayNode array = MAPPER.createArrayNode();
            for (String n : av.ns()) {
                array.add(new DoubleNode(Double.parseDouble(n)));
            }
            return array;
        }
        // Fallback
        return NullNode.getInstance();
    }

    /**
     * Convert a Java Object to an AttributeValue (for query parameters).
     */
    public static AttributeValue toAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.fromNul(true);
        }
        if (value instanceof String s) {
            return AttributeValue.fromS(s);
        }
        if (value instanceof Number n) {
            return AttributeValue.fromN(n.toString());
        }
        if (value instanceof Boolean b) {
            return AttributeValue.fromBool(b);
        }
        return AttributeValue.fromS(value.toString());
    }

    /**
     * Convert a caller-supplied partial-update field value into an {@link AttributeValue},
     * preserving null, scalar, map, and list shapes without stringifying structured values.
     * <p>
     * Unlike {@link #toAttributeValue(Object)} (which is for flat query parameters and
     * stringifies containers), this routes through Jackson so a {@code Map} becomes a DynamoDB
     * {@code M}, a {@code List} becomes an {@code L}, and {@code null} becomes {@code NULL}.
     * This is the conversion used to build partial-update {@code SET} expression values.
     *
     * @param value the field value (may be {@code null}, scalar, map, or list)
     * @return the structured DynamoDB attribute value
     */
    public static AttributeValue objectToAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.fromNul(true);
        }
        return jsonNodeToAttributeValue(MAPPER.valueToTree(value));
    }

    /**
     * Convert a caller-supplied {@code Map<String, Object>} document into a
     * DynamoDB attribute map. Uses Jackson internally to handle nested structures.
     */
    public static Map<String, AttributeValue> mapToAttributeMap(Map<String, Object> document) {
        if (document == null) return new LinkedHashMap<>();
        JsonNode node = MAPPER.valueToTree(document);
        return jsonNodeToAttributeMap(node);
    }

    /**
     * Convert a DynamoDB attribute map to a plain {@code Map<String, Object>}.
     * Uses Jackson internally to handle nested structures.
     */
    public static Map<String, Object> attributeMapToMap(Map<String, AttributeValue> item) {
        JsonNode node = attributeMapToJsonNode(item);
        return MAPPER.convertValue(node, MAP_TYPE);
    }
}
