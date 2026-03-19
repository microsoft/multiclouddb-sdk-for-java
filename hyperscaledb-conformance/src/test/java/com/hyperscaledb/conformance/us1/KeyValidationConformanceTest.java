// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us1;

import com.hyperscaledb.api.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for key validation.
 * <p>
 * Verifies that providers correctly validate key inputs and reject
 * null, empty, or blank keys with appropriate errors.
 * <p>
 * Subclass and implement {@link #createClient()} and {@link #getAddress()}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class KeyValidationConformanceTest {

    protected abstract HyperscaleDbClient createClient();
    protected abstract ResourceAddress getAddress();

    private HyperscaleDbClient client;

    @BeforeEach
    void setUp() {
        client = createClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("upsert with null key throws exception")
    void upsertNullKey() {
        assertThrows(NullPointerException.class,
                () -> client.upsert(getAddress(), null, java.util.Map.of("title", "test")));
    }

    @Test
    @DisplayName("read with null key throws exception")
    void readNullKey() {
        assertThrows(NullPointerException.class,
                () -> client.read(getAddress(), null));
    }

    @Test
    @DisplayName("delete with null key throws exception")
    void deleteNullKey() {
        assertThrows(NullPointerException.class,
                () -> client.delete(getAddress(), null));
    }

    @Test
    @DisplayName("Key.of rejects null partitionKey")
    void keyOfNullPartitionKey() {
        assertThrows(Exception.class, () -> Key.of(null));
    }

    @Test
    @DisplayName("Key.of rejects empty partitionKey")
    void keyOfEmptyPartitionKey() {
        assertThrows(Exception.class, () -> Key.of(""));
    }

    @Test
    @DisplayName("Key.of rejects blank partitionKey")
    void keyOfBlankPartitionKey() {
        assertThrows(Exception.class, () -> Key.of("   "));
    }

    @Test
    @DisplayName("valid key with partition and sort key succeeds")
    void validKeyWithPartitionAndSortKey() {
        Key key = Key.of("partition-1", "valid-key-test");
        assertDoesNotThrow(() -> client.upsert(getAddress(), key, java.util.Map.of("title", "valid")));
        try { client.delete(getAddress(), key); } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("valid key without sort key succeeds")
    void validKeyWithoutSortKey() {
        Key key = Key.of("valid-key-no-part");
        assertDoesNotThrow(() -> client.upsert(getAddress(), key, java.util.Map.of("title", "valid")));
        try { client.delete(getAddress(), Key.of("valid-key-no-part", "valid-key-no-part")); } catch (Exception ignored) {}
    }
}
