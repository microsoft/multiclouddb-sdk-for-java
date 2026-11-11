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
        for (Number value : List.of((byte) 1, (short) 2, 3, Long.MIN_VALUE, Long.MAX_VALUE,
                0.1F, Float.MIN_VALUE, Float.MAX_VALUE, -0.0D, Double.MIN_VALUE, Double.MAX_VALUE,
                BigInteger.TEN.pow(1500), new BigDecimal("1.00"), new BigDecimal("1E+1025"),
                new BigDecimal("1E-1025"), new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE))) {
            Map<String, Object> input = Map.of("number", value);
            assertDoesNotThrow(() -> DocumentValidator.validate(input, "upsert"));
            assertSame(value, input.get("number"));
        }
        for (String text : List.of("\ud800", "\udfff", "\ud83d\ude00", "\u0000", "\uD55C\uAE00")) {
            assertDoesNotThrow(() -> DocumentValidator.validate(Map.of(text, text), "create"));
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
        String allowedNumbers = "Byte, Short, Integer, Long, Float, Double, BigInteger or BigDecimal";
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
        for (Number value : List.of(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
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
