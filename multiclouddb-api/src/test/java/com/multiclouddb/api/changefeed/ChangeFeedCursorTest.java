// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.changefeed.internal.CursorToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChangeFeedCursorTest {

    @Test
    @DisplayName("now() returns an unhydrated sentinel with the sentinel provider id")
    void nowReturnsUnhydratedSentinel() {
        ChangeFeedCursor c = ChangeFeedCursor.now();
        assertTrue(c.isUnhydratedSentinel(),
                "now() must be classified as an unhydrated sentinel");
        assertEquals(ChangeFeedCursor.SENTINEL_PROVIDER, c.token().providerId(),
                "sentinel must carry the SENTINEL_PROVIDER id, not any real provider");
        assertNull(c.token().resource(),
                "sentinel must not carry a resource binding (bound on first read)");
        assertTrue(c.token().partitions().isEmpty(),
                "sentinel must have no partition positions");
    }

    @Test
    @DisplayName("now() returns a fresh instance each call but all are unhydrated sentinels")
    void nowReturnsFreshInstances() {
        ChangeFeedCursor a = ChangeFeedCursor.now();
        ChangeFeedCursor b = ChangeFeedCursor.now();
        assertNotSame(a, b);
        assertTrue(a.isUnhydratedSentinel() && b.isUnhydratedSentinel());
    }

    @Test
    @DisplayName("toToken round-trips through fromToken into an equal CursorToken")
    void toTokenRoundTrip() {
        ChangeFeedCursor original = ChangeFeedCursor.now();
        String wire = original.toToken();
        ChangeFeedCursor decoded = ChangeFeedCursor.fromToken(wire);
        assertEquals(original.token().providerId(), decoded.token().providerId());
        assertEquals(original.token().anchor(), decoded.token().anchor());
        assertEquals(original.token().partitions(), decoded.token().partitions());
        assertNull(decoded.token().resource(), "sentinel must remain unbound after round-trip");
    }

    @Test
    @DisplayName("fromToken on garbage raises CursorExpiredException")
    void fromTokenGarbageThrows() {
        assertThrows(CursorExpiredException.class, () -> ChangeFeedCursor.fromToken("not-a-real-token"));
    }

    @Test
    @DisplayName("constructor rejects null token")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ChangeFeedCursor((CursorToken) null));
    }
}
