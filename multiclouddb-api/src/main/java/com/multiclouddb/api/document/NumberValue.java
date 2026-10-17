// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * An immutable number with retained JDK representation and mathematical decimal
 * equality. Floating-point decimal views follow the supported runtime's
 * {@link Float#toString(float)} or {@link Double#toString(double)}, not an exact
 * binary expansion. The original floating zero sign and BigDecimal scale remain
 * available through {@link #value()}.
 *
 * <p>Precision is at most 1024 digits and stored decimal scale is between -1024
 * and 1024 inclusive. Nonfinite values and other Number implementations are
 * rejected. These in-memory bounds are not provider storage guarantees.
 */
public final class NumberValue implements DocumentValue {
    private final Number value;
    private final BigDecimal decimal;
    private final BigDecimal equalityKey;

    private NumberValue(Number value, BigDecimal decimal) {
        this.value = value;
        this.decimal = decimal;
        this.equalityKey = decimal.signum() == 0
                ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    }

    /** Creates a value without rounding or rewriting its original representation. */
    public static NumberValue of(Number value) {
        Objects.requireNonNull(value, "value");
        Class<?> kind = value.getClass();
        BigDecimal decimal;
        if (kind == BigDecimal.class) {
            decimal = (BigDecimal) value;
            checkMagnitude(decimal.unscaledValue());
        } else if (kind == BigInteger.class) {
            checkMagnitude((BigInteger) value);
            decimal = new BigDecimal((BigInteger) value);
        } else if (kind == Byte.class || kind == Short.class
                || kind == Integer.class || kind == Long.class) {
            decimal = BigDecimal.valueOf(value.longValue());
        } else if (kind == Float.class) {
            if (!Float.isFinite(value.floatValue())) {
                throw new IllegalArgumentException("Nonfinite document number.");
            }
            decimal = new BigDecimal(Float.toString(value.floatValue()));
        } else if (kind == Double.class) {
            if (!Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException("Nonfinite document number.");
            }
            decimal = BigDecimal.valueOf(value.doubleValue());
        } else {
            throw new IllegalArgumentException("Unsupported document number implementation.");
        }
        if (decimal.scale() < -1024 || decimal.scale() > 1024 || decimal.precision() > 1024) {
            throw new IllegalArgumentException("Document number exceeds precision or scale limit.");
        }
        return new NumberValue(value, decimal);
    }

    private static void checkMagnitude(BigInteger value) {
        // 10^1024 has 3402 bits; the decimal precision check handles that boundary.
        if (value.bitLength() > 3402) {
            throw new IllegalArgumentException("Document number exceeds precision limit.");
        }
    }

    /** Returns the original, factory-whitelisted immutable JDK number. */
    public Number value() {
        return value;
    }

    /** Returns the unrounded decimal view, retaining its original scale. */
    public BigDecimal decimalValue() {
        return decimal;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof NumberValue number
                && equalityKey.equals(number.equalityKey);
    }

    @Override
    public int hashCode() {
        return equalityKey.hashCode();
    }

    @Override
    public String toString() {
        return "NumberValue[kind=" + value.getClass().getSimpleName() + "]";
    }
}
