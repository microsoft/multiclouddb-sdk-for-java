package com.hyperscaledb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyTest {

    @Test
    void ofPartitionKeyOnly() {
        Key key = Key.of("pk1");
        assertEquals("pk1", key.partitionKey());
        assertNull(key.sortKey());
        assertTrue(key.components().isEmpty());
    }

    @Test
    void ofPartitionKeyAndSortKey() {
        Key key = Key.of("pk1", "abc");
        assertEquals("pk1", key.partitionKey());
        assertEquals("abc", key.sortKey());
    }

    @Test
    void nullPartitionKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> Key.of(null));
    }

    @Test
    void equalKeys() {
        Key k1 = Key.of("a", "b");
        Key k2 = Key.of("a", "b");
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void differentKeysNotEqual() {
        Key k1 = Key.of("a", "b");
        Key k2 = Key.of("a", "c");
        assertNotEquals(k1, k2);
    }
}
