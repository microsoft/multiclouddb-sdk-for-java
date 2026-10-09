// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;

/** Maintained JSON syntax handling plus the existing v1 field-coercion profile. */
final class CursorJson {
    private static final TypeAdapter<JsonElement> ELEMENT = new Gson().getAdapter(JsonElement.class);

    private CursorJson() {
    }

    static JsonElement read(String json) throws IOException {
        // Match legacy readTree(String), not BOM-tolerant byte-stream parsing.
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') {
            throw new IOException("Unexpected leading cursor JSON BOM.");
        }
        try (JsonReader reader = new V1Reader(json)) {
            reader.setStrictness(Strictness.STRICT);
            // Like the original readTree, consume one root, not additional trailing roots.
            return ELEMENT.read(reader);
        }
    }

    static String write(JsonObject value) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.setHtmlSafe(false);
            ELEMENT.write(writer, value);
        }
        // JsonWriter leaves unpaired UTF-16 units in the StringWriter. Escape those
        // units before UTF-8 encoding rather than replacing opaque cursor data.
        String json = output.toString();
        StringBuilder escaped = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < json.length()
                    && Character.isLowSurrogate(json.charAt(i + 1))) {
                escaped.append(c).append(json.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                escaped.append("\\u");
                String hex = Integer.toHexString(c);
                escaped.append("0".repeat(4 - hex.length())).append(hex);
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    static JsonElement get(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null ? JsonNull.INSTANCE : value;
    }

    static boolean isText(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    static boolean isNumber(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    static String text(JsonElement value, String nullValue) {
        if (value.isJsonNull()) return nullValue;
        if (!value.isJsonPrimitive()) return "";
        if (isNumber(value)) return number(value).toString();
        return value.getAsString();
    }

    static int asInt(JsonElement value, int defaultValue) {
        if (isNumber(value)) return number(value).intValue();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1 : 0;
        }
        if (isText(value)) {
            String text = value.getAsString().trim();
            try {
                return integralText(text) ? Integer.parseInt(text) : (int) Double.parseDouble(text);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static long asLong(JsonElement value, long defaultValue) {
        if (isNumber(value)) return number(value).longValue();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1 : 0;
        }
        if (isText(value)) {
            String text = value.getAsString().trim();
            try {
                return integralText(text) ? Long.parseLong(text) : (long) Double.parseDouble(text);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static boolean canConvertToLong(JsonElement value) {
        if (!isNumber(value)) return false;
        Number number = number(value);
        if (number instanceof BigInteger integer) {
            return integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                    && integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
        }
        double floating = number.doubleValue();
        return floating >= Long.MIN_VALUE && floating <= Long.MAX_VALUE;
    }

    private static Number number(JsonElement value) {
        String text = value.getAsString();
        return integralText(text) ? new BigInteger(text) : Double.valueOf(text);
    }

    private static boolean integralText(String value) {
        int start = !value.isEmpty() && (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (start == value.length()) return false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Preserve the original parser's structural limits, not document model limits. */
    private static final class V1Reader extends JsonReader {
        private int depth;

        V1Reader(String json) {
            super(new StringReader(json));
        }

        private void enter() throws IOException {
            if (++depth > 1000) throw new IOException("Cursor JSON nesting limit.");
        }

        @Override public void beginObject() throws IOException {
            enter();
            super.beginObject();
        }

        @Override public void beginArray() throws IOException {
            enter();
            super.beginArray();
        }

        @Override public void endObject() throws IOException {
            super.endObject();
            depth--;
        }

        @Override public void endArray() throws IOException {
            super.endArray();
            depth--;
        }

        @Override public String nextName() throws IOException {
            String name = super.nextName();
            if (name.length() > 50_000) throw new IOException("Cursor JSON name limit.");
            return name;
        }

        @Override public String nextString() throws IOException {
            boolean number = peek() == JsonToken.NUMBER;
            String value = super.nextString();
            int length = value.length();
            if (number && value.startsWith("-")) length--;
            if (length > (number ? 1000 : 20_000_000)) {
                throw new IOException("Cursor JSON scalar limit.");
            }
            return value;
        }
    }
}
