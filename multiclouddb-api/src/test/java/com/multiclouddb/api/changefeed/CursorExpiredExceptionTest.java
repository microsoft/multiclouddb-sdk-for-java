// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CursorExpiredExceptionTest {

    @Test
    @DisplayName("constructor accepts CURSOR_EXPIRED error and preserves it")
    void constructorAcceptsExpiredCategory() {
        MulticloudDbError err = new MulticloudDbError(
                MulticloudDbErrorCategory.CURSOR_EXPIRED,
                "trimmed",
                null,
                "readChanges",
                false,
                Map.of("reason", "TRIMMED"));
        CursorExpiredException ex = new CursorExpiredException(err);
        assertSame(err, ex.error());
        assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, ex.error().category());
    }

    @Test
    @DisplayName("constructor rejects wrong error category")
    void constructorRejectsWrongCategory() {
        MulticloudDbError wrong = new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                "wrong",
                null,
                "readChanges",
                false,
                Map.of());
        assertThrows(IllegalArgumentException.class, () -> new CursorExpiredException(wrong));
    }

    @Test
    @DisplayName("constructor rejects null error")
    void constructorRejectsNullError() {
        assertThrows(IllegalArgumentException.class, () -> new CursorExpiredException(null));
    }
}
