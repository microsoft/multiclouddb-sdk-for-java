// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentJsonSizeTest {
    private final ObjectMapper legacy = new ObjectMapper();

    @Test
    void countsTheOriginalUtf8EscapingProfile() throws Exception {
        for (String text : List.of("ASCII", "\"\\\b\f\n\r\t\u0000\u001f", "\u00e9\u0800\u2028\u2029",
                "\ud83d\ude00", "\ud800", "\udc00", "</>&", "\\u1234")) {
            Document document = Document.builder().put(text, new StringValue(text)).build();
            assertEquals(legacySize(Map.of(text, text)),
                    DocumentJsonSize.measure(document, Long.MAX_VALUE), "string profile");
        }
    }

    @Test
    void retainsTheOriginalNumericSizeProfileWithoutChangingStoredScale() throws Exception {
        for (Number number : List.of((byte) 1, (short) 2, 3, Long.MIN_VALUE, BigInteger.TEN.pow(100),
                0.1F, -0.0D, Double.MIN_VALUE, Double.MAX_VALUE,
                new BigDecimal("1.00"), new BigDecimal("0.000"), new BigDecimal("1E+20"))) {
            NumberValue value = NumberValue.of(number);
            Document document = Document.builder().put("n", value).build();
            assertEquals(legacySize(Map.of("n", number)), DocumentJsonSize.measure(document, Long.MAX_VALUE),
                    number.getClass().getSimpleName() + " " + number);
            assertSame(number, value.value());
        }
    }

    @Test
    void countsNestedContainersAndStopsAtExactByteBoundary() throws Exception {
        Document document = Document.builder().put("a", ArrayValue.of(List.of(
                NullValue.INSTANCE, new BooleanValue(false), ObjectValue.of(Map.of())))).build();
        Map<String, Object> original = Map.of("a", java.util.Arrays.asList(null, false, Map.of()));
        long expected = legacySize(original);
        assertEquals(expected, DocumentJsonSize.measure(document, expected));
        assertThrows(IllegalArgumentException.class, () -> DocumentJsonSize.measure(document, expected - 1));
    }

    @Test
    void preservesTheActual399KiBLimitAtBelowEqualAndAbove() {
        int overhead = 11; // {"body":""}
        for (int bytes : new int[]{DocumentSizeValidator.MAX_BYTES - 1, DocumentSizeValidator.MAX_BYTES}) {
            Document document = Document.builder()
                    .put("body", new StringValue("x".repeat(bytes - overhead))).build();
            assertEquals(bytes, DocumentJsonSize.measure(document, DocumentSizeValidator.MAX_BYTES));
            assertDoesNotThrow(() -> DocumentSizeValidator.validate(document, "create"));
        }
        Document tooLarge = Document.builder().put("body",
                new StringValue("x".repeat(DocumentSizeValidator.MAX_BYTES - overhead + 1))).build();
        MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(tooLarge, "create"));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
        assertEquals("create", failure.error().operation());
        assertTrue(failure.error().message().contains("at least " + (DocumentSizeValidator.MAX_BYTES + 1)));
        assertTrue(failure.error().message().contains("Maximum logical JSON size"));
    }

    @Test
    void sizeDiagnosticReportsACutoffLowerBoundWithoutTraversingTheWholeDocument() {
        Document document = Document.builder().put("body", new StringValue("x".repeat(2_000_000)))
                .put("later", new StringValue("y".repeat(2_000_000))).build();
        MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, "create"));
        assertTrue(failure.error().message().contains("at least " + (DocumentSizeValidator.MAX_BYTES + 1)));
        assertFalse(failure.error().message().contains("2000000"));
        assertFalse(failure.error().message().contains("4000000"), "counter must stop before later fields");
    }

    private long legacySize(Map<String, Object> document) throws Exception {
        return legacy.writeValueAsBytes(legacy.valueToTree(document)).length;
    }
}
