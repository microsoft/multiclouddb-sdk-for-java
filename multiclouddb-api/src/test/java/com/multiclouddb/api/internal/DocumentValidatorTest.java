// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.BudgetedGraph;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentValidatorTest {
    @Test
    void consumesFlatPrefixAndNestedSuffixThroughOneRootIterator() {
        var shared = new ArrayList<Object>(List.of("leaf"));
        for (List<Object> values : List.<List<Object>>of(
                Arrays.asList(null, "text", true, 1, 1.0D),
                Arrays.asList(null, 1, shared, false, shared, List.of()),
                Arrays.asList(Map.of(), "suffix", shared))) {
            int[] reads = new int[values.size()];
            Map<String, Object> root = singlePassRoot(values, reads);
            assertDoesNotThrow(() -> DocumentValidator.validate(root, "create"));
            for (int count : reads) assertEquals(1, count);
        }
    }

    @Test
    void flatValidationFailsBeforeReadingLaterFieldsAndKeepsDiagnostics() {
        for (Object invalid : List.of(new Object(), Double.NaN,
                new java.util.concurrent.atomic.AtomicInteger(1))) {
            int[] reads = new int[3];
            Map<String, Object> root = singlePassRoot(Arrays.asList("prefix", invalid, List.of()), reads);
            var failure = assertThrows(MulticloudDbException.class,
                    () -> DocumentValidator.validate(root, "update"));
            assertInvalid(failure, "update");
            assertArrayEquals(new int[]{1, 1, 0}, reads);
            var nestedFailure = assertThrows(MulticloudDbException.class,
                    () -> DocumentValidator.validate(Map.of("nested", List.of(invalid)), "update"));
            assertEquals(nestedFailure.getMessage(), failure.getMessage());
        }
        var nullKey = new LinkedHashMap<String, Object>();
        nullKey.put(null, "secret");
        assertSafeDiagnostic(nullKey,
                "Document Map key has type null; expected a non-null java.lang.String.", "secret");
    }

    @Test
    void rootRemainsActiveAcrossTheLazyTransitionAndCompletedChildren() {
        for (boolean afterEmptyChild : List.of(false, true)) {
            for (boolean throughList : List.of(false, true)) {
                var root = new LinkedHashMap<String, Object>();
                root.put("prefix", 1);
                if (afterEmptyChild) root.put("empty", List.of());
                root.put("cycle", throughList ? List.of(root) : root);
                assertSafeDiagnostic(root,
                        "Document contains a cycle through container type " + root.getClass().getName()
                                + "; only acyclic Map/List values are allowed.", "secret");
            }
        }
    }

    private static Map<String, Object> singlePassRoot(List<Object> values, int[] reads) {
        var entries = new ArrayList<Map.Entry<String, Object>>();
        for (int i = 0; i < values.size(); i++) {
            int index = i;
            entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>("field" + i, values.get(i)) {
                @Override public Object getValue() {
                    assertEquals(0, reads[index]++, "Root entry must not be revisited");
                    return super.getValue();
                }
            });
        }
        return new java.util.AbstractMap<>() {
            private int iterators;
            @Override public java.util.Set<Entry<String, Object>> entrySet() {
                return new java.util.AbstractSet<>() {
                    @Override public java.util.Iterator<Entry<String, Object>> iterator() {
                        assertEquals(0, iterators++, "Root iterator must not be restarted");
                        return entries.iterator();
                    }
                    @Override public int size() { return entries.size(); }
                };
            }
        };
    }

    @Test
    void countsOnlyContainersBelowTheRootIncludingEmptyContainers() {
        assertDoesNotThrow(() -> DocumentValidator.validate(Map.of(), "create"));
        assertDoesNotThrow(() -> DocumentValidator.validate(Map.of("scalar", 1), "create"));
        for (String shape : List.of("map", "list", "mixed")) {
            for (boolean empty : List.of(false, true)) {
                for (int depth : new int[]{1, 31, 32, 33, 10_000}) {
                    Map<String, Object> input = nested(depth, shape, empty);
                    if (depth <= 32) {
                        assertDoesNotThrow(() -> DocumentValidator.validate(input, "create"),
                                shape + "/" + empty + "/" + depth);
                    } else {
                        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                                () -> DocumentValidator.validate(input, "update"));
                        assertInvalid(error, "update");
                        assertTrue(error.getMessage().contains("depth 33 exceeds maximum 32 (root Map depth 0)"));
                        assertTrue(error.getMessage().contains("container type "));
                    }
                }
            }
        }
    }

    private static Map<String, Object> nested(int depth, String shape, boolean empty) {
        Object value = "leaf";
        for (int level = depth; level >= 1; level--) {
            boolean map = shape.equals("map") || shape.equals("mixed") && level % 2 == 0;
            value = empty && level == depth ? (map ? Map.of() : List.of())
                    : map ? Map.of("child", value) : List.of(value);
        }
        return Map.of("field", value);
    }

    @Test
    void doesNotMeasureStringsOrNormalizeNumbersForSize() {
        for (int formerLogicalBytes : new int[]{408575, 408576, 408577, 2_000_000}) {
            assertDoesNotThrow(() -> DocumentValidator.validate(
                    Map.of("body", "x".repeat(formerLogicalBytes - 11)), "create"));
        }
        for (Number value : List.of(3, -9_007_199_254_740_991L, 9_007_199_254_740_991L,
                0.1D, -0.0D, 1E-130, 9_007_199_254_740_991D)) {
            Map<String, Object> input = Map.of("number", value);
            assertDoesNotThrow(() -> DocumentValidator.validate(input, "upsert"));
            assertSame(value, input.get("number"));
        }
        for (String text : List.of("\ud800", "\udfff", "\ud83d\ude00", "\u0000", "\uD55C\uAE00")) {
            assertDoesNotThrow(() -> DocumentValidator.validate(Map.of(text, text), "create"));
        }
    }

    @Test
    void appliesNumericBoundsToFlatAndNestedValuesWithoutChangingThem() {
        for (Number number : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE,
                -9_007_199_254_740_991L, 9_007_199_254_740_991L,
                0.0D, -0.0D, 0.1D, 1E-130, -1E-130, Math.nextUp(1E-130),
                -9_007_199_254_740_991D, 9_007_199_254_740_991D)) {
            for (Map<String, Object> input : List.of(Map.<String, Object>of("n", number),
                    Map.<String, Object>of("nested", List.of(Map.of("n", number))))) {
                assertDoesNotThrow(() -> DocumentValidator.validate(input, "upsert"));
            }
        }
        for (Number number : List.of(-9_007_199_254_740_992L, 9_007_199_254_740_992L,
                Long.MIN_VALUE, Long.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE,
                Math.nextDown(1E-130), -Math.nextDown(1E-130),
                Math.nextUp(9_007_199_254_740_991D), -Math.nextUp(9_007_199_254_740_991D),
                Double.MAX_VALUE, -Double.MAX_VALUE)) {
            String expected = number instanceof Long
                    ? "Document Long must be between -9007199254740991 and 9007199254740991 inclusive."
                    : "Document Double must be zero or have absolute value between "
                            + "1E-130 and 9007199254740991 inclusive.";
            assertSafeDiagnostic(Map.of("private-field", number), expected, "private-field");
            assertSafeDiagnostic(Map.of("private-field", List.of(Map.of("n", number))), expected, "private-field");
        }
    }

    @Test
    void rejectsOtherNumericClassesEvenWhenTheirValuesFit() {
        for (Number number : List.of((byte) 1, (short) 1, 1.0F, BigInteger.ONE, BigDecimal.ONE,
                new java.util.concurrent.atomic.AtomicInteger(1),
                new java.util.concurrent.atomic.AtomicLong(1))) {
            String expected = "Unsupported document number type " + number.getClass().getName()
                    + "; expected exactly Integer, Long or Double.";
            assertSafeDiagnostic(Map.of("private-field", number), expected, "private-field");
            assertSafeDiagnostic(Map.of("private-field", List.of(number)), expected, "private-field");
        }
    }

    @Test
    void rejectsUnsupportedValuesKeysNonfiniteNumbersAndCyclesWithoutLeakingPayloads() {
        var cycleMap = new LinkedHashMap<String, Object>();
        cycleMap.put("private-value", cycleMap);
        var cycleList = new ArrayList<Object>();
        cycleList.add(cycleList);
        var nullKey = new LinkedHashMap<Object, Object>();
        nullKey.put(null, "private-value");
        for (Object value : List.of(new Object(), new int[]{1}, java.util.Set.of(1),
                new java.util.concurrent.atomic.AtomicInteger(1), Double.NaN, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Map.of(1, "private-value"),
                nullKey, cycleMap, cycleList)) {
            var failure = assertThrows(MulticloudDbException.class,
                    () -> DocumentValidator.validate(Map.of("private-field", value), "update"));
            assertInvalid(failure, "update");
            assertFalse(failure.getMessage().contains("private"));
            assertNull(failure.getCause());
        }
        assertInvalid(assertThrows(MulticloudDbException.class,
                () -> DocumentValidator.validate(null, "create")), "create");
    }

    @Test
    void permitsSharedSubtreesWithoutModifyingOrIndexingSequentialLists() {
        var shared = new java.util.LinkedList<Object>(Arrays.asList(null, 1, "v")) {
            @Override
            public Object get(int index) { throw new AssertionError("Indexed access"); }
        };
        Map<String, Object> input = Map.of("a", shared, "b", shared);
        assertDoesNotThrow(() -> DocumentValidator.validate(input, "create"));
        assertSame(shared, input.get("a"));
        assertSame(shared, input.get("b"));
        assertEquals(Arrays.asList(null, 1, "v"), new ArrayList<>(shared));
    }

    @Test
    void validatesSharedGraphsOncePerIdentityWithinADeterministicBudget() {
        var counts = new BudgetedGraph(2048);
        Object shared = counts.list("leaf");
        for (int i = 0; i < 31; i++) {
            shared = i % 2 == 0 ? counts.map(shared, shared) : counts.list(shared, shared);
        }
        Map<String, Object> root = Map.of("a", shared, "b", shared);
        assertDoesNotThrow(() -> DocumentValidator.validate(root, "create"));
        assertEquals(32, counts.containers());
        assertEquals(63, counts.edges());
        assertTrue(counts.accesses() <= 8 * counts.edges() + 4 * counts.containers());
        assertSame(root.get("a"), root.get("b"));
    }

    @Test
    void checksCachedSubtreeHeightAtEveryDeeperReuseIncludingEmptyLeaves() {
        for (boolean empty : List.of(false, true)) {
            for (int deepest : new int[]{32, 33}) {
                Object leaf = empty ? new ArrayList<>() : new ArrayList<>(List.of("leaf"));
                Object shared = Map.of("nested", leaf);
                Object deeper = shared;
                for (int i = 0; i < deepest - 2; i++) deeper = List.of(deeper);
                var root = new LinkedHashMap<String, Object>();
                root.put("shallow", shared);
                root.put("deeper", deeper);
                if (deepest == 32) {
                    assertDoesNotThrow(() -> DocumentValidator.validate(root, "create"));
                } else {
                    assertInvalid(assertThrows(MulticloudDbException.class,
                            () -> DocumentValidator.validate(root, "update")), "update");
                }
            }
        }
    }

    @Test
    void failedOrMutatedSubtreesAreNeverReusedAcrossValidationCalls() {
        var shared = new ArrayList<Object>(List.of("ok"));
        var failing = new ArrayList<Object>(List.of(shared, new Object()));
        var root = new LinkedHashMap<String, Object>();
        root.put("completed", shared);
        root.put("failed", failing);
        assertThrows(MulticloudDbException.class, () -> DocumentValidator.validate(root, "create"));
        failing.set(1, "fixed");
        assertDoesNotThrow(() -> DocumentValidator.validate(root, "create"));
        shared.add(failing);
        assertThrows(MulticloudDbException.class, () -> DocumentValidator.validate(root, "create"));
        shared.remove(1);
        shared.add(new Object());
        assertThrows(MulticloudDbException.class, () -> DocumentValidator.validate(root, "create"));

        var broken = new java.util.AbstractMap<String, Object>() {
            @Override public java.util.Set<Entry<String, Object>> entrySet() {
                throw new IllegalArgumentException("access failure");
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> DocumentValidator.validate(Map.of("a", broken, "b", broken), "create"));
    }

    @Test
    void diagnosticsDescribeRulesAndTypesWithoutExposingValuesOrCallingToString() {
        String secret = "validation-secret-marker";
        Object hostile = new Object() {
            @Override
            public String toString() { throw new AssertionError(secret); }
        };
        Number hostileNumber = new BigDecimal("1") {
            @Override
            public String toString() { throw new AssertionError(secret); }
        };
        String allowedNumbers = "Integer, Long or Double";
        for (Object value : List.of(hostile, hostileNumber)) {
            String expected = value instanceof Number
                    ? "Unsupported document number type " + value.getClass().getName()
                            + "; expected exactly " + allowedNumbers + "."
                    : "Unsupported document value type " + value.getClass().getName()
                            + "; expected Map, List, String, Boolean, null, or exactly " + allowedNumbers + ".";
            assertSafeDiagnostic(Map.of(secret, List.of(value)), expected, secret);
        }
        for (Object key : Arrays.asList(null, hostile, 42)) {
            var keyed = new LinkedHashMap<Object, Object>();
            keyed.put(key, secret);
            assertSafeDiagnostic(Map.of(secret, keyed),
                    "Document Map key has type " + (key == null ? "null" : key.getClass().getName())
                            + "; expected a non-null java.lang.String.", secret);
        }
        for (Number value : List.of(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertSafeDiagnostic(Map.of(secret, value),
                    "Document number of type " + value.getClass().getName() + " must be finite.", secret);
        }
        var cycle = new ArrayList<Object>() {
            @Override
            public String toString() { throw new AssertionError(secret); }
        };
        cycle.add(cycle);
        assertSafeDiagnostic(Map.of(secret, cycle),
                "Document contains a cycle through container type " + cycle.getClass().getName()
                        + "; only acyclic Map/List values are allowed.", secret);
        Object leaf = new LinkedHashMap<String, Object>();
        Object nested = leaf;
        for (int i = 1; i < 33; i++) nested = List.of(nested);
        assertSafeDiagnostic(Map.of(secret, nested),
                "Document nesting depth 33 exceeds maximum 32 (root Map depth 0); container type "
                        + leaf.getClass().getName() + ".", secret);
        assertSafeDiagnostic(null, "Document root is null; expected a non-null Map<String, Object>.", secret);
    }

    private static void assertSafeDiagnostic(Map<String, Object> document, String expected, String secret) {
        var failure = assertThrows(MulticloudDbException.class,
                () -> DocumentValidator.validate(document, "upsert"));
        assertInvalid(failure, "upsert");
        assertEquals(expected, failure.getMessage());
        assertEquals(expected, failure.error().message());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(failure.error().providerDetails().isEmpty());
        assertFalse(failure.toString().contains(secret));
        assertFalse(failure.error().toString().contains(secret));
        var trace = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        assertFalse(trace.toString().contains(secret));
    }

    private static void assertInvalid(MulticloudDbException failure, String operation) {
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
        assertEquals(operation, failure.error().operation());
        assertFalse(failure.error().retryable());
    }
}
