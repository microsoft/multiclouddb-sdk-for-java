// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeDocumentsTest {
    @Test
    void unsupportedNativeValuesCannotEscapeAsNeutralReadOrFeedValues() {
        byte[] binary = {1, 2};
        for (Object input : List.of(binary, Map.of("nested", List.of(binary)))) {
            for (boolean feed : List.of(false, true)) {
                MulticloudDbException failure = assertThrows(MulticloudDbException.class, () -> {
                    if (feed) NativeDocuments.changeData(input);
                    else NativeDocuments.document(input);
                });
                assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, failure.error().category());
                assertEquals(ProviderId.COSMOS, failure.error().provider());
                assertEquals(feed ? "readChanges" : "read", failure.error().operation());
                assertFalse(failure.error().retryable());
                assertEquals(Map.of("reason", "invalid_document_payload"), failure.error().providerDetails());
                assertEquals("Provider response cannot be represented as neutral data.", failure.error().message());
                assertNull(failure.getCause());
            }
        }

    }

    @Test
    void writesNormalizeOnlyLegacyDecimalsAndDetachNestedContainers() {
        var nested = new java.util.ArrayList<>(java.util.Arrays.asList(null, new java.math.BigDecimal("1.00")));
        Map<String, Object> input = Map.of("items", nested);
        Map<String, Object> nativeMap = NativeDocuments.write(input);
        assertEquals(Map.of("items", java.util.Arrays.asList(null, java.math.BigDecimal.ONE)), nativeMap);
        assertEquals(new java.math.BigDecimal("1.00"), nested.get(1));
        nested.clear();
        assertEquals(2, ((List<?>) nativeMap.get("items")).size());
    }

    @Test
    void readsFilterOnlyRootSystemFieldsAndReturnMutableDetachedData() {
        Map<String, Object> raw = Map.of("_etag", "version", "nested", Map.of("_etag", "user-value"));
        Map<String, Object> result = NativeDocuments.document(raw);
        assertFalse(result.containsKey("_etag"));
        assertEquals(Map.of("_etag", "user-value"), result.get("nested"));
        ((Map<?, ?>) result.get("nested")).clear();
        assertEquals(Map.of("_etag", "user-value"), raw.get("nested"));
        result.put("mutable", true);
    }

    @Test
    void queryRowsPreserveNestedShapesButRejectTopLevelScalarsAndArrays() {
        assertEquals(Map.of("v", List.of(1, true)), NativeDocuments.queryRow(Map.of("v", List.of(1, true))));
        assertThrows(IllegalArgumentException.class, () -> NativeDocuments.queryRow(List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> NativeDocuments.queryRow(1));
    }

    @Test
    void doesNotReintroduceUnshippedModelNumberOrDepthCaps() {
        Object nested = Map.of("number", java.math.BigInteger.TEN.pow(1500));
        for (int i = 0; i < 129; i++) nested = List.of(nested);
        assertNotNull(NativeDocuments.changeData(nested));
        var decimal = new java.math.BigDecimal("1E+1025");
        assertEquals(decimal, NativeDocuments.changeData(decimal));
    }
}
