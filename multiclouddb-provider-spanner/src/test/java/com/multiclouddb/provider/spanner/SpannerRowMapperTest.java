// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.ResultSets;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Type.StructField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link SpannerConstants#JSON_VALUE_MARKER} wire-format contract
 * for {@link SpannerRowMapper}. These tests are intentionally unit-level
 * (no emulator) and run under the default unit profile — they carry no
 * provider tag so the unit profile's {@code excludedGroups} does not skip
 * them.
 *
 * <p>The contract under test:
 * <ol>
 *   <li>A STRING column whose value begins with the marker is parsed back as a
 *       JSON value (Map / List / scalar).</li>
 *   <li>A user-supplied STRING that itself begins with a single U+0001 is
 *       protected by the SDK escape (double U+0001) so it round-trips
 *       <em>verbatim</em> rather than being misclassified as a marker-encoded
 *       JSON payload.</li>
 *   <li>A marker-prefixed value whose payload is not valid JSON falls back to
 *       returning the raw string (for diagnosis) rather than crashing.</li>
 *   <li>Malformed {@code FIELD_DATA} (non-JSON-array contents) does not abort
 *       the row mapping — affected columns are returned without metadata
 *       filtering.</li>
 *   <li>{@link SpannerRowMapper#toMap} preserves explicitly written
 *       {@code null} values, matching {@link SpannerRowMapper#toJsonNode}
 *       and the Cosmos / DynamoDB schemaless contract.</li>
 * </ol>
 */
class SpannerRowMapperTest {

    private static ResultSet singleRow(Type rowType, Struct row) {
        ResultSet rs = ResultSets.forRows(rowType, List.of(row));
        // Advance to the (only) row so the mapper can read it.
        assertTrue(rs.next(), "in-memory ResultSet should expose the staged row");
        return rs;
    }

    @Test
    @DisplayName("marker-prefixed JSON object is parsed back to a JSON object")
    void markerRoundTripObject() {
        String payload = SpannerConstants.JSON_VALUE_MARKER + "{\"a\":1,\"b\":\"x\"}";
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("custom", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"custom\"]")
                .set("custom").to(payload)
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertTrue(node.get("custom").isObject(),
                    "marker-prefixed payload must decode to JSON object");
            assertEquals(1, node.get("custom").get("a").asInt());
            assertEquals("x", node.get("custom").get("b").asText());
        }
    }

    @Test
    @DisplayName("user string starting with U+0001 round-trips verbatim (escape pair)")
    void adversarialUserStringStartingWithSoh() {
        // Simulates what setMutationValue writes for a user string that itself
        // begins with U+0001: prepend ONE extra U+0001 so the marker-detection
        // path cannot mistake the user string for a marker-encoded JSON value.
        String userValue = "\u0001mcdb:json:[\"hi\"]";
        String storedValue = "\u0001" + userValue;
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("note", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"note\"]")
                .set("note").to(storedValue)
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertTrue(node.get("note").isTextual(),
                    "escape pair must decode to a plain text node, not a JSON value");
            assertEquals(userValue, node.get("note").asText(),
                    "verbatim round-trip of user string starting with U+0001");
        }
    }

    @Test
    @DisplayName("ordinary user string is returned unchanged")
    void plainUserStringPassThrough() {
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("title", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"title\"]")
                .set("title").to("hello world")
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertEquals("hello world", node.get("title").asText());
        }
    }

    @Test
    @DisplayName("string starting with '[' or '{' is NOT parsed as JSON (no marker)")
    void unmarkedJsonShapedStringIsLiteral() {
        // Without the marker, the SDK must NOT speculatively parse a string
        // that happens to look like JSON — that would corrupt round-trip for
        // users who literally store JSON-shaped text.
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("snippet", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"snippet\"]")
                .set("snippet").to("[\"not\",\"parsed\"]")
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertTrue(node.get("snippet").isTextual());
            assertEquals("[\"not\",\"parsed\"]", node.get("snippet").asText());
        }
    }

    @Test
    @DisplayName("corrupt marker payload falls back to the raw string")
    void markerWithCorruptPayloadFallsBack() {
        String stored = SpannerConstants.JSON_VALUE_MARKER + "{ not json }";
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("payload", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"payload\"]")
                .set("payload").to(stored)
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertTrue(node.get("payload").isTextual(),
                    "corrupt payload must not crash the mapper");
            assertEquals(stored, node.get("payload").asText());
        }
    }

    @Test
    @DisplayName("malformed FIELD_DATA does not filter rows out")
    void malformedFieldDataIsTolerated() {
        // FIELD_DATA must be a JSON array of strings; if it's malformed, the
        // mapper should fall back to "no metadata" (treat every column as
        // present) rather than masking columns or aborting the row.
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("title", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("totally not a json array")
                .set("title").to("still visible")
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertEquals("still visible", node.get("title").asText());
        }
    }

    @Test
    @DisplayName("toMap preserves explicit null values from FIELD_DATA metadata")
    void toMapPreservesExplicitNulls() {
        // FIELD_DATA lists 'maybe' as a written field; its column value is NULL.
        // The mapper must surface this as an explicit map entry with a null
        // value (parity with the Cosmos / Dynamo schemaless round-trip).
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("maybe", Type.string()),
                StructField.of("present", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"maybe\",\"present\"]")
                .set("maybe").to((String) null)
                .set("present").to("yes")
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            Map<String, Object> map = SpannerRowMapper.toMap(rs);
            assertTrue(map.containsKey("maybe"),
                    "explicit nulls (recorded in FIELD_DATA) must survive toMap");
            assertNull(map.get("maybe"));
            assertEquals("yes", map.get("present"));
        }
    }

    @Test
    @DisplayName("columns absent from FIELD_DATA are filtered out")
    void columnsNotInMetadataAreOmitted() {
        // FIELD_DATA lists ONLY 'kept'; 'extra' is a NULL Spanner column the
        // user never wrote, so it must NOT appear in the result.
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("kept", Type.string()),
                StructField.of("extra", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("[\"kept\"]")
                .set("kept").to("value")
                .set("extra").to((String) null)
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            Map<String, Object> map = SpannerRowMapper.toMap(rs);
            assertTrue(map.containsKey("kept"));
            assertFalse(map.containsKey("extra"),
                    "columns not present in FIELD_DATA must be filtered out");
        }
    }

    @Test
    @DisplayName("legacy rows (no FIELD_DATA column) preserve null columns on read")
    void legacyRowPreservesNullColumns() {
        // When the table has no FIELD_DATA column at all (legacy pre-FIELD_DATA
        // schema), the mapper has no metadata to filter on. The pre-FIELD_DATA
        // behaviour was "every column surfaces, including nulls" — preserving
        // that on the upgrade is what callers / round-trip tests rely on. A
        // regression here would silently drop null fields on legacy data.
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("title", Type.string()),
                StructField.of("maybe", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("title").to("still visible")
                .set("maybe").to((String) null)
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertEquals("still visible", node.get("title").asText());
            assertTrue(node.has("maybe"),
                    "legacy row null columns must round-trip (no metadata => no filtering)");
            assertTrue(node.get("maybe").isNull(),
                    "legacy row null columns must surface as JSON null, not be dropped");
        }
    }

    @Test
    @DisplayName("malformed FIELD_DATA preserves null columns (legacy fallback)")
    void malformedFieldDataPreservesNullColumns() {
        // Malformed FIELD_DATA collapses to "no metadata available"; the mapper
        // must then apply the legacy "no filtering" rule, which includes
        // surfacing null columns. Previously this path dropped nulls — the
        // upgrade now surfaces them, matching the unstructured-null behaviour
        // of Cosmos / Dynamo.
        Type rowType = Type.struct(
                StructField.of("partitionKey", Type.string()),
                StructField.of("sortKey", Type.string()),
                StructField.of("data", Type.string()),
                StructField.of("maybe", Type.string()));
        Struct row = Struct.newBuilder()
                .set("partitionKey").to("p")
                .set("sortKey").to("s")
                .set("data").to("not a json array")
                .set("maybe").to((String) null)
                .build();

        try (ResultSet rs = singleRow(rowType, row)) {
            JsonNode node = SpannerRowMapper.toJsonNode(rs);
            assertTrue(node.has("maybe"),
                    "malformed FIELD_DATA must fall back to legacy null-preserving behaviour");
            assertTrue(node.get("maybe").isNull());
        }
    }
}
