// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.TestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Changes JVM default locales; restores them before returning")
class CursorFieldCoercionsCompatibilityTest {
    private record Coercion(String json, int integer, long longValue, String text, boolean fitsLong) {}

    @Test
    void standardNumericTextAndPublicCursorWireAreLocaleIndependent() {
        List<Number> numbers = List.of((byte) -128, (short) 32767, Integer.MIN_VALUE, Long.MAX_VALUE,
                1234.5f, -0.0d, Double.MIN_VALUE, Double.POSITIVE_INFINITY,
                new BigInteger("-9223372036854775809"), new BigDecimal("1234.5000"),
                new BigDecimal("1E+20"));
        List<String> expected = List.of("-128", "32767", "-2147483648", "9223372036854775807",
                "1234.5", "-0.0", "4.9E-324", "Infinity", "-9223372036854775809",
                "1234.5000", "1E+20");
        Locale original = Locale.getDefault();
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        long issuedAt = System.currentTimeMillis();
        var baselineWires = new ArrayList<String>();
        try {
            for (Locale defaultLocale : List.of(Locale.US, Locale.GERMANY, Locale.FRANCE)) {
                for (Locale formatLocale : List.of(Locale.US, Locale.GERMANY, Locale.FRANCE)) {
                    Locale.setDefault(defaultLocale);
                    Locale.setDefault(Locale.Category.FORMAT, formatLocale);
                    for (int i = 0; i < numbers.size(); i++) {
                        Number value = numbers.get(i);
                        assertEquals(expected.get(i), CursorFieldCoercions.text(value, null));
                        int[] callbacks = {0};
                        ChangeFeedCursor cursor = ChangeFeedCursor.fromToken("e30", (text, target) -> {
                            assertEquals(Object.class, target);
                            callbacks[0]++;
                            return Map.of("v", 1, "p", "cosmos", "i", issuedAt, "a", "CONTINUING",
                                    "s", List.of(Map.of("id", value, "c", value)));
                        });
                        assertEquals(1, callbacks[0]);
                        assertEquals(expected.get(i), cursor.token().partitions().get(0).partitionId());
                        assertEquals(expected.get(i), cursor.token().partitions().get(0).continuation());
                        String wire = cursor.toToken(TestJson.SERIALIZE);
                        assertEquals(cursor.token(),
                                ChangeFeedCursor.fromToken(wire, TestJson.DESERIALIZE).token());
                        if (baselineWires.size() < numbers.size()) baselineWires.add(wire);
                        else assertEquals(baselineWires.get(i), wire);
                    }
                }
            }
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.FORMAT, format);
            Locale.setDefault(Locale.Category.DISPLAY, display);
        }
    }

    @Test
    void matchesExistingFieldCoercionForAdmittedJsonKinds() throws IOException {
        // Captured from the released Jackson 2.18.6 cursor field coercions.
        for (Coercion fixture : List.of(
                new Coercion("null", -9, -9L, null, false),
                new Coercion("true", 1, 1L, "true", false),
                new Coercion("false", 0, 0L, "false", false),
                new Coercion("0", 0, 0L, "0", true),
                new Coercion("1", 1, 1L, "1", true),
                new Coercion("-1", -1, -1L, "-1", true),
                new Coercion("1.9", 1, 1L, "1.9", true),
                new Coercion("1e20", 2147483647, 9223372036854775807L, "1.0E20", false),
                new Coercion("1e999", 2147483647, 9223372036854775807L, "Infinity", false),
                new Coercion("9223372036854775807", -1, 9223372036854775807L, "9223372036854775807", true),
                new Coercion("9223372036854775808", 0, -9223372036854775808L, "9223372036854775808", false),
                new Coercion("-9223372036854775809", -1, 9223372036854775807L, "-9223372036854775809", false),
                new Coercion("2147483648", -2147483648, 2147483648L, "2147483648", true),
                new Coercion("\"1.5\"", 1, 1L, "1.5", false),
                new Coercion("\"  +12 \"", 12, 12L, "  +12 ", false),
                new Coercion("\"2147483648\"", -9, 2147483648L, "2147483648", false),
                new Coercion("\"9223372036854775808\"", -9, -9L, "9223372036854775808", false),
                new Coercion("\"nonnumeric\"", -9, -9L, "nonnumeric", false),
                new Coercion("\"NaN\"", 0, 0L, "NaN", false),
                new Coercion("\"Infinity\"", 2147483647, 9223372036854775807L, "Infinity", false),
                new Coercion("[]", -9, -9L, "", false),
                new Coercion("{}", -9, -9L, "", false))) {
            Object next = JsonSupport.read(fixture.json(), TestJson.DESERIALIZE);
            assertEquals(fixture.integer(), CursorFieldCoercions.asInt(next, -9), "int: " + fixture.json());
            assertEquals(fixture.longValue(), CursorFieldCoercions.asLong(next, -9), "long: " + fixture.json());
            assertEquals(fixture.text(), CursorFieldCoercions.text(next, null), "text: " + fixture.json());
            assertEquals(fixture.fitsLong(), CursorFieldCoercions.canConvertToLong(next), "range: " + fixture.json());
        }
    }

    @Test
    void strictSyntaxAndDuplicateTrailingBehaviorMatchTheEstablishedBaseline() throws IOException {
        assertEquals(2, ((java.util.Map<?, ?>) JsonSupport.read(
                "{\"v\":1,\"v\":2} true", TestJson.DESERIALIZE)).get("v"));
        for (String invalid : List.of("{'v':1}", "{v:1}", "{\"v\":NaN}",
                "{\"v\":1,}", "{\"v\":01}", "{/*comment*/\"v\":1}")) {
            assertThrows(IOException.class, () -> JsonSupport.read(invalid, TestJson.DESERIALIZE), invalid);
        }
    }

    @Test
    void writerAndReaderPreserveOpaqueUnicodeData() throws IOException {
        for (String value : List.of("normal ASCII", "</>&", "\b\f\n\r\t", "\u2028\u2029",
                "\u00e9 \ud83d\ude00", "\ud800", "\udc00", "\\uD800")) {
            CursorToken original = new CursorToken(com.multiclouddb.api.ProviderId.COSMOS, null,
                    0, CursorAnchor.NOW, List.of(new PartitionPosition("p", value)));
            String wire = CursorTokenCodec.encode(original, TestJson.SERIALIZE);
            assertEquals(value, CursorTokenCodec.decode(wire, 0, TestJson.DESERIALIZE)
                    .partitions().get(0).continuation());
        }
    }

    @Test
    void preservesExistingParserDepthAndIntegerLimits() throws IOException {
        String atDepth = "[".repeat(1000) + "null" + "]".repeat(1000);
        assertNotNull(JsonSupport.read(atDepth, TestJson.DESERIALIZE));
        for (String invalid : List.of("[" + atDepth + "]", "1".repeat(1001),
                "-" + "1".repeat(1001))) {
            assertThrows(IOException.class, () -> JsonSupport.read(invalid, TestJson.DESERIALIZE));
        }
    }
}
