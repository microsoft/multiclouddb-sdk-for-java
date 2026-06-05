// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChangeFeedRequest} and
 * {@link StartPosition} — sealed-type behavior and builder defaults.
 */
class ChangeFeedRequestTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "col");

    @Test
    @DisplayName("builder defaults: beginning + INCLUDE_IF_AVAILABLE")
    void builderDefaults() {
        ChangeFeedRequest r = ChangeFeedRequest.builder(ADDR).build();
        assertEquals(ADDR, r.address());
        assertTrue(r.startPosition() instanceof StartPosition.Beginning);
        assertEquals(NewItemStateMode.INCLUDE_IF_AVAILABLE, r.newItemStateMode());
        assertEquals(0, r.maxPageSize());
    }

    @Test
    @DisplayName("StartPosition factories return the expected variants")
    void startPositionFactories() {
        assertInstanceOf(StartPosition.Beginning.class, StartPosition.beginning());
        assertInstanceOf(StartPosition.Now.class, StartPosition.now());
        assertInstanceOf(StartPosition.FromContinuationToken.class,
                StartPosition.fromContinuationToken("abc"));
    }

    @Test
    @DisplayName("StartPosition.FromContinuationToken rejects null/blank tokens")
    void fromTokenRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> StartPosition.fromContinuationToken(null));
        assertThrows(IllegalArgumentException.class, () -> StartPosition.fromContinuationToken(""));
        assertThrows(IllegalArgumentException.class, () -> StartPosition.fromContinuationToken("   "));
    }

    @Test
    @DisplayName("ChangeFeedRequest.builder rejects negative maxPageSize")
    void rejectsNegativePageSize() {
        assertThrows(IllegalArgumentException.class,
                () -> ChangeFeedRequest.builder(ADDR).maxPageSize(-1));
    }
}
