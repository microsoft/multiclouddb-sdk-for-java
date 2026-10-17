// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContainerHashTest {
    @Test
    void sharedArraysRetainListHash() {
        assertSharedHash("array");
    }

    @Test
    void sharedObjectsRetainMapHash() {
        assertSharedHash("object");
    }

    @Test
    void mixedSharedSubtreesAndDocumentsRetainHash() {
        assertSharedHash("mixed");
    }

    private static void assertSharedHash(String kind) {
        // Cap expansion even if recursive hashing regresses; never build a 2^40 test.
        assertTimeout(Duration.ofSeconds(5), () -> {
            DocumentValue value = new StringValue("leaf");
            int expected = "leaf".hashCode();
            for (int depth = 0; depth < 20; depth++) {
                if (kind.equals("array") || kind.equals("mixed") && depth % 2 == 0) {
                    value = ArrayValue.of(List.of(value, value));
                    expected = 31 * (31 + expected) + expected;
                } else {
                    value = ObjectValue.of(Map.of("a", value, "b", value));
                    expected = ("a".hashCode() ^ expected) + ("b".hashCode() ^ expected);
                }
                assertEquals(expected, value.hashCode());
            }
            Document document = Document.builder().put("root", value).build();
            int documentHash = "root".hashCode() ^ expected;
            for (int repeat = 0; repeat < 64; repeat++) {
                assertEquals(expected, value.hashCode());
                assertEquals(documentHash, document.hashCode());
                assertEquals(documentHash, document.root().hashCode());
            }
        });
    }

    @Test
    void hashesPreserveOwnershipEqualityOrderAndZeroValues() {
        List<DocumentValue> input = new ArrayList<>(List.of(
                NumberValue.of(new BigDecimal("1.00")), new StringValue("text")));
        int listHash = input.hashCode();
        ArrayValue array = ArrayValue.of(input);
        input.clear();
        assertEquals(listHash, array.hashCode());
        ArrayValue equivalent = ArrayValue.of(List.of(NumberValue.of(1), new StringValue("text")));
        assertEquals(array, equivalent);
        assertEquals(array.hashCode(), equivalent.hashCode());
        assertNotEquals(array.hashCode(),
                ArrayValue.of(List.of(new StringValue("text"), NumberValue.of(1))).hashCode());

        Map<String, DocumentValue> fields = new LinkedHashMap<>();
        fields.put("array", array);
        fields.put("zero", NumberValue.of(-0.0));
        int mapHash = fields.hashCode();
        ObjectValue object = ObjectValue.of(fields);
        fields.clear();
        assertEquals(mapHash, object.hashCode());
        fields.put("zero", NumberValue.of(0));
        fields.put("array", equivalent);
        ObjectValue reordered = ObjectValue.of(fields);
        assertEquals(object, reordered);
        assertEquals(object.hashCode(), reordered.hashCode());
        assertEquals(Document.of(object).hashCode(), Document.of(reordered).hashCode());

        assertEquals(List.of().hashCode(), ArrayValue.of(List.of()).hashCode());
        assertEquals(Map.of().hashCode(), ObjectValue.of(Map.of()).hashCode());
        // NumberValue(-1) has hash -31, making the one-element List hash zero.
        ArrayValue zeroArray = ArrayValue.of(List.of(NumberValue.of(-1)));
        ObjectValue zeroObject = ObjectValue.of(Map.of("", new StringValue("")));
        for (int repeat = 0; repeat < 3; repeat++) {
            assertEquals(0, zeroArray.hashCode());
            assertEquals(0, zeroObject.hashCode());
            assertEquals(0, Document.of(zeroObject).hashCode());
        }
    }
}
