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
        assertTrue(ev.hasData());
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
        assertFalse(ev.hasData());
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
    void distinguishesPresentNullEmptyAndAbsentPayloads() {
        MulticloudDbKey key = MulticloudDbKey.of("k");
        ChangeEvent presentNull = new ChangeEvent(key, ChangeType.UPDATE, Instant.EPOCH, null, "id", true);
        ChangeEvent empty = new ChangeEvent(key, ChangeType.UPDATE, Instant.EPOCH, Map.of(), "id");
        ChangeEvent absent = new ChangeEvent(key, ChangeType.DELETE, Instant.EPOCH, null, "id", false);
        assertTrue(presentNull.hasData());
        assertNull(presentNull.data());
        assertTrue(presentNull.toString().contains("(data)"));
        assertTrue(empty.hasData());
        assertFalse(absent.hasData());
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeEvent(key, ChangeType.UPDATE, Instant.EPOCH, Map.of(), "id", false));
    }
}
