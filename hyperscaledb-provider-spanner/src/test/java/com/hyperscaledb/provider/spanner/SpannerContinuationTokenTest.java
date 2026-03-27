// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpannerContinuationTokenTest {

    @Test
    void encodeDecodeRoundTrip() {
        String token = SpannerContinuationToken.encode(50);
        assertNotNull(token);
        assertFalse(token.contains("50"), "Token should be opaque (Base64-encoded)");

        long decoded = SpannerContinuationToken.decode(token);
        assertEquals(50, decoded);
    }

    @Test
    void encodeDecodeMultipleOffsets() {
        for (long offset : new long[] { 1, 10, 25, 100, 999, 1_000_000 }) {
            String token = SpannerContinuationToken.encode(offset);
            assertNotNull(token, "Token should not be null for offset " + offset);
            assertEquals(offset, SpannerContinuationToken.decode(token),
                    "Round-trip failed for offset " + offset);
        }
    }

    @Test
    void encodeZeroReturnsNull() {
        assertNull(SpannerContinuationToken.encode(0));
    }

    @Test
    void encodeNegativeReturnsNull() {
        assertNull(SpannerContinuationToken.encode(-1));
        assertNull(SpannerContinuationToken.encode(-100));
    }

    @Test
    void decodeNullReturnsZero() {
        assertEquals(0, SpannerContinuationToken.decode(null));
    }

    @Test
    void decodeEmptyReturnsZero() {
        assertEquals(0, SpannerContinuationToken.decode(""));
        assertEquals(0, SpannerContinuationToken.decode("   "));
    }

    @Test
    void decodeGarbageReturnsZero() {
        assertEquals(0, SpannerContinuationToken.decode("not-a-valid-token!!!"));
    }

    @Test
    void tokenIsUrlSafe() {
        String token = SpannerContinuationToken.encode(12345);
        assertNotNull(token);
        // URL-safe Base64 uses only [A-Za-z0-9_-] (no + / =)
        assertTrue(token.matches("[A-Za-z0-9_-]+"), "Token should be URL-safe Base64");
    }
}
