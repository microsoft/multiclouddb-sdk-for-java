// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.Struct;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.changefeed.ChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the internal helpers on {@link SpannerChangeFeedReader} that
 * are normally exercised only via the live Spanner change-stream TVF and
 * therefore have no emulator coverage today:
 *
 * <ul>
 *   <li>{@code filterByFieldData(JsonNode)} — Finding #1. Whitelists the
 *       SDK-written columns named in {@code FIELD_DATA} while also preserving
 *       {@code partitionKey} / {@code sortKey}, so cross-provider consumers
 *       calling {@code ChangeEvent.data().get("partitionKey")} see the value
 *       on Spanner just as they would on Cosmos AVAD and Dynamo
 *       {@code NEW_AND_OLD_IMAGES}.</li>
 *   <li>{@code mapModType(String)} — Finding #16. Locale.ROOT-safe
 *       case-folding so {@code "insert"} maps to {@link ChangeType#CREATE}
 *       even under a Turkish JVM default locale (where the platform-default
 *       {@code "insert".toUpperCase()} yields {@code "İNSERT"} and would
 *       fall through to {@link ChangeType#UPDATE}).</li>
 *   <li>{@code parseSequence(Struct, String)} — Finding #15. Distinguishes
 *       "{@code record_sequence} absent" (legacy, returns 0L) from
 *       "{@code record_sequence} present but unparseable" (must surface as
 *       {@code MalformedContinuation} so {@code readChanges} can map it to
 *       {@code CursorExpired(MALFORMED)} rather than silently substituting
 *       0L and allowing event reordering across resume).</li>
 * </ul>
 *
 * <p>All three targets are package- or private-scope internals; tests reach
 * them via reflection rather than exposing them on the public reader API.
 */
class SpannerChangeFeedReaderHelpersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SpannerChangeFeedReader READER =
            new SpannerChangeFeedReader(ProviderId.SPANNER, null, java.util.Map.of());

    // ── filterByFieldData (Finding #1) ──────────────────────────────────────

    @Test
    @DisplayName("filterByFieldData drops extraneous columns not in FIELD_DATA but PRESERVES partitionKey/sortKey")
    void filterByFieldData_dropsExtrasKeepsPkSk() throws Exception {
        ObjectNode row = MAPPER.createObjectNode();
        row.put(SpannerConstants.FIELD_DATA, "[\"v\",\"tag\"]");
        row.put(SpannerConstants.FIELD_PARTITION_KEY, "pk-1");
        row.put(SpannerConstants.FIELD_SORT_KEY, "sk-1");
        row.put("v", 42);
        row.put("tag", "alpha");
        row.put("extra_col", "stale-from-prior-write");

        ObjectNode out = (ObjectNode) invokeFilter(row);
        assertNull(out.get(SpannerConstants.FIELD_DATA),
                "FIELD_DATA metadata column must never leak into ChangeEvent.data()");
        assertEquals(42, out.get("v").asInt(), "v is in FIELD_DATA → kept");
        assertEquals("alpha", out.get("tag").asText(), "tag is in FIELD_DATA → kept");
        assertNull(out.get("extra_col"),
                "extra_col is NOT in FIELD_DATA → must be dropped (stale column from a prior write)");
        assertEquals("pk-1", out.get(SpannerConstants.FIELD_PARTITION_KEY).asText(),
                "Finding #1: PK MUST be preserved regardless of FIELD_DATA contents — "
                        + "Cosmos AVAD and Dynamo NEW_AND_OLD_IMAGES both carry the document's "
                        + "key in the post-image; Spanner must match this contract.");
        assertEquals("sk-1", out.get(SpannerConstants.FIELD_SORT_KEY).asText(),
                "Finding #1: SK MUST be preserved regardless of FIELD_DATA contents");
    }

    @Test
    @DisplayName("filterByFieldData with no FIELD_DATA returns the object unchanged (legacy row fallback)")
    void filterByFieldData_noMetadata_returnsUnchanged() throws Exception {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("v", 1);
        row.put("tag", "x");
        row.put("extra_col", "also-kept");

        ObjectNode out = (ObjectNode) invokeFilter(row);
        // No FIELD_DATA means a legacy row that pre-dates the FIELD_DATA regime.
        // SpannerRowMapper.parseFieldMetadata's "no metadata → project every
        // column" rule applies; the filter must mirror that.
        assertEquals(1, out.get("v").asInt());
        assertEquals("x", out.get("tag").asText());
        assertEquals("also-kept", out.get("extra_col").asText(),
                "legacy-row fallback: without FIELD_DATA, every column is surfaced");
    }

    @Test
    @DisplayName("filterByFieldData with non-textual FIELD_DATA strips FIELD_DATA but keeps every other column")
    void filterByFieldData_nonTextualMetadata_stripsMetadataOnly() throws Exception {
        ObjectNode row = MAPPER.createObjectNode();
        ArrayNode fdArr = row.putArray(SpannerConstants.FIELD_DATA);
        fdArr.add("v"); // FIELD_DATA as a JSON array node, not a textual JSON string
        row.put("v", 1);
        row.put("tag", "x");
        row.put("extra_col", "kept-fallback");

        ObjectNode out = (ObjectNode) invokeFilter(row);
        assertNull(out.get(SpannerConstants.FIELD_DATA), "FIELD_DATA must always be stripped");
        assertEquals(1, out.get("v").asInt());
        assertEquals("x", out.get("tag").asText());
        assertEquals("kept-fallback", out.get("extra_col").asText(),
                "non-textual metadata is treated as legacy-row fallback");
    }

    @Test
    @DisplayName("filterByFieldData with malformed FIELD_DATA (not starting with '[') strips FIELD_DATA, keeps rest")
    void filterByFieldData_malformedMetadata_stripsMetadataOnly() throws Exception {
        ObjectNode row = MAPPER.createObjectNode();
        row.put(SpannerConstants.FIELD_DATA, "not_a_json_array");
        row.put("v", 1);
        row.put("extra_col", "kept-fallback");

        ObjectNode out = (ObjectNode) invokeFilter(row);
        assertNull(out.get(SpannerConstants.FIELD_DATA));
        assertEquals(1, out.get("v").asInt());
        assertEquals("kept-fallback", out.get("extra_col").asText(),
                "malformed metadata is treated as legacy-row fallback");
    }

    @Test
    @DisplayName("filterByFieldData with empty FIELD_DATA still preserves PK/SK")
    void filterByFieldData_emptyWhitelist_keepsOnlyPkSk() throws Exception {
        ObjectNode row = MAPPER.createObjectNode();
        row.put(SpannerConstants.FIELD_DATA, "[]");
        row.put(SpannerConstants.FIELD_PARTITION_KEY, "pk-2");
        row.put(SpannerConstants.FIELD_SORT_KEY, "sk-2");
        row.put("extra_col", "should-go");

        ObjectNode out = (ObjectNode) invokeFilter(row);
        assertNull(out.get(SpannerConstants.FIELD_DATA));
        assertNull(out.get("extra_col"));
        assertEquals("pk-2", out.get(SpannerConstants.FIELD_PARTITION_KEY).asText(),
                "even with an empty FIELD_DATA whitelist, PK MUST survive");
        assertEquals("sk-2", out.get(SpannerConstants.FIELD_SORT_KEY).asText());
    }

    @Test
    @DisplayName("filterByFieldData passes non-object JsonNode through unchanged")
    void filterByFieldData_nonObject_passthrough() throws Exception {
        JsonNode arr = MAPPER.createArrayNode();
        assertSame(arr, invokeFilter(arr));
        JsonNode txt = IntNode.valueOf(7);
        assertSame(txt, invokeFilter(txt));
    }

    // ── mapModType (Finding #16) ────────────────────────────────────────────

    @Test
    @DisplayName("mapModType maps known UPPER strings to ChangeType")
    void mapModType_knownValues_map() throws Exception {
        assertEquals(ChangeType.CREATE, invokeMapModType("INSERT"));
        assertEquals(ChangeType.DELETE, invokeMapModType("DELETE"));
        assertEquals(ChangeType.UPDATE, invokeMapModType("UPDATE"));
    }

    @Test
    @DisplayName("mapModType accepts mixed case (Locale.ROOT case-folding)")
    void mapModType_mixedCase_map() throws Exception {
        assertEquals(ChangeType.CREATE, invokeMapModType("insert"));
        assertEquals(ChangeType.CREATE, invokeMapModType("Insert"));
        assertEquals(ChangeType.DELETE, invokeMapModType("delete"));
        assertEquals(ChangeType.UPDATE, invokeMapModType("UpDaTe"));
    }

    @Test
    @DisplayName("mapModType(null) returns UPDATE (default safe semantic)")
    void mapModType_null_defaultsToUpdate() throws Exception {
        assertEquals(ChangeType.UPDATE, invokeMapModType(null));
    }

    @Test
    @DisplayName("mapModType unknown string falls through to UPDATE")
    void mapModType_unknown_defaultsToUpdate() throws Exception {
        assertEquals(ChangeType.UPDATE, invokeMapModType("UPSERT"));
        assertEquals(ChangeType.UPDATE, invokeMapModType("flap"));
    }

    @Test
    @DisplayName("mapModType is Locale.ROOT-safe: \"insert\" maps to CREATE even under a Turkish default Locale")
    void mapModType_turkishLocale_stillWorks() throws Exception {
        // Without Locale.ROOT, "insert".toUpperCase() under a Turkish JVM
        // default Locale would yield "İNSERT" (dotted capital I, U+0130),
        // which is NOT == "INSERT" — the switch would fall through to
        // ChangeType.UPDATE and Spanner CREATEs would silently arrive as
        // UPDATEs. This is the Turkish-I trap.
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(ChangeType.CREATE, invokeMapModType("insert"),
                    "Finding #16: Locale.ROOT case-folding MUST be used so "
                            + "'insert' continues to map to CREATE on a JVM whose default "
                            + "Locale is Turkish (the Turkish-I trap).");
            assertEquals(ChangeType.DELETE, invokeMapModType("delete"));
        } finally {
            Locale.setDefault(prev);
        }
    }

    // ── parseSequence (Finding #15) ─────────────────────────────────────────

    @Test
    @DisplayName("parseSequence returns 0L when the field is absent (legacy continuation compat)")
    void parseSequence_absent_returnsZero() throws Exception {
        Struct rec = Struct.newBuilder()
                .set("some_other_field").to("ignored")
                .build();
        assertEquals(0L, invokeParseSequence(rec, "record_sequence"));
    }

    @Test
    @DisplayName("parseSequence returns 0L when the field is present but NULL")
    void parseSequence_nullValue_returnsZero() throws Exception {
        Struct rec = Struct.newBuilder()
                .set("record_sequence").to((String) null)
                .build();
        assertEquals(0L, invokeParseSequence(rec, "record_sequence"));
    }

    @Test
    @DisplayName("parseSequence parses STRING record_sequence (the typical Spanner shape)")
    void parseSequence_stringNumeric_parsed() throws Exception {
        Struct rec = Struct.newBuilder()
                .set("record_sequence").to("42")
                .build();
        assertEquals(42L, invokeParseSequence(rec, "record_sequence"));
    }

    @Test
    @DisplayName("parseSequence parses INT64 record_sequence (the emulator shape)")
    void parseSequence_int64_parsed() throws Exception {
        Struct rec = Struct.newBuilder()
                .set("record_sequence").to(123L)
                .build();
        assertEquals(123L, invokeParseSequence(rec, "record_sequence"));
    }

    @Test
    @DisplayName("parseSequence throws MalformedContinuation on unparseable STRING (must surface as CursorExpired(MALFORMED))")
    void parseSequence_unparseableString_throws() throws Exception {
        Struct rec = Struct.newBuilder()
                .set("record_sequence").to("not_a_number")
                .build();
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> invokeParseSequence(rec, "record_sequence"));
        Throwable cause = ite.getCause();
        assertNotNull(cause, "the underlying throw must propagate");
        assertEquals("MalformedContinuation", cause.getClass().getSimpleName(),
                "Finding #15: present-but-unparseable record_sequence MUST throw "
                        + "MalformedContinuation so readChanges can re-throw it as "
                        + "CursorExpired(reason=MALFORMED). Silently substituting 0L would "
                        + "re-read every event in this commit-timestamp on resume and "
                        + "violate the at-least-once cross-provider invariant.");
        assertTrue(cause.getMessage().contains("record_sequence"),
                "MalformedContinuation message must name the field for operator triage");
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private static JsonNode invokeFilter(JsonNode in) throws Exception {
        Method m = SpannerChangeFeedReader.class
                .getDeclaredMethod("filterByFieldData", JsonNode.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(null, in);
    }

    private static ChangeType invokeMapModType(String s) throws Exception {
        Method m = SpannerChangeFeedReader.class
                .getDeclaredMethod("mapModType", String.class);
        m.setAccessible(true);
        return (ChangeType) m.invoke(READER, s);
    }

    private static long invokeParseSequence(Struct rec, String field) throws Exception {
        Method m = SpannerChangeFeedReader.class
                .getDeclaredMethod("parseSequence", Struct.class, String.class);
        m.setAccessible(true);
        return (long) m.invoke(null, rec, field);
    }
}
