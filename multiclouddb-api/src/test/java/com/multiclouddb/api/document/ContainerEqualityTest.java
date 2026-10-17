// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContainerEqualityTest {
    @Test
    void independentSharedArraysCompareWithoutExpandingEveryOccurrence() {
        assertSharedEquality("array");
    }

    @Test
    void independentSharedObjectsCompareWithoutExpandingEveryOccurrence() {
        assertSharedEquality("object");
    }

    @Test
    void independentMixedGraphsShareOneComparisonContext() {
        assertSharedEquality("mixed");
    }

    private static void assertSharedEquality(String kind) {
        // Bound the old algorithm's work too, rather than testing a runaway 2^40 graph.
        DocumentValue left = shared(kind, NumberValue.of(1), 20);
        DocumentValue right = shared(kind, NumberValue.of(new BigDecimal("1.00")), 20);
        Document first = Document.builder().put("value", left).build();
        Document second = Document.builder().put("value", right).build();
        assertTimeout(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 32; i++) {
                assertEquals(left, right);
                assertEquals(right, left);
                assertEquals(first, second);
                var documents = new HashSet<Document>();
                assertTrue(documents.add(first));
                assertFalse(documents.add(second));
            }
        });
    }

    private static DocumentValue shared(String kind, DocumentValue value, int depth) {
        for (int i = 0; i < depth; i++) {
            value = kind.equals("array") || kind.equals("mixed") && i % 2 == 0
                    ? ArrayValue.of(List.of(value, value))
                    : ObjectValue.of(Map.of("a", value, "b", value));
        }
        return value;
    }

    @Test
    void memoizationTracksPairsNotIndividualNodesAndDoesNotTrustHashCollisions() {
        var a = ArrayValue.of(List.of(new StringValue("Aa")));
        var equalA = ArrayValue.of(List.of(new StringValue("Aa")));
        var b = ArrayValue.of(List.of(new StringValue("BB")));
        assertEquals(a.hashCode(), b.hashCode());
        var repeated = ArrayValue.of(List.of(a, a));
        var different = ArrayValue.of(List.of(equalA, b));
        assertNotEquals(repeated, different);
        assertNotEquals(different, repeated);
        var repeatedObject = ObjectValue.of(Map.of("a", a, "b", a));
        var differentObject = ObjectValue.of(Map.of("a", equalA, "b", b));
        assertNotEquals(repeatedObject, differentObject);
        assertNotEquals(differentObject, repeatedObject);
        assertNotEquals(shared("mixed", new StringValue("Aa"), 20),
                shared("mixed", new StringValue("BB"), 20));
        assertEquals(repeated, ArrayValue.of(List.of(equalA,
                ArrayValue.of(List.of(new StringValue("Aa"))))));
    }

    @Test
    void retainsValueSemanticsAcrossOrderingMissingNullKindsAndSeparateCalls() {
        var first = new LinkedHashMap<String, DocumentValue>();
        first.put("number", NumberValue.of(-0.0));
        first.put("null", NullValue.INSTANCE);
        var reordered = new LinkedHashMap<String, DocumentValue>();
        reordered.put("null", NullValue.INSTANCE);
        reordered.put("number", NumberValue.of(0));
        assertEquals(ObjectValue.of(first), ObjectValue.of(reordered));
        assertEquals(ObjectValue.of(Map.of()), ObjectValue.of(Map.of()));
        assertEquals(ArrayValue.of(List.of()), ArrayValue.of(List.of()));
        assertNotEquals(ObjectValue.of(Map.of()), ObjectValue.of(Map.of("v", NullValue.INSTANCE)));
        // Equal key hashes must not turn absence into an explicit null.
        assertNotEquals(ObjectValue.of(Map.of("Aa", NullValue.INSTANCE)),
                ObjectValue.of(Map.of("BB", NullValue.INSTANCE)));
        var array = ArrayValue.of(List.of(new StringValue("Aa"), new StringValue("BB")));
        var reversed = ArrayValue.of(List.of(new StringValue("BB"), new StringValue("Aa")));
        assertEquals(array.hashCode(), reversed.hashCode());
        assertNotEquals(array, reversed);
        assertNotEquals(array, null);
        assertNotEquals(array, array.values());
        assertNotEquals(array, ObjectValue.of(Map.of()));
        assertEquals(array, ArrayValue.of(array.values()));
        assertNotEquals(array, reversed);
    }
}
