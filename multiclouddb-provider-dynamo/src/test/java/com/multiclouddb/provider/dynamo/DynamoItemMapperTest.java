// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.core.JsonParser.NumberType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.fasterxml.jackson.core.JsonParser.NumberType.*;
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

    @ParameterizedTest
    @MethodSource("nativeNumbers")
    void nativeNumberRoundTrip(String value, NumberType expectedType) {
        JsonNode decoded = DynamoItemMapper.attributeValueToJsonNode(AttributeValue.fromN(value));

        assertEquals(expectedType, decoded.numberType());
        assertNumericValue(value, decoded.decimalValue());
        AttributeValue encoded = DynamoItemMapper.jsonNodeToAttributeValue(decoded);
        assertEquals(AttributeValue.Type.N, encoded.type());
        assertNumericValue(value, new BigDecimal(encoded.n()));
    }

    @ParameterizedTest
    @MethodSource("nativeNumbers")
    void nativeNumberSetRoundTrip(String value, NumberType expectedType) {
        JsonNode decoded = DynamoItemMapper.attributeValueToJsonNode(
                AttributeValue.fromNs(List.of(value)));

        assertTrue(decoded.isArray());
        assertEquals(1, decoded.size());
        assertEquals(expectedType, decoded.get(0).numberType());
        assertNumericValue(value, decoded.get(0).decimalValue());
        AttributeValue encoded = DynamoItemMapper.jsonNodeToAttributeValue(decoded);
        assertEquals(AttributeValue.Type.L, encoded.type());
        assertNumericValue(value, new BigDecimal(encoded.l().get(0).n()));
    }

    @ParameterizedTest
    @MethodSource("nativeNumbers")
    void nativeNumbersSurviveMapAndNestedContainerRoundTrips(String value, NumberType expectedType) {
        AttributeValue number = AttributeValue.fromN(value);
        Map<String, AttributeValue> source = Map.of(
                "value", number,
                "nested", AttributeValue.fromM(Map.of(
                        "list", AttributeValue.fromL(List.of(number)),
                        "set", AttributeValue.fromNs(List.of(value)))));

        Map<String, Object> decoded = DynamoItemMapper.attributeMapToMap(source);
        Number decodedNumber = assertInstanceOf(Number.class, decoded.get("value"));
        assertEquals(expectedType, MAPPER.valueToTree(decodedNumber).numberType());
        assertNumericValue(value, new BigDecimal(decodedNumber.toString()));

        Map<String, AttributeValue> encoded = DynamoItemMapper.mapToAttributeMap(decoded);
        assertNumericValue(value, new BigDecimal(encoded.get("value").n()));
        Map<String, AttributeValue> nested = encoded.get("nested").m();
        assertNumericValue(value, new BigDecimal(nested.get("list").l().get(0).n()));
        assertNumericValue(value, new BigDecimal(nested.get("set").l().get(0).n()));
    }

    @Test
    void mixedNumberSetPreservesEachMembersValueAndType() {
        List<String> values = List.of(
                "42", "2147483648", "9223372036854775808",
                "0.12345678901234567890123456789012345678", "-1e-130");
        List<NumberType> types = List.of(INT, LONG, BIG_INTEGER, BIG_DECIMAL, BIG_DECIMAL);

        JsonNode decoded = DynamoItemMapper.attributeValueToJsonNode(AttributeValue.fromNs(values));
        assertEquals(values.size(), decoded.size());
        List<AttributeValue> encoded = DynamoItemMapper.jsonNodeToAttributeValue(decoded).l();
        for (int i = 0; i < values.size(); i++) {
            assertEquals(types.get(i), decoded.get(i).numberType());
            assertNumericValue(values.get(i), decoded.get(i).decimalValue());
            assertNumericValue(values.get(i), new BigDecimal(encoded.get(i).n()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "NaN", "Infinity", "1e"})
    void invalidNativeNumbersAreRejected(String value) {
        assertThrows(NumberFormatException.class,
                () -> DynamoItemMapper.attributeValueToJsonNode(AttributeValue.fromN(value)));
        assertThrows(NumberFormatException.class,
                () -> DynamoItemMapper.attributeValueToJsonNode(AttributeValue.fromNs(List.of(value))));
    }

    private static Stream<Arguments> nativeNumbers() {
        return Stream.of(
                Arguments.of("0", INT),
                Arguments.of("-0", INT),
                Arguments.of("2147483647", INT),
                Arguments.of("-2147483648", INT),
                Arguments.of("2147483648", LONG),
                Arguments.of("-2147483649", LONG),
                Arguments.of("9007199254740993", LONG),
                Arguments.of("-9007199254740993", LONG),
                Arguments.of("9223372036854775807", LONG),
                Arguments.of("-9223372036854775808", LONG),
                Arguments.of("9223372036854775808", BIG_INTEGER),
                Arguments.of("-9223372036854775809", BIG_INTEGER),
                Arguments.of("99999999999999999999999999999999999999", BIG_INTEGER),
                Arguments.of("-99999999999999999999999999999999999999", BIG_INTEGER),
                Arguments.of("0.0", BIG_DECIMAL),
                Arguments.of("1.2300", BIG_DECIMAL),
                Arguments.of("0.12345678901234567890123456789012345678", BIG_DECIMAL),
                Arguments.of("-12345678901234567890.123456789012345678", BIG_DECIMAL),
                Arguments.of("1e3", BIG_DECIMAL),
                Arguments.of("-1E+3", BIG_DECIMAL),
                Arguments.of("1e-130", BIG_DECIMAL),
                Arguments.of("-1E-130", BIG_DECIMAL),
                Arguments.of("9.9999999999999999999999999999999999999E+125", BIG_DECIMAL),
                Arguments.of("-9.9999999999999999999999999999999999999e+125", BIG_DECIMAL));
    }

    private static void assertNumericValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected numeric value " + expected + " but got " + actual);
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
