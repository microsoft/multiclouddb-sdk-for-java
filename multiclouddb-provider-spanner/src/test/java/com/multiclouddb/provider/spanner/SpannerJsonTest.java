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
    void preservesPersistedStringEncodingRatherThanGsonHtmlOrSeparatorEscapes() throws Exception {
        String text = "<>&='\u2028\u2029\\u2028\ud800";
        assertEquals("\"" + "<>&='\u2028\u2029\\\\u2028\ud800" + "\"", SpannerJson.write(text));
        assertEquals(text, SpannerJson.read(SpannerJson.write(text)));
        assertEquals("{\"key\\n\":null}", SpannerJson.write(java.util.Collections.singletonMap("key\n", null)));
        assertEquals("\"\\u000B\\u000E\\u001F\\\\u000b\"",
                SpannerJson.write("\u000b\u000e\u001f\\u000b"));
        assertEquals("[1.00,0.000,1E+20,0.1,-0.0]", SpannerJson.write(
                List.of(new BigDecimal("1.00"), new BigDecimal("0.000"),
                        new BigDecimal("1E+20"), 0.1F, -0.0D)));
    }

    @Test
    void keepsLegacyMetadataScalarToStringCoercion() throws Exception {
        assertEquals(Arrays.asList("x", "2", "true", null), SpannerJson.fieldNames("[\"x\",2,true,null]"));
        assertEquals(List.of("2.00", "1e+03", "-0"), SpannerJson.fieldNames("[2.00,1e+03,-0]"));
        assertThrows(IOException.class, () -> SpannerJson.fieldNames("[{}]"));
        assertThrows(IOException.class, () -> SpannerJson.read("\uFEFF{}"));
        assertThrows(IOException.class, () -> SpannerJson.read("{bad}"));
        assertThrows(IOException.class, () -> SpannerJson.read("[1,]"));
        assertThrows(IOException.class, () -> SpannerJson.read("NaN"));
    }

    @Test
    void readsPastRemovedModelDepthButRetainsNativeParserLimit() throws Exception {
        String atLimit = "[".repeat(1000) + "0" + "]".repeat(1000);
        assertNotNull(SpannerJson.read(atLimit));
        assertThrows(IOException.class, () -> SpannerJson.read("[" + atLimit + "]"));
    }
}
