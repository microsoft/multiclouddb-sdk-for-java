// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CursorJsonCompatibilityTest {
    private final ObjectMapper legacy = new ObjectMapper();

    @Test
    void matchesExistingFieldCoercionForAdmittedJsonKinds() throws IOException {
        for (String literal : List.of("null", "true", "false", "0", "1", "-1",
                "1.9", "1e20", "1e999", "9223372036854775807", "9223372036854775808",
                "-9223372036854775809", "2147483648", "\"1.5\"", "\"  +12 \"",
                "\"2147483648\"", "\"9223372036854775808\"", "\"nonnumeric\"",
                "\"NaN\"", "\"Infinity\"", "[]", "{}")) {
            JsonNode old = legacy.readTree(literal);
            JsonElement next = CursorJson.read(literal);
            assertEquals(old.asInt(-9), CursorJson.asInt(next, -9), "int: " + literal);
            assertEquals(old.asLong(-9), CursorJson.asLong(next, -9), "long: " + literal);
            assertEquals(old.asText(null), CursorJson.text(next, null), "text: " + literal);
            assertEquals(old.canConvertToLong(), CursorJson.canConvertToLong(next), "range: " + literal);
        }
    }

    @Test
    void strictSyntaxAndDuplicateTrailingBehaviorMatchTheEstablishedBaseline() throws IOException {
        assertEquals(2, CursorJson.read("{\"v\":1,\"v\":2} true").getAsJsonObject().get("v").getAsInt());
        for (String invalid : List.of("{'v':1}", "{v:1}", "{\"v\":NaN}",
                "{\"v\":1,}", "{\"v\":01}", "{/*comment*/\"v\":1}")) {
            assertThrows(IOException.class, () -> CursorJson.read(invalid), invalid);
        }
    }

    @Test
    void writerAndBothReadersPreserveOpaqueUnicodeData() throws IOException {
        for (String value : List.of("normal ASCII", "</>&", "\b\f\n\r\t", "\u2028\u2029",
                "\u00e9 \ud83d\ude00", "\ud800", "\udc00", "\\uD800")) {
            JsonObject object = new JsonObject();
            object.addProperty("c", value);
            String json = CursorJson.write(object);
            String utf8 = new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertEquals(value, legacy.readTree(utf8).get("c").asText());
            assertEquals(value, CursorJson.read(utf8).getAsJsonObject().get("c").getAsString());
        }
    }

    @Test
    void preservesExistingParserDepthAndIntegerLimits() throws IOException {
        String atDepth = "[".repeat(1000) + "null" + "]".repeat(1000);
        assertNotNull(legacy.readTree(atDepth));
        assertNotNull(CursorJson.read(atDepth));
        for (String invalid : List.of("[" + atDepth + "]", "1".repeat(1001),
                "-" + "1".repeat(1001))) {
            assertThrows(IOException.class, () -> legacy.readTree(invalid));
            assertThrows(IOException.class, () -> CursorJson.read(invalid));
        }
    }
}
