// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamoItemMapperTest {
    @Test
    void neutralValuesRoundTripWithoutAliasingNativeOrCallerContainers() {
        List<Object> children = new ArrayList<>(Arrays.asList(null, false, Map.of("n", 2)));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", "Alice");
        document.put("count", 42);
        document.put("active", true);
        document.put("missing", null);
        document.put("nested", children);
        Map<String, AttributeValue> nativeItem = DynamoItemMapper.mapToAttributeMap(document);
        Map<String, Object> back = DynamoItemMapper.attributeMapToMap(nativeItem);
        assertEquals(document, back);
        assertEquals("42", nativeItem.get("count").n());
        assertTrue(nativeItem.get("missing").nul());
        children.clear();
        assertEquals(3, assertInstanceOf(List.class, back.get("nested")).size());
        assertInstanceOf(List.class, back.get("nested")).clear();
        assertEquals(3, nativeItem.get("nested").l().size());
        back.put("extra", "mutable");
        assertFalse(nativeItem.containsKey("extra"));
    }

    @Test
    void preservesLegacyNumericTextAndReadRepresentations() {
        for (Number number : List.of((byte) 1, (short) 2, 3, Long.MIN_VALUE,
                BigInteger.TEN.pow(100), 0.1F, -0.0D, new BigDecimal("1.00"),
                new BigDecimal("0.000"), new BigDecimal("1E+20"))) {
            Map<String, Object> input = Map.of("n", number);
            String expected = number instanceof BigDecimal d ? d.stripTrailingZeros().toString() : number.toString();
            assertEquals(expected, DynamoItemMapper.mapToAttributeMap(input).get("n").n());
            assertSame(number, input.get("n"));
        }
        assertInstanceOf(Integer.class, readNumber("42"));
        assertInstanceOf(Long.class, readNumber("2147483648"));
        assertInstanceOf(Double.class, readNumber("1.25"));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits((Double) readNumber("-0.0")));
        assertThrows(NumberFormatException.class, () -> readNumber("1E+3"));
        assertThrows(NumberFormatException.class, () -> readNumber("9223372036854775808"));
    }

    @Test
    void preservesNativeSetsAndUnsupportedBinaryFallback() {
        Map<String, AttributeValue> item = Map.of(
                "strings", AttributeValue.fromSs(List.of("a", "b")),
                "numbers", AttributeValue.fromNs(List.of("2", "1.5")),
                "binary", AttributeValue.fromB(software.amazon.awssdk.core.SdkBytes.fromByteArray(new byte[]{1})));
        Map<String, Object> result = DynamoItemMapper.attributeMapToMap(item);
        assertEquals(List.of("a", "b"), result.get("strings"));
        assertEquals(List.of(2.0, 1.5), result.get("numbers"));
        assertTrue(result.containsKey("binary"));
        assertNull(result.get("binary"));
    }

    @Test
    void queryParametersKeepTheirSeparateLegacyCoercion() {
        assertEquals("hello", DynamoItemMapper.toAttributeValue("hello").s());
        assertEquals("42", DynamoItemMapper.toAttributeValue(42).n());
        assertEquals("1.00", DynamoItemMapper.toAttributeValue(new BigDecimal("1.00")).n());
        assertTrue(DynamoItemMapper.toAttributeValue(true).bool());
        assertTrue(DynamoItemMapper.toAttributeValue(null).nul());
        assertEquals("[a]", DynamoItemMapper.toAttributeValue(List.of("a")).s());
    }

    private static Object readNumber(String number) {
        return DynamoItemMapper.attributeMapToMap(Map.of("n", AttributeValue.fromN(number))).get("n");
    }
}
