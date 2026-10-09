// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NumberValueTest {
    @Test
    void mathematicalEqualityRetainsRepresentationAndScale() {
        BigDecimal original = new BigDecimal("1.00");
        NumberValue decimal = NumberValue.of(original);
        for (Number number : List.of((byte) 1, (short) 1, 1, 1L, BigInteger.ONE,
                new BigDecimal("1.0"), 1F, 1D)) {
            assertEquals(decimal, NumberValue.of(number));
            assertEquals(decimal.hashCode(), NumberValue.of(number).hashCode());
        }
        assertSame(original, decimal.value());
        assertSame(original, decimal.decimalValue());
        assertEquals(2, decimal.decimalValue().scale());
        assertEquals(new BigDecimal("0.1"), NumberValue.of(0.1D).decimalValue());
        assertEquals(new BigDecimal("0.1"), NumberValue.of(0.1F).decimalValue());
    }

    @Test
    void preservesFloatingSignAndKindWhileZerosCompareEqually() {
        NumberValue negative = NumberValue.of(-0.0D);
        assertEquals(Double.doubleToRawLongBits(-0.0D),
                Double.doubleToRawLongBits(negative.value().doubleValue()));
        assertInstanceOf(Double.class, negative.value());
        assertEquals(NumberValue.of(0), negative);
        assertEquals(NumberValue.of(0).hashCode(), negative.hashCode());
        assertEquals(Float.floatToRawIntBits(-0.0F),
                Float.floatToRawIntBits(NumberValue.of(-0.0F).value().floatValue()));
    }

    @Test
    void acceptsFiniteFloatingExtremesWithoutRoundingThePayload() {
        for (Number number : List.of(Float.MIN_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE)) {
            assertSame(number, NumberValue.of(number).value());
        }
        assertEquals(BigDecimal.valueOf(Long.MIN_VALUE), NumberValue.of(Long.MIN_VALUE).decimalValue());
    }

    @Test
    void rejectsNonfiniteAndUnknownMutableNumberKinds() {
        for (Number number : List.of(Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> NumberValue.of(number));
        }
        assertThrows(IllegalArgumentException.class, () -> NumberValue.of(new AtomicInteger(1)));
    }

    @Test
    void enforcesPrecisionAndScaleBeforeNormalizing() {
        BigInteger atLimit = BigInteger.TEN.pow(1024).subtract(BigInteger.ONE);
        assertDoesNotThrow(() -> NumberValue.of(atLimit));
        assertDoesNotThrow(() -> NumberValue.of(atLimit.negate()));
        assertThrows(IllegalArgumentException.class, () -> NumberValue.of(BigInteger.TEN.pow(1024)));
        assertThrows(IllegalArgumentException.class,
                () -> NumberValue.of(new BigDecimal(BigInteger.TEN.pow(1024), 1024)));
        for (int scale : new int[]{-1024, -1023, 1023, 1024}) {
            assertDoesNotThrow(() -> NumberValue.of(new BigDecimal(BigInteger.ONE, scale)));
        }
        for (int scale : new int[]{-1025, 1025, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> NumberValue.of(new BigDecimal(BigInteger.ZERO, scale)));
        }
        BigDecimal trailingZeros = new BigDecimal(BigInteger.TEN.pow(1023), -1024);
        NumberValue normalized = NumberValue.of(trailingZeros);
        assertEquals(normalized.hashCode(), NumberValue.of(trailingZeros).hashCode());
        assertSame(trailingZeros, normalized.value());
    }
}
