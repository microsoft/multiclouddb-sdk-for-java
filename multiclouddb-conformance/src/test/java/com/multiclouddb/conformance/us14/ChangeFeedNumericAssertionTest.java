// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.conformance.us14;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChangeFeedNumericAssertionTest {
    @Test
    void acceptsExactNumericValuesRegardlessOfNativeRepresentation() {
        for (Object value : List.of((byte) 1, (short) 1, 1, 1L, 1F, 1D,
                BigInteger.ONE, new BigDecimal("1.000"), "1", "1.0", "1e0")) {
            assertDoesNotThrow(() -> ChangeFeedConformanceTest.assertNumericValue(value, BigDecimal.ONE));
        }
        BigDecimal large = new BigDecimal("9223372036854775808.125");
        ChangeFeedConformanceTest.assertNumericValue(large, large);
        ChangeFeedConformanceTest.assertNumericValue(large.toString(), large);
    }

    @Test
    void rejectsTruncationWrongTypesAndNonNumericValues() {
        var coercions = new java.util.concurrent.atomic.AtomicInteger();
        Object arbitrary = new Object() {
            @Override
            public String toString() { coercions.incrementAndGet(); return "1"; }
        };
        for (Object value : Arrays.asList(null, true, Map.of(), List.of(1), arbitrary,
                "bad", "", "NaN", "Infinity", Double.NaN, Double.POSITIVE_INFINITY,
                "1.00000000000000000001", new BigDecimal("1.00000000000000000001"),
                1.5, "1.5", 2, "2", 0, "18446744073709551617")) {
            assertThrows(AssertionError.class,
                    () -> ChangeFeedConformanceTest.assertNumericValue(value, BigDecimal.ONE));
        }
        assertEquals(0, coercions.get(), "arbitrary objects must be rejected before toString");
    }
}
