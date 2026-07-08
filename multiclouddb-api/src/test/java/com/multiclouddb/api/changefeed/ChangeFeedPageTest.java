// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.MulticloudDbKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeFeedPageTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ChangeEvent evt(String key) {
        ObjectNode data = M.createObjectNode().put("k", key);
        return new ChangeEvent(MulticloudDbKey.of(key), ChangeType.CREATE,
                Instant.parse("2025-01-01T00:00:00Z"), data, key);
    }

    @Test
    @DisplayName("events list is unmodifiable and defensively copied")
    void eventsListUnmodifiable() {
        ChangeEvent[] arr = new ChangeEvent[]{evt("a"), evt("b")};
        java.util.List<ChangeEvent> mutable = new java.util.ArrayList<>(List.of(arr));
        ChangeFeedPage page = new ChangeFeedPage(mutable, ChangeFeedCursor.now(), true, false);

        // Mutating the source list must not affect the page.
        mutable.add(evt("c"));
        assertEquals(2, page.events().size(), "page must hold a defensive copy");

        // Returned list must be unmodifiable.
        assertThrows(UnsupportedOperationException.class,
                () -> page.events().add(evt("z")));
    }

    @Test
    @DisplayName("null events is treated as empty")
    void nullEventsBecomesEmpty() {
        ChangeFeedPage page = new ChangeFeedPage(null, ChangeFeedCursor.now(), false, false);
        assertTrue(page.events().isEmpty());
    }

    @Test
    @DisplayName("nextCursor is required (non-null)")
    void nextCursorRequired() {
        assertThrows(NullPointerException.class,
                () -> new ChangeFeedPage(List.of(), null, false, false));
    }

    @Test
    @DisplayName("hasMore and terminal cannot both be true")
    void hasMoreAndTerminalMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChangeFeedPage(List.of(), ChangeFeedCursor.now(), true, true));
    }

    @Test
    @DisplayName("flags are preserved")
    void flagsPreserved() {
        ChangeFeedPage caughtUp = new ChangeFeedPage(List.of(), ChangeFeedCursor.now(), false, false);
        assertFalse(caughtUp.hasMore());
        assertFalse(caughtUp.isTerminal());

        ChangeFeedPage hasMore = new ChangeFeedPage(List.of(), ChangeFeedCursor.now(), true, false);
        assertTrue(hasMore.hasMore());
        assertFalse(hasMore.isTerminal());

        ChangeFeedPage terminal = new ChangeFeedPage(List.of(), ChangeFeedCursor.now(), false, true);
        assertFalse(terminal.hasMore());
        assertTrue(terminal.isTerminal());
    }
}
