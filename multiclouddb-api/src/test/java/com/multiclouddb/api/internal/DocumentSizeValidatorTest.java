// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exact common-size boundary for the shared {@link DocumentSizeValidator}: a
 * serialized field map of exactly 408,576 bytes (399 KiB) passes; 408,577 bytes
 * fails with a non-retryable INVALID_REQUEST. The validator performs no provider
 * I/O, so a rejection delegates zero provider operations by construction.
 */
class DocumentSizeValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("MAX_BYTES is exactly 408,576 bytes (399 KiB)")
    void maxBytesIsExact() {
        assertEquals(408_576, DocumentSizeValidator.MAX_BYTES);
    }

    /** Builds a single-field map whose serialized JSON is exactly {@code targetBytes} long. */
    private static Map<String, Object> mapOfSerializedSize(int targetBytes) throws Exception {
        int overhead = MAPPER.writeValueAsBytes(Map.of("p", "")).length; // {"p":""} == 8 bytes
        String value = "A".repeat(targetBytes - overhead);
        Map<String, Object> map = Map.of("p", value);
        assertEquals(targetBytes, MAPPER.writeValueAsBytes(map).length,
                "test fixture must serialize to the exact target size");
        return map;
    }

    @Test
    @DisplayName("exactly 408,576 bytes passes common preflight")
    void exactLimitPasses() throws Exception {
        Map<String, Object> atLimit = mapOfSerializedSize(408_576);
        assertDoesNotThrow(() -> DocumentSizeValidator.validate(atLimit, OperationNames.UPDATE));
    }

    @Test
    @DisplayName("408,577 bytes fails with non-retryable INVALID_REQUEST and zero delegation")
    void oneOverLimitFails() throws Exception {
        Map<String, Object> overLimit = mapOfSerializedSize(408_577);
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DocumentSizeValidator.validate(overLimit, OperationNames.UPDATE));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(false, ex.error().retryable());
        // The static validator performs no provider call, so a rejection is inherently zero-I/O.
    }
}
