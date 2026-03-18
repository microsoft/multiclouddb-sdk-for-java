// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Type;

/**
 * Maps Spanner {@link ResultSet} rows to Jackson {@link JsonNode} documents.
 * <p>
 * Supports all common Spanner column types: STRING, INT64, FLOAT64, BOOL,
 * BYTES, TIMESTAMP, DATE, and JSON.
 */
public final class SpannerRowMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpannerRowMapper() {
    }

    /**
     * Convert the current row of a {@link ResultSet} into a {@link JsonNode}.
     * The cursor must already be positioned on a valid row (i.e., after
     * {@code rs.next()} returned true).
     *
     * @param rs the result set positioned on a row
     * @return a JSON object node with column values mapped to appropriate JSON
     *         types
     */
    public static JsonNode toJsonNode(ResultSet rs) {
        ObjectNode node = MAPPER.createObjectNode();
        Type type = rs.getType();

        for (int i = 0; i < rs.getColumnCount(); i++) {
            String colName = type.getStructFields().get(i).getName();
            Type colType = type.getStructFields().get(i).getType();

            if (rs.isNull(i)) {
                node.putNull(colName);
                continue;
            }

            switch (colType.getCode()) {
                case STRING -> node.put(colName, rs.getString(i));
                case INT64 -> node.put(colName, rs.getLong(i));
                case FLOAT64 -> node.put(colName, rs.getDouble(i));
                case BOOL -> node.put(colName, rs.getBoolean(i));
                case BYTES -> node.put(colName, rs.getBytes(i).toBase64());
                case TIMESTAMP -> node.put(colName, rs.getTimestamp(i).toString());
                case DATE -> node.put(colName, rs.getDate(i).toString());
                case JSON -> {
                    try {
                        node.set(colName, MAPPER.readTree(rs.getJson(i)));
                    } catch (Exception e) {
                        node.put(colName, rs.getJson(i));
                    }
                }
                default -> node.put(colName, rs.getString(i));
            }
        }

        return node;
    }
}
