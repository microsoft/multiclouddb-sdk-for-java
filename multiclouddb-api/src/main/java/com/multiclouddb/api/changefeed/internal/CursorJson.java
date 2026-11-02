// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import java.math.BigInteger;

/** The v1 cursor field-coercion profile, independent of JSON syntax processing. */
final class CursorJson {
    private CursorJson() {}

    static String text(Object value, String nullValue) {
        if (value == null) return nullValue;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value.toString();
        return "";
    }

    static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        if (value instanceof String string) {
            String text = string.trim();
            try {
                return integralText(text) ? Integer.parseInt(text) : (int) Double.parseDouble(text);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static long asLong(Object value, long defaultValue) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        if (value instanceof String string) {
            String text = string.trim();
            try {
                return integralText(text) ? Long.parseLong(text) : (long) Double.parseDouble(text);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static boolean canConvertToLong(Object value) {
        if (!(value instanceof Number number)) return false;
        if (number instanceof BigInteger integer) {
            return integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                    && integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
        }
        double floating = number.doubleValue();
        return floating >= Long.MIN_VALUE && floating <= Long.MAX_VALUE;
    }

    private static boolean integralText(String value) {
        int start = !value.isEmpty() && (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (start == value.length()) return false;
        for (int i = start; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }
}
