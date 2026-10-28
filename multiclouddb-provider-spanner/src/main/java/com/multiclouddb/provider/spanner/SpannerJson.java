// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal persisted-JSON syntax with the released numeric and string profile. */
final class SpannerJson {
    private SpannerJson() {
    }

    static Object read(String json) throws IOException {
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') throw new IOException("Unexpected JSON BOM.");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() == JsonToken.END_DOCUMENT) return null;
            return read(reader, 0);
        }
    }

    private static Object read(JsonReader reader, int depth) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                if (depth >= 1000) throw new IOException("JSON nesting limit.");
                Map<String, Object> map = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 50_000) throw new IOException("JSON name limit.");
                    map.put(name, read(reader, depth + 1));
                }
                reader.endObject();
                return map;
            case BEGIN_ARRAY:
                if (depth >= 1000) throw new IOException("JSON nesting limit.");
                List<Object> list = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) list.add(read(reader, depth + 1));
                reader.endArray();
                return list;
            case STRING:
                String text = reader.nextString();
                if (text.length() > 20_000_000) throw new IOException("JSON string limit.");
                return text;
            case NUMBER:
                String token = reader.nextString();
                if (token.length() - (token.startsWith("-") ? 1 : 0) > 1000) {
                    throw new IOException("JSON number limit.");
                }
                if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                    return Double.valueOf(token);
                }
                BigInteger integer = new BigInteger(token);
                if (integer.bitLength() < 32) return integer.intValue();
                if (integer.bitLength() < 64) return integer.longValue();
                return integer;
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return null;
            default:
                throw new IOException("Expected JSON value.");
        }
    }

    static List<String> fieldNames(String json) throws IOException {
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') throw new IOException("Unexpected JSON BOM.");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                return null;
            }
            if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new IOException("Expected field-name array.");
            List<String> names = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) {
                switch (reader.peek()) {
                    case STRING, NUMBER:
                        names.add(reader.nextString());
                        break;
                    case BOOLEAN:
                        names.add(Boolean.toString(reader.nextBoolean()));
                        break;
                    case NULL:
                        reader.nextNull();
                        names.add(null);
                        break;
                    default:
                        throw new IOException("Invalid field name.");
                }
            }
            reader.endArray();
            return names;
        }
    }

    static Map<String, Object> object(Map<?, ?> value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String name)) throw new IllegalArgumentException("Invalid JSON key.");
            fields.put(name, entry.getValue());
        }
        return fields;
    }

    static String text(Object value) {
        return value == null ? "null" : value instanceof Map<?, ?> || value instanceof List<?> ? "" : value.toString();
    }

    static String write(Object value) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.setHtmlSafe(false);
            writer.setSerializeNulls(true);
            write(writer, value);
        }
        // Gson escapes these separators even with HTML escaping disabled;
        // the released String-based writer leaves them literal.
        String json = output.toString();
        StringBuilder compatible = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                if (json.startsWith("\\u2028", i) || json.startsWith("\\u2029", i)) {
                    compatible.append(json.charAt(i + 5) == '8' ? '\u2028' : '\u2029');
                    i += 5;
                } else if (json.charAt(i + 1) == 'u' && i + 5 < json.length()) {
                    compatible.append("\\u");
                    for (int digit = i + 2; digit <= i + 5; digit++) {
                        compatible.append(Character.toUpperCase(json.charAt(digit)));
                    }
                    i += 5;
                } else {
                    compatible.append(c).append(json.charAt(++i));
                }
            } else {
                compatible.append(c);
            }
        }
        return compatible.toString();
    }

    private static void write(JsonWriter writer, Object value) throws IOException {
        if (value == null) writer.nullValue();
        else if (value instanceof Map<?, ?> map) {
            writer.beginObject();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) throw new IOException("Invalid JSON key.");
                writer.name(name);
                write(writer, entry.getValue());
            }
            writer.endObject();
        } else if (value instanceof List<?> list) {
            writer.beginArray();
            for (Object item : list) write(writer, item);
            writer.endArray();
        } else if (value instanceof String text) writer.value(text);
        else if (value instanceof Number number) writer.value(number);
        else if (value instanceof Boolean bool) writer.value(bool);
        else throw new IOException("Unsupported JSON value.");
    }
}
