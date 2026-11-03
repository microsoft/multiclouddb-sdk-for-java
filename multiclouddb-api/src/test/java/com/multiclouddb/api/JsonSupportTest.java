// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api;

import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.internal.JsonSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonSupportTest {
    @Test
    void sequentialListsDetachInOrderWithoutIndexedReadsAndStillRejectCycles() throws IOException {
        var source = new java.util.LinkedList<Object>() {
            @Override
            public Object get(int index) {
                throw new AssertionError("Sequential list must not be accessed by index");
            }
        };
        var shared = new java.util.LinkedList<Object>(List.of("nested"));
        for (int i = 0; i < 2048; i++) source.add(i);
        source.add(shared);
        source.add(null);
        source.add(shared);
        List<?> result = assertInstanceOf(List.class, JsonSupport.read("[]", (s, t) -> source));
        assertEquals(2051, result.size());
        for (int i = 0; i < 2048; i++) assertEquals(i, result.get(i));
        assertEquals(List.of("nested"), result.get(2048));
        assertNull(result.get(2049));
        assertEquals(List.of("nested"), result.get(2050));
        assertNotSame(result.get(2048), result.get(2050));
        shared.clear();
        assertEquals(List.of("nested"), result.get(2048));
        source.add(source);
        assertThrows(IllegalStateException.class, () -> JsonSupport.read("[]", (s, t) -> source));
    }

    @Test
    void compatibilityProfilePreservesStringContentAndRejectsUnsupportedSyntax() throws IOException {
        assertEquals("\uFEFF", JsonSupport.read("\"\\uFEFF\"", TestJson.DESERIALIZE));
        assertThrows(IOException.class, () -> JsonSupport.read("\"raw\ncontrol\"", TestJson.DESERIALIZE));
        String name = "n".repeat(50_000);
        assertEquals(Map.of(name, 1), JsonSupport.read("{\"" + name + "\":1}", TestJson.DESERIALIZE));
        assertThrows(IOException.class, () -> JsonSupport.read("{\"" + name + "n\":1}", TestJson.DESERIALIZE));
    }
    @Test
    void separatesProcessingFailuresFromContractFailuresWithoutLeakingPayloads() {
        IOException processing = assertThrows(IOException.class, () -> JsonSupport.read("secret", (s, t) -> {
            throw new UncheckedIOException(new IOException("secret"));
        }));
        assertNull(processing.getCause());
        assertFalse(processing.getMessage().contains("secret"));
        for (Object invalid : List.of(new Object(), new int[]{1}, Map.of(1, "bad"), List.of(new Object()))) {
            assertThrows(IllegalStateException.class, () -> JsonSupport.read("{}", (s, t) -> invalid));
        }
        IllegalStateException contract = assertThrows(IllegalStateException.class,
                () -> JsonSupport.read("secret", (s, t) -> { throw new IllegalArgumentException("secret"); }));
        assertNull(contract.getCause());
        assertFalse(contract.getMessage().contains("secret"));
        assertThrows(IllegalStateException.class, () -> JsonSupport.write(Map.of(), v -> null));
        assertThrows(IOException.class, () -> JsonSupport.write(Map.of(), v -> {
            throw new UncheckedIOException(new IOException("secret"));
        }));
    }

    @Test
    void detachesSharedContainersAndRejectsCyclesWithoutReadSideFiniteRestrictions() throws IOException {
        var nested = new ArrayList<Object>(List.of(1));
        var source = new LinkedHashMap<String, Object>();
        source.put("a", nested);
        source.put("b", nested);
        Map<?, ?> result = assertInstanceOf(Map.class, JsonSupport.read("{}", (s, t) -> source));
        nested.clear();
        assertEquals(List.of(1), result.get("a"));
        assertNotSame(result.get("a"), result.get("b"));
        source.put("self", source);
        assertThrows(IllegalStateException.class, () -> JsonSupport.read("{}", (s, t) -> source));
        assertEquals(Double.POSITIVE_INFINITY, JsonSupport.read("1e999", TestJson.DESERIALIZE));
        assertNull(JsonSupport.read("null", TestJson.DESERIALIZE));
        assertEquals(true, JsonSupport.read("true", TestJson.DESERIALIZE));
    }

    @Test
    void inMemorySentinelNeedsNoCallbacksButPersistenceDoes() {
        var cursor = ChangeFeedCursor.now();
        assertNotNull(cursor);
        assertThrows(NullPointerException.class, () -> cursor.toToken(null));
        assertThrows(NullPointerException.class, () -> ChangeFeedCursor.fromToken("e30", null));
        String persisted = cursor.toToken(TestJson.SERIALIZE);
        var independentMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = ChangeFeedCursor.fromToken(persisted, (text, type) -> {
            try {
                return independentMapper.readValue(text, type);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        assertEquals(cursor.token(), restored.token());
        assertThrows(IllegalStateException.class,
                () -> ChangeFeedCursor.fromToken(persisted, (s, t) -> new Object()));
    }
}
