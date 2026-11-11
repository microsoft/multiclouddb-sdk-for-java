// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Type;
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
import java.util.Set;
import java.util.HashSet;

/**
 * Maps Spanner {@link ResultSet} rows to detached neutral Map documents.
 * Also owns persisted JSON encoding and parsing for writes and change feeds.
 * <p>
 * Supports all common Spanner column types: STRING, INT64, FLOAT64, BOOL,
 * BYTES, TIMESTAMP, DATE, and JSON.
 * <p>
 * When a {@code data} column is present and contains a JSON array of field
 * names, only those fields (plus the primary key columns) are included in the
 * result. This lets the SDK distinguish between "explicitly set to null" and
 * "empty schema column" — a distinction that Spanner's fixed schema otherwise
 * loses.
 * <p>
 * STRING values written by the SDK as JSON-serialised Map/List are
 * tagged with {@link SpannerConstants#JSON_VALUE_MARKER} (a control-char
 * prefix). Only marker-prefixed values are parsed back as JSON; ordinary
 * user strings (including legitimate text starting with {@code [} or
 * <code>{</code>) are returned verbatim.
 */
final class SpannerRowMapper {

    private SpannerRowMapper() {
    }

    /**
     * Convert the current row of a {@link ResultSet} into a mutable neutral Map.
     * The cursor must already be positioned on a valid row (i.e., after
     * {@code rs.next()} returned true).
     *
     * @param rs the result set positioned on a row
     * @return a detached mutable Map of column names to neutral Java values
     */
    static Map<String, Object> toMap(ResultSet rs) {
        Map<String, Object> item = new LinkedHashMap<>();
        Type type = rs.getType();
        int columnCount = rs.getColumnCount();

        // Single pre-scan: locate the data column (once) and parse its metadata.
        int dataColumnIndex = -1;
        for (int i = 0; i < columnCount; i++) {
            if (SpannerConstants.FIELD_DATA.equals(type.getStructFields().get(i).getName())) {
                dataColumnIndex = i;
                break;
            }
        }
        Set<String> writtenFields = parseFieldMetadata(rs, dataColumnIndex);

        for (int i = 0; i < columnCount; i++) {
            if (i == dataColumnIndex) continue; // internal metadata column

            String colName = type.getStructFields().get(i).getName();
            Type colType = type.getStructFields().get(i).getType();

            if (rs.isNull(i)) {
                // Surface null columns when either:
                //   - we have FIELD_DATA metadata and the column is listed in it
                //     (explicitly-written null — must round-trip), or
                //   - we have no/invalid FIELD_DATA metadata (legacy / pre-FIELD_DATA
                //     rows): keep the historical "no metadata => no filtering"
                //     behaviour and emit every null column so existing callers don't
                //     silently lose null fields on the upgrade.
                if (writtenFields == null || writtenFields.contains(colName)) {
                    item.put(colName, null);
                }
                continue;
            }

            // For non-null values, include if no metadata or if field is in metadata.
            if (writtenFields != null
                    && !writtenFields.contains(colName)
                    && !SpannerConstants.FIELD_PARTITION_KEY.equals(colName)
                    && !SpannerConstants.FIELD_SORT_KEY.equals(colName)) {
                continue;
            }

            switch (colType.getCode()) {
                case STRING -> {
                    String s = rs.getString(i);
                    // Only parse as JSON if it carries the explicit SDK marker —
                    // ordinary user strings are passed through unchanged. A user
                    // string that happens to start with U+0001 is escaped at
                    // write-time with a doubled leading U+0001, which we strip
                    // here so the verbatim value round-trips.
                    if (s != null && s.startsWith(SpannerConstants.JSON_VALUE_MARKER)) {
                        String payload = s.substring(SpannerConstants.JSON_VALUE_MARKER.length());
                        try {
                            item.put(colName, readJson(payload));
                        } catch (java.io.IOException | IllegalArgumentException e) {
                            // Marker present but payload corrupted — return raw string for diagnosis.
                            item.put(colName, s);
                        }
                    } else if (s != null && s.length() >= 2
                            && s.charAt(0) == '\u0001'
                            && s.charAt(1) == '\u0001') {
                        // Escape pair: user string that itself starts with U+0001.
                        item.put(colName, s.substring(1));
                    } else {
                        item.put(colName, s);
                    }
                }
                case INT64 -> item.put(colName, rs.getLong(i));
                case FLOAT64 -> item.put(colName, rs.getDouble(i));
                case BOOL -> item.put(colName, rs.getBoolean(i));
                case BYTES -> item.put(colName, rs.getBytes(i).toBase64());
                case TIMESTAMP -> item.put(colName, rs.getTimestamp(i).toString());
                case DATE -> item.put(colName, rs.getDate(i).toString());
                case JSON -> {
                    try {
                        item.put(colName, readJson(rs.getJson(i)));
                    } catch (java.io.IOException | IllegalArgumentException e) {
                        item.put(colName, rs.getJson(i));
                    }
                }
                default -> item.put(colName, rs.getString(i));
            }
        }

        return item;
    }

