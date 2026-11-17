// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.MulticloudDbKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChangeEventTest {

    @Test
    @DisplayName("constructor retains all fields by reference")
    void constructorPreservesFields() {
        MulticloudDbKey key = MulticloudDbKey.of("user-1");
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        Map<String, Object> data = Map.of("name", "alice");

        ChangeEvent ev = new ChangeEvent(key, ChangeType.UPDATE, ts, data, "evt-123");

        assertEquals(key, ev.key());
        assertEquals(ChangeType.UPDATE, ev.type());
        assertEquals(ts, ev.commitTimestamp());
        assertSame(data, ev.data());
        assertNotNull(ev.data());
        assertEquals("evt-123", ev.providerEventId());
    }

    @Test
    @DisplayName("DELETE events may carry null data")
    void deleteAllowsNullData() {
        ChangeEvent ev = new ChangeEvent(
                MulticloudDbKey.of("k"), ChangeType.DELETE,
                Instant.EPOCH, null, "evt-d");
        assertEquals(ChangeType.DELETE, ev.type());
        assertNull(ev.data());
        assertFalse(ev.toString().contains("(data)"));
    }

    @Test
    @DisplayName("constructor rejects null required fields")
    void constructorRejectsRequiredNulls() {
        Instant ts = Instant.now();
        MulticloudDbKey key = MulticloudDbKey.of("k");
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(null, ChangeType.CREATE, ts, Map.of(), "id"));
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(key, null, ts, Map.of(), "id"));
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(key, ChangeType.CREATE, null, Map.of(), "id"));
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(key, ChangeType.CREATE, ts, Map.of(), null));
    }

    @Test
    void nullMeansNoPayloadWhileEmptyAndNullValuedMapsRemainPayloads() {
        MulticloudDbKey key = MulticloudDbKey.of("k");
        for (ChangeType type : ChangeType.values()) {
            ChangeEvent absent = new ChangeEvent(key, type, Instant.EPOCH, null, "id");
            assertNull(absent.data());
            assertFalse(absent.toString().contains("(data)"));
            assertEquals(key, absent.key());
            assertEquals(type, absent.type());
            assertEquals(Instant.EPOCH, absent.commitTimestamp());
            assertEquals("id", absent.providerEventId());
            for (Object payload : java.util.List.of(Map.of(), java.util.Collections.singletonMap("field", null),
                    java.util.List.of(), "text", 7, false)) {
                ChangeEvent event = new ChangeEvent(key, type, Instant.EPOCH, payload, "id");
                assertSame(payload, event.data());
                assertTrue(event.toString().contains("(data)"));
            }
        }
        assertEquals(1, ChangeEvent.class.getConstructors().length);
        assertEquals(5, ChangeEvent.class.getConstructors()[0].getParameterCount());
        assertTrue(java.util.Arrays.stream(ChangeEvent.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == boolean.class));
        assertThrows(NoSuchMethodException.class, () -> ChangeEvent.class.getMethod("hasData"));
    }
}
