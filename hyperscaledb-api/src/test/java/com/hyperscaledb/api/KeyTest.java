// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyTest {

    @Test
    void ofPartitionKeyOnly() {
        HyperscaleDbKey key = HyperscaleDbKey.of("pk1");
        assertEquals("pk1", key.partitionKey());
        assertNull(key.sortKey());
        assertTrue(key.components().isEmpty());
    }

    @Test
    void ofPartitionKeyAndSortKey() {
        HyperscaleDbKey key = HyperscaleDbKey.of("pk1", "abc");
        assertEquals("pk1", key.partitionKey());
        assertEquals("abc", key.sortKey());
    }

    @Test
    void nullPartitionKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> HyperscaleDbKey.of(null));
    }

    @Test
    void equalKeys() {
        HyperscaleDbKey k1 = HyperscaleDbKey.of("a", "b");
        HyperscaleDbKey k2 = HyperscaleDbKey.of("a", "b");
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void differentKeysNotEqual() {
        HyperscaleDbKey k1 = HyperscaleDbKey.of("a", "b");
        HyperscaleDbKey k2 = HyperscaleDbKey.of("a", "c");
        assertNotEquals(k1, k2);
    }
}
