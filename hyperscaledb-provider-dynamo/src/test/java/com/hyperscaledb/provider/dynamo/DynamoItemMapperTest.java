// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamoItemMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stringRoundTrip() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", "Alice");

        Map<String, AttributeValue> map = DynamoItemMapper.jsonNodeToAttributeMap(node);
        assertEquals("Alice", map.get("name").s());

        JsonNode back = DynamoItemMapper.attributeMapToJsonNode(map);
        assertEquals("Alice", back.get("name").asText());
    }

    @Test
    void numberRoundTrip() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("count", 42);

        Map<String, AttributeValue> map = DynamoItemMapper.jsonNodeToAttributeMap(node);
        assertEquals("42", map.get("count").n());

        JsonNode back = DynamoItemMapper.attributeMapToJsonNode(map);
        assertEquals(42, back.get("count").asInt());
    }

    @Test
    void booleanRoundTrip() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("active", true);

        Map<String, AttributeValue> map = DynamoItemMapper.jsonNodeToAttributeMap(node);
        assertTrue(map.get("active").bool());

        JsonNode back = DynamoItemMapper.attributeMapToJsonNode(map);
        assertTrue(back.get("active").asBoolean());
    }

    @Test
    void nullRoundTrip() {
        ObjectNode node = MAPPER.createObjectNode();
        node.putNull("missing");

        Map<String, AttributeValue> map = DynamoItemMapper.jsonNodeToAttributeMap(node);
        assertTrue(map.get("missing").nul());

        JsonNode back = DynamoItemMapper.attributeMapToJsonNode(map);
        assertTrue(back.get("missing").isNull());
    }

    @Test
    void nestedObjectRoundTrip() {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("street", "123 Main St");
        ObjectNode node = MAPPER.createObjectNode();
        node.set("address", inner);

        Map<String, AttributeValue> map = DynamoItemMapper.jsonNodeToAttributeMap(node);
        Map<String, AttributeValue> nested = map.get("address").m();
        assertEquals("123 Main St", nested.get("street").s());

        JsonNode back = DynamoItemMapper.attributeMapToJsonNode(map);
        assertEquals("123 Main St", back.get("address").get("street").asText());
    }

    @Test
    void arrayRoundTrip() {
        ObjectNode node = MAPPER.createObjectNode();
        node.putArray("tags").add("a").add("b").add("c");

        Map<String, AttributeValue> map = DynamoItemMapper.jsonNodeToAttributeMap(node);
        assertEquals(3, map.get("tags").l().size());

        JsonNode back = DynamoItemMapper.attributeMapToJsonNode(map);
        assertEquals(3, back.get("tags").size());
        assertEquals("b", back.get("tags").get(1).asText());
    }

    @Test
    void toAttributeValueFromJavaTypes() {
        assertEquals("hello", DynamoItemMapper.toAttributeValue("hello").s());
        assertEquals("42", DynamoItemMapper.toAttributeValue(42).n());
        assertTrue(DynamoItemMapper.toAttributeValue(true).bool());
        assertTrue(DynamoItemMapper.toAttributeValue(null).nul());
    }
}