    /**
     * Parses the field-name metadata stored at the given index.
     *
     * @param rs              the result set positioned on a row
     * @param dataColumnIndex the column index of the {@code data} column, or
     *                        {@code -1} if the table has no data column
     * @return set of field names that were explicitly written, or {@code null}
     *         if no metadata is available (legacy row or data column absent)
     */
    private static Set<String> parseFieldMetadata(ResultSet rs, int dataColumnIndex) {
        if (dataColumnIndex < 0 || rs.isNull(dataColumnIndex)) return null;
        String dataValue = rs.getString(dataColumnIndex);
        if (dataValue == null || !dataValue.startsWith("[")) return null;
        try {
            List<String> fields = fieldNames(dataValue);
            return fields == null ? null : new HashSet<>(fields);
        } catch (java.io.IOException | IllegalArgumentException e) {
            return null;
        }
    }

    static Object readJson(String json) throws IOException {
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') throw new IOException("Unexpected JSON BOM.");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(1000);
            if (reader.peek() == JsonToken.END_DOCUMENT) return null;
            return readJson(reader, 0);
        }
    }

    private static Object readJson(JsonReader reader, int depth) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                if (depth >= 1000) throw new IOException("JSON nesting limit.");
                Map<String, Object> map = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 50_000) throw new IOException("JSON name limit.");
                    map.put(name, readJson(reader, depth + 1));
                }
                reader.endObject();
                return map;
            case BEGIN_ARRAY:
                if (depth >= 1000) throw new IOException("JSON nesting limit.");
                List<Object> list = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) list.add(readJson(reader, depth + 1));
                reader.endArray();
                return list;
            case STRING:
                String text = reader.nextString();
                if (text.length() > 20_000_000) throw new IOException("JSON string limit.");
                return text;
            case NUMBER:
                String token = reader.nextString();
                validateNumberLength(token);
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
            reader.setNestingLimit(1000);
            if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new IOException("Expected field-name array.");
            List<String> names = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) {
                switch (reader.peek()) {
                    case NUMBER:
                        String token = reader.nextString();
                        validateNumberLength(token);
                        names.add(token);
                        break;
                    case STRING:
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

    private static void validateNumberLength(String token) throws IOException {
        // Released Jackson counts digits rather than punctuation. Its
        // zero-leading slow path counts an absent fraction/exponent as -1.
        int digits = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) >= '0' && token.charAt(i) <= '9') digits++;
        }
        boolean fractional = token.indexOf('.') >= 0;
        boolean exponential = token.indexOf('e') >= 0 || token.indexOf('E') >= 0;
        int firstDigit = token.startsWith("-") ? 1 : 0;
        if ((fractional || exponential) && token.charAt(firstDigit) == '0') {
            if (!fractional) digits--;
            if (!exponential) digits--;
        }
        if (digits > 1000) throw new IOException("JSON number limit.");
    }

    static String text(Object value) {
        return value == null ? "null" : value instanceof Map<?, ?> || value instanceof List<?> ? "" : value.toString();
    }

    static String writeJson(Object value) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.setHtmlSafe(false);
            writer.setSerializeNulls(true);
            writeJson(writer, value);
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

    private static void writeJson(JsonWriter writer, Object value) throws IOException {
        if (value == null) writer.nullValue();
        else if (value instanceof Map<?, ?> map) {
            writer.beginObject();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) throw new IOException("Invalid JSON key.");
                writer.name(name);
                writeJson(writer, entry.getValue());
            }
            writer.endObject();
        } else if (value instanceof List<?> list) {
            writer.beginArray();
            for (Object item : list) writeJson(writer, item);
            writer.endArray();
        } else if (value instanceof String text) writer.value(text);
        else if (value instanceof Number number) writer.value(number);
        else if (value instanceof Boolean bool) writer.value(bool);
        else throw new IOException("Unsupported JSON value.");
    }
}
