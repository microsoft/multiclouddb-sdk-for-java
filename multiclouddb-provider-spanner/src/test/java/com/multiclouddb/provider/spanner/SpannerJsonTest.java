// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpannerJsonTest {
    @Test
    void preservesScalarTypesNullsAndReleasedIntegerWidths() throws Exception {
        Object parsed = SpannerJson.read("[null,true,1,2147483648,9223372036854775808,1.5,-0.0]");
        assertEquals(Arrays.asList(null, true, 1, 2147483648L,
                new BigInteger("9223372036854775808"), 1.5, -0.0), parsed);
        assertEquals(Map.of("last", 2), SpannerJson.read("{\"last\":1,\"last\":2} ignored trailing text"));
    }

    @Test
    void preservesReleasedPersistedStringAndNumericEncoding() throws Exception {
        String text = "<>&='\u2028\u2029\\u2028\uD55C\uAE00\uD83D\uDE00";
        assertEquals("\"" + "<>&='\u2028\u2029\\\\u2028\uD55C\uAE00\uD83D\uDE00" + "\"",
                SpannerJson.write(text));
        assertEquals(text, SpannerJson.read(SpannerJson.write(text)));
        assertEquals("{\"key\\n\":null}", SpannerJson.write(java.util.Collections.singletonMap("key\n", null)));
        assertEquals("\"\\u000B\\u000E\\u001F\\\\u000b\"",
                SpannerJson.write("\u000b\u000e\u001f\\u000b"));
        assertEquals("[1.00,0.000,1E+20,0.1,-0.0]", SpannerJson.write(
                List.of(new BigDecimal("1.00"), new BigDecimal("0.000"),
                        new BigDecimal("1E+20"), 0.1F, -0.0D)));
    }

    @Test
    void strictUnicodeAcceptsValidPairsButRejectsRawAndEscapedLoneSurrogates() throws Exception {
        String valid = "\uD55C\uAE00\uD83D\uDE00";
        assertEquals(valid, SpannerJson.read("\"\\ud55c\\uae00\\ud83d\\ude00\""));
        assertEquals(Map.of(valid, valid), SpannerJson.read(SpannerJson.write(Map.of(valid, valid))));
        assertEquals(List.of(valid), SpannerJson.fieldNames(SpannerJson.write(List.of(valid))));
        for (String invalid : List.of("\ud800", "\udc00", "\\ud800", "\\udc00")) {
            assertThrows(IOException.class, () -> SpannerJson.read("\"" + invalid + "\""));
            assertThrows(IOException.class, () -> SpannerJson.read("{\"" + invalid + "\":1}"));
            assertThrows(IOException.class, () -> SpannerJson.fieldNames("[\"" + invalid + "\"]"));
        }
        // Writing is unchanged; strict read rejection is not an escaping workaround.
        assertEquals("\"\ud800\"", SpannerJson.write("\ud800"));
        assertEquals("\"\udc00\"", SpannerJson.write("\udc00"));
    }

    @Test
    void keepsLegacyMetadataScalarToStringCoercion() throws Exception {
        assertEquals(Arrays.asList("x", "2", "true", null), SpannerJson.fieldNames("[\"x\",2,true,null]"));
        assertEquals(List.of("2.00", "1e+03", "-0"), SpannerJson.fieldNames("[2.00,1e+03,-0]"));
        assertThrows(IOException.class, () -> SpannerJson.fieldNames("[{}]"));
        assertThrows(IOException.class, () -> SpannerJson.fieldNames(""));
        assertThrows(IOException.class, () -> SpannerJson.fieldNames("  "));
        assertThrows(IOException.class, () -> SpannerJson.read("\uFEFF{}"));
        assertThrows(IOException.class, () -> SpannerJson.read("{bad}"));
        assertThrows(IOException.class, () -> SpannerJson.read("[1,]"));
        assertThrows(IOException.class, () -> SpannerJson.read("NaN"));
    }

    @Test
    void metadataNumbersMatchReleasedLexicalLimitsWithoutChangingSpelling() throws Exception {
        var released = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String token : List.of("1".repeat(1000), "-" + "1".repeat(1000),
                "0." + "9".repeat(1000), "-0." + "9".repeat(1000),
                "1" + "0".repeat(998) + "e+1", "1e+" + "0".repeat(998) + "1",
                "2.00", "1e+03", "-0", "0e+1")) {
            String json = "[" + token + "]";
            assertEquals(List.of(token), Arrays.asList(released.readValue(json, String[].class)));
            assertEquals(List.of(token), SpannerJson.fieldNames(json));
        }
        for (String token : List.of("1".repeat(1001), "-" + "1".repeat(1001),
                "0." + "9".repeat(1001), "-0." + "9".repeat(1001),
                "1" + "0".repeat(999) + "e+1", "1e+" + "0".repeat(999) + "1")) {
            String json = "[\"kept\"," + token + "]";
            assertThrows(IOException.class, () -> released.readValue(json, String[].class));
            assertThrows(IOException.class, () -> SpannerJson.fieldNames(json));
        }
        String text = "1".repeat(1001);
        assertEquals(List.of(text), SpannerJson.fieldNames("[\"" + text + "\"]"),
                "numeric-looking strings are not numeric tokens");
    }

    @Test
    void readsPastRemovedModelDepthButRetainsNativeParserLimit() throws Exception {
        for (int depth : List.of(256, 1000)) {
            Object arrays = SpannerJson.read("[".repeat(depth) + "0" + "]".repeat(depth));
            Object objects = SpannerJson.read("{\"x\":".repeat(depth) + "0" + "}".repeat(depth));
            for (int i = 0; i < depth; i++) {
                arrays = assertInstanceOf(List.class, arrays).get(0);
                objects = assertInstanceOf(Map.class, objects).get("x");
            }
            assertEquals(0, arrays);
            assertEquals(0, objects);
        }
        assertThrows(IOException.class, () -> SpannerJson.read("[".repeat(1001) + "0" + "]".repeat(1001)));
        assertThrows(IOException.class,
                () -> SpannerJson.read("{\"x\":".repeat(1001) + "0" + "}".repeat(1001)));
    }
}
