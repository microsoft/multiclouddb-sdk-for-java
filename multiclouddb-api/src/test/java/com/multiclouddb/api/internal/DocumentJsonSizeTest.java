// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Byte fixtures captured from the released API's Jackson 2.18.6 Map-to-tree encoder. */
class DocumentJsonSizeTest {

    @Test
    void countsTheOriginalUtf8EscapingProfile() throws Exception {
        int[] expected = {17, 59, 29, 31, 19, 19, 15, 21};
        int index = 0;
        for (String text : List.of("ASCII", "\"\\\b\f\n\r\t\u0000\u001f", "\u00e9\u0800\u2028\u2029",
                "\ud83d\ude00", "\ud800", "\udc00", "</>&", "\\u1234")) {
            Map<String, Object> document = Map.of(text, text);
            assertEquals(expected[index++],
                    DocumentJsonSize.measure(document, Long.MAX_VALUE), "string profile");
        }
    }

    @Test
    void supplementaryCharactersRetainLegacyByteEncoderSurrogateEscapes() throws Exception {
        Map<String, Object> fields = Map.of("v", "\ud83d\ude00");
        assertEquals(20, DocumentJsonSize.measure(fields, Long.MAX_VALUE));
    }

    @Test
    void retainsTheOriginalNumericSizeProfileWithoutChangingStoredScale() throws Exception {
        int[] expected = {7, 7, 7, 26, 107, 9, 10, 14, 28, 7, 7, 11};
        int index = 0;
        for (Number number : List.of((byte) 1, (short) 2, 3, Long.MIN_VALUE, BigInteger.TEN.pow(100),
                0.1F, -0.0D, Double.MIN_VALUE, Double.MAX_VALUE,
                new BigDecimal("1.00"), new BigDecimal("0.000"), new BigDecimal("1E+20"))) {
            Map<String, Object> document = Map.of("n", number);
            assertEquals(expected[index++], DocumentJsonSize.measure(document, Long.MAX_VALUE),
                    number.getClass().getSimpleName() + " " + number);
            assertSame(number, document.get("n"));
        }
    }

    @Test
    void countsNestedContainersAndStopsAtExactByteBoundary() throws Exception {
        Map<String, Object> original = Map.of("a", java.util.Arrays.asList(null, false, Map.of()));
        long expected = "{\"a\":[null,false,{}]}".length();
        assertEquals(expected, DocumentJsonSize.measure(original, expected));
        assertThrows(IllegalArgumentException.class, () -> DocumentJsonSize.measure(original, expected - 1));
    }

    @Test
    void preservesTheActual399KiBLimitAtBelowEqualAndAbove() {
        int overhead = 11; // {"body":""}
        for (int bytes : new int[]{DocumentSizeValidator.MAX_BYTES - 1, DocumentSizeValidator.MAX_BYTES}) {
            Map<String, Object> document = Map.of("body", "x".repeat(bytes - overhead));
            assertEquals(bytes, DocumentJsonSize.measure(document, DocumentSizeValidator.MAX_BYTES));
            assertDoesNotThrow(() -> DocumentSizeValidator.validate(document, "create"));
        }
        Map<String, Object> tooLarge = Map.of("body",
                "x".repeat(DocumentSizeValidator.MAX_BYTES - overhead + 1));
        MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(tooLarge, "create"));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
        assertEquals("create", failure.error().operation());
        assertTrue(failure.error().message().contains("at least " + (DocumentSizeValidator.MAX_BYTES + 1)));
        assertTrue(failure.error().message().contains("Maximum logical JSON size"));
    }

    @Test
    void sizeDiagnosticReportsACutoffLowerBoundWithoutTraversingTheWholeDocument() {
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("body", "x".repeat(2_000_000));
        document.put("later", "y".repeat(2_000_000));
        MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(document, "create"));
        assertTrue(failure.error().message().contains("at least " + (DocumentSizeValidator.MAX_BYTES + 1)));
        assertFalse(failure.error().message().contains("2000000"));
        assertFalse(failure.error().message().contains("4000000"), "counter must stop before later fields");
    }

    @Test
    void retainsReleasedDepthRatherThanUnshippedDocumentLimit() throws Exception {
        Map<String, Object> document = Map.of("v", "x");
        for (int depth = 2; depth <= 1000; depth++) {
            document = Map.of("v", document);
        }
        Map<String, Object> accepted = document;
        assertEquals(6003, DocumentJsonSize.measure(accepted, Long.MAX_VALUE));
        assertDoesNotThrow(() -> DocumentSizeValidator.validate(accepted, "create"));
        Map<String, Object> rejected = Map.of("v", document);
        assertThrows(MulticloudDbException.class, () -> DocumentSizeValidator.validate(rejected, "create"));
    }

    @Test
    void acceptsNumbersBeyondUnshippedModelCapsWithoutChangingThem() throws Exception {
        int[] expected = {1507, 13, 13, 7};
        int index = 0;
        for (Number number : List.of(BigInteger.TEN.pow(1500),
                new BigDecimal("1E+1025"), new BigDecimal("1E-1025"),
                new BigDecimal("0E-2147483647"))) {
            Map<String, Object> document = Map.of("n", number);
            assertEquals(expected[index++], DocumentJsonSize.measure(document, Long.MAX_VALUE));
            assertDoesNotThrow(() -> DocumentSizeValidator.validate(document, "upsert"));
            assertSame(number, document.get("n"));
        }
    }

    @Test
    void rejectsUnsupportedValuesAndCyclesWithSafeInvalidRequestErrors() {
        Map<String, Object> cyclicMap = new java.util.LinkedHashMap<>();
        cyclicMap.put("cycle", cyclicMap);
        List<Object> cyclicList = new java.util.ArrayList<>();
        cyclicList.add(cyclicList);
        Map<Object, Object> nullKey = new java.util.LinkedHashMap<>();
        nullKey.put(null, "value");
        List<Object> invalid = List.of(new Object(), new int[]{1}, java.util.Set.of(1),
                new java.util.concurrent.atomic.AtomicInteger(1), Double.NaN,
                Float.POSITIVE_INFINITY, Map.of(1, "key"), nullKey, cyclicMap, cyclicList);
        for (Object value : invalid) {
            MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                    () -> DocumentSizeValidator.validate(Map.of("secret-field", value), "update"));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
            assertEquals("update", failure.error().operation());
            assertFalse(failure.error().message().contains("secret-field"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void permitsSharedSubtreesWithoutChangingInput() throws Exception {
        List<Object> shared = new java.util.ArrayList<>(java.util.Arrays.asList(null, 1, "v"));
        Map<String, Object> document = Map.of("a", shared, "b", shared);
        assertEquals("{\"a\":[null,1,\"v\"],\"b\":[null,1,\"v\"]}".length(),
                DocumentJsonSize.measure(document, Long.MAX_VALUE));
        assertSame(shared, document.get("a"));
        assertSame(shared, document.get("b"));
        assertEquals(java.util.Arrays.asList(null, 1, "v"), shared);
    }
}
