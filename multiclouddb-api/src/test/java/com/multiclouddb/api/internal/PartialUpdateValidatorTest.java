// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the shared partial-update preflight {@link PartialUpdateValidator}.
 * Every rejection must be a non-retryable INVALID_REQUEST.
 */
class PartialUpdateValidatorTest {

    private static MulticloudDbException reject(Map<String, Object> fields, OperationOptions options) {
        return assertThrows(MulticloudDbException.class,
                () -> PartialUpdateValidator.validate(fields, options, OperationNames.UPDATE));
    }

    private static void assertInvalidRequest(MulticloudDbException ex) {
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals(false, ex.error().retryable(), "shared validation failures are non-retryable");
        assertEquals(OperationNames.UPDATE, ex.error().operation());
    }

    @Test
    @DisplayName("null map is rejected")
    void nullMapRejected() {
        assertInvalidRequest(reject(null, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("empty map is rejected")
    void emptyMapRejected() {
        assertInvalidRequest(reject(Map.of(), OperationOptions.defaults()));
    }

    @Test
    @DisplayName("null field name is rejected")
    void nullNameRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(null, "v");
        assertInvalidRequest(reject(f, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("empty field name is rejected")
    void emptyNameRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("", "v");
        assertInvalidRequest(reject(f, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("blank (whitespace-only) field name is rejected")
    void blankNameRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("   ", "v");
        assertInvalidRequest(reject(f, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("accepted non-trimmed literal name is not rejected and not rewritten")
    void nonTrimmedNameAccepted() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(" customer ", "v");
        assertDoesNotThrow(() -> PartialUpdateValidator.validate(f, OperationOptions.defaults(), OperationNames.UPDATE));
        assertTrue(f.containsKey(" customer "), "validator must not trim or rewrite accepted names");
    }

    @Test
    @DisplayName("reserved names are rejected case-insensitively")
    void reservedNamesRejected() {
        for (String reserved : new String[] {"id", "ID", "partitionKey", "PARTITIONKEY", "sortKey",
                "ttl", "ttlExpiry", "TtlExpiry", "data", "DATA"}) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put(reserved, "v");
            assertInvalidRequest(reject(f, OperationOptions.defaults()));
        }
    }

    @Test
    @DisplayName("underscore-prefixed names are rejected")
    void underscorePrefixRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("_hidden", "v");
        assertInvalidRequest(reject(f, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("foo/Foo case-insensitive collision is rejected")
    void caseCollisionRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("foo", 1);
        f.put("Foo", 2);
        assertInvalidRequest(reject(f, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("exact . / ~ names are accepted literal top-level fields")
    void punctuationNamesAccepted() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(".", "dot");
        f.put("/", "slash");
        f.put("~", "tilde");
        assertDoesNotThrow(() -> PartialUpdateValidator.validate(f, OperationOptions.defaults(), OperationNames.UPDATE));
    }

    @Test
    @DisplayName("non-null ttlSeconds is rejected")
    void ttlRejected() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "SHIPPED");
        OperationOptions withTtl = OperationOptions.builder().ttlSeconds(3600).build();
        assertInvalidRequest(reject(f, withTtl));
    }

    @Test
    @DisplayName("ordinary valid fields with default options pass")
    void validFieldsPass() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "SHIPPED");
        f.put("owner", "ana");
        assertDoesNotThrow(() -> PartialUpdateValidator.validate(f, OperationOptions.defaults(), OperationNames.UPDATE));
        assertDoesNotThrow(() -> PartialUpdateValidator.validate(f, null, OperationNames.UPDATE));
    }
}
