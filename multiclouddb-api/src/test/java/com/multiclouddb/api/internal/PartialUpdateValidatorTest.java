// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
    @DisplayName("ten fields are accepted")
    void maximumFieldCountAccepted() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < PartialUpdateValidator.MAX_FIELDS; i++) {
            fields.put("field" + i, i);
        }
        assertDoesNotThrow(() -> PartialUpdateValidator.validate(
                fields, OperationOptions.defaults(), OperationNames.UPDATE));
    }

    @Test
    @DisplayName("more than ten fields are rejected")
    void fieldCountOverLimitRejected() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i <= PartialUpdateValidator.MAX_FIELDS; i++) {
            fields.put("field" + i, i);
        }
        assertFieldCountDetails(reject(fields, OperationOptions.defaults()));
    }

    private static void assertFieldCountDetails(MulticloudDbException ex) {
        assertInvalidRequest(ex);
        assertEquals(Map.of(
                "reason", "partial_update_field_count_limit",
                "maximumFields", "10",
                "observedFields", "11"), ex.error().providerDetails());
    }

    @Test
    @DisplayName("observed field count is a lower bound, not the total map size")
    void largerMapReportsOnlyObservedFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            fields.put("field" + i, i);
        }
        assertFieldCountDetails(reject(fields, OperationOptions.defaults()));
    }

    @Test
    @DisplayName("misleading unbounded maps stop at the field-count limit")
    void unboundedMapInspectionIsBounded() {
        Map<String, Object> unbounded = new AbstractMap<>() {
            @Override
            public int size() {
                throw new AssertionError("validation must not request the total map size");
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Object>> iterator() {
                        return new Iterator<>() {
                            private int index;

                            @Override
                            public boolean hasNext() {
                                return true;
                            }

                            @Override
                            public Entry<String, Object> next() {
                                if (index > PartialUpdateValidator.MAX_FIELDS) {
                                    throw new AssertionError("inspection must stop at the first excess field");
                                }
                                return Map.entry("field" + index, index++);
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                };
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertFieldCountDetails(reject(
                        unbounded, OperationOptions.defaults())));
    }

    @Test
    @DisplayName("map iterator failures are structured invalid requests")
    void mapIteratorFailuresAreInvalidRequests() {
        Map<String, Object> failing = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("iterator unavailable");
            }
        };

        MulticloudDbException ex = reject(failing, OperationOptions.defaults());

        assertInvalidRequest(ex);
        assertEquals("portable_value_snapshot_failed",
                ex.error().providerDetails().get("reason"));
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
    @DisplayName("field name at the UTF-8 byte limit is accepted")
    void maximumFieldNameBytesAccepted() {
        Map<String, Object> fields = Map.of(
                "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES), "v");

        assertDoesNotThrow(() -> PartialUpdateValidator.validate(
                fields, OperationOptions.defaults(), OperationNames.UPDATE));
    }

    @Test
    @DisplayName("field name over the UTF-8 byte limit is rejected with stable details")
    void fieldNameOverByteLimitRejected() {
        Map<String, Object> fields = Map.of(
                "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 1), "v");

        MulticloudDbException ex = reject(fields, OperationOptions.defaults());

        assertInvalidRequest(ex);
        assertEquals(PartialUpdateValidator.FIELD_NAME_SIZE_LIMIT_REASON,
                ex.error().providerDetails().get("reason"));
        assertEquals(String.valueOf(PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 1),
                ex.error().providerDetails().get("actualFieldNameBytes"));
        assertEquals(String.valueOf(PartialUpdateValidator.MAX_FIELD_NAME_BYTES),
                ex.error().providerDetails().get("maximumFieldNameBytes"));
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
    @DisplayName("foo/Foo case variants are accepted as distinct fields")
    void caseVariantsAccepted() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("foo", 1);
        f.put("Foo", 2);
        assertDoesNotThrow(() -> PartialUpdateValidator.validate(
                f, OperationOptions.defaults(), OperationNames.UPDATE));
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
