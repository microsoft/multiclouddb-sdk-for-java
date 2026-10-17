// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.document.ObjectValue;
import com.multiclouddb.api.document.StringValue;
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
        ObjectValue data = ObjectValue.of(Map.of("name", new StringValue("alice")));

        ChangeEvent ev = new ChangeEvent(key, ChangeType.UPDATE, ts, data, "evt-123");

        assertEquals(key, ev.key());
        assertEquals(ChangeType.UPDATE, ev.type());
        assertEquals(ts, ev.commitTimestamp());
        assertSame(data, ev.data());
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
    }

    @Test
    @DisplayName("constructor rejects null required fields")
    void constructorRejectsRequiredNulls() {
        Instant ts = Instant.now();
        MulticloudDbKey key = MulticloudDbKey.of("k");
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(null, ChangeType.CREATE, ts, ObjectValue.of(Map.of()), "id"));
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(key, null, ts, ObjectValue.of(Map.of()), "id"));
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(key, ChangeType.CREATE, null, ObjectValue.of(Map.of()), "id"));
        assertThrows(NullPointerException.class,
                () -> new ChangeEvent(key, ChangeType.CREATE, ts, ObjectValue.of(Map.of()), null));
    }
}
