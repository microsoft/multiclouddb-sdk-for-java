// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.ResultSets;
import com.google.cloud.spanner.Value;
import com.google.cloud.Timestamp;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.internal.CursorAnchor;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.PartitionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Native-result fixtures for selected change images, metadata-driven decoding
 * and cursor timing. These tests do not require a running Spanner service.
 */
class SpannerChangeFeedReaderTest {

    private static final ResourceAddress ADDR = new ResourceAddress("test_db", "test_collection");

    @Test
    void finalEventsPreserveSelectedValuesNullEmptyAndFieldDataFiltering() {
        String newer = "{\"partitionKey\":\"tenant\",\"sortKey\":\"record\","
                + "\"nested\":[null,{\"n\":2}],\"stale\":true,\"data\":\"[\\\"nested\\\"]\"}";
        String older = "{\"old\":true}";
        ChangeEvent inserted = readValues("INSERT", newer, older);
        assertEquals(ChangeType.CREATE, inserted.type());
        assertEquals(ObjectValue.of(Map.of("partitionKey", new StringValue("tenant"),
                "sortKey", new StringValue("record"), "nested", ArrayValue.of(List.of(
                        NullValue.INSTANCE, ObjectValue.of(Map.of("n", NumberValue.of(2))))))), inserted.data());
        assertTrue(((ObjectValue) inserted.data()).get("data").isEmpty());
        assertTrue(((ObjectValue) inserted.data()).get("stale").isEmpty());
        assertEquals(inserted.data(), readValues("UPDATE", newer, older).data());
        ChangeEvent deleted = readValues("DELETE", newer, older);
        assertEquals(ChangeType.DELETE, deleted.type());
        assertEquals(ObjectValue.of(Map.of("old", new BooleanValue(true))), deleted.data());
        assertEquals(NullValue.INSTANCE, readValues("UPDATE", "null", null).data());
        assertEquals(ObjectValue.of(Map.of("bytes", new StringValue("AQI="))),
                readValues("UPDATE", "{\"bytes\":\"AQI=\"}", null).data(),
                "native change-stream JSON text is not inferred to be binary");
        assertEquals(ObjectValue.of(Map.of()), readValues("UPDATE", null, null).data());
        assertEquals(ObjectValue.of(Map.of()), readValues("UPDATE", "{}", older).data());
        assertEquals(ObjectValue.of(Map.of("old", new BooleanValue(true))), readValues("UPDATE", null, older).data());
    }

    @Test
    void modelDomainFailuresAreSafeClassifiedFeedErrors() {
        for (String invalid : List.of("{\"private-field\":1e309}", "{\"private-field\":-1e309}",
                "{\"private-field\":" + "[".repeat(128) + "null" + "]".repeat(128) + "}")) {
            MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                    () -> readValues("UPDATE", invalid, null));
            assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, failure.error().category());
            assertEquals(ProviderId.SPANNER, failure.error().provider());
            assertEquals("readChanges", failure.error().operation());
            assertFalse(failure.error().retryable());
            assertEquals(Map.of("reason", "invalid_document_payload"), failure.error().providerDetails());
            assertEquals("Provider response cannot be represented as a Document value.", failure.error().message());
            assertNull(failure.getCause());
        }
    }

    @Test
    void nativeInt64StringsDecodeInSelectedCreateUpdateAndDeleteImages() {
        List<Struct> columns = List.of(column("v", "INT64"), column("text", "STRING"),
                column("data", "STRING"), column("stale", "INT64"));
        String newer = "{\"v\":\"1\",\"text\":\"1\",\"stale\":\"not-an-integer\","
                + "\"data\":\"[\\\"v\\\",\\\"text\\\"]\"}";
        String older = "{\"v\":\"-2\",\"text\":\"-2\",\"data\":\"[\\\"v\\\",\\\"text\\\"]\"}";
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            ChangeEvent event = readValues(operation, newer, older, columns);
            long expected = operation.equals("DELETE") ? -2 : 1;
            assertEquals(ObjectValue.of(Map.of("v", NumberValue.of(expected),
                    "text", new StringValue(Long.toString(expected)))), event.data());
            assertEquals(operation.equals("INSERT") ? ChangeType.CREATE
                    : operation.equals("DELETE") ? ChangeType.DELETE : ChangeType.UPDATE, event.type());
            assertEquals(com.multiclouddb.api.MulticloudDbKey.of("tenant", "record"), event.key());
            assertTrue(event.providerEventId().startsWith("txn:"));
        }
    }

    @Test
    void declaredInt64PreservesSignedRangeAndIntegralJsonWithoutRounding() {
        for (long value : new long[]{Long.MIN_VALUE, -9007199254740993L, -1, 0, 1,
                9007199254740993L, Long.MAX_VALUE}) {
            for (String json : List.of("{\"v\":\"" + value + "\"}", "{\"v\":" + value + "}")) {
                assertEquals(ObjectValue.of(Map.of("v", NumberValue.of(value))),
                        readValues("INSERT", json, null, List.of(column("v", "INT64"))).data());
            }
        }
    }

    @Test
    void invalidDeclaredInt64FailsSafelyRatherThanReturningRawPayload() {
        List<Struct> columns = List.of(column("v", "INT64"));
        for (String value : List.of("\"9223372036854775808\"", "\"-9223372036854775809\"",
                "9223372036854775808", "-9223372036854775809", "\"1.5\"", "1.5", "1.0",
                "\"1e0\"", "\"NaN\"", "\"Infinity\"", "\"\"", "\" 1\"", "\"+1\"",
                "\"\u0661\"", "true", "{}", "[]", "\"private-native-payload\"")) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                String image = "{\"v\":" + value + "}";
                assertSafePayloadFailure(() -> readValues(operation,
                        operation.equals("DELETE") ? "{\"v\":\"1\"}" : image,
                        operation.equals("DELETE") ? image : "{\"v\":\"1\"}", columns));
            }
        }
        assertSafePayloadFailure(() -> readValues("INSERT", "{\"v\":", null, columns));
    }

    @Test
    void int64MetadataDoesNotCollapseNullMissingEmptyOrFallbackImages() {
        List<Struct> columns = List.of(column("v", "INT64"));
        assertEquals(ObjectValue.of(Map.of("v", NullValue.INSTANCE)),
                readValues("INSERT", "{\"v\":null}", null, columns).data());
        assertEquals(NullValue.INSTANCE, readValues("UPDATE", "null", null, columns).data());
        assertEquals(ObjectValue.of(Map.of()), readValues("INSERT", "{}", "{\"v\":\"2\"}", columns).data());
        assertEquals(ObjectValue.of(Map.of()), readValues("UPDATE", null, null, columns).data());
        assertEquals(ObjectValue.of(Map.of("v", NumberValue.of(2))),
                readValues("UPDATE", null, "{\"v\":\"2\"}", columns).data());
        assertEquals(ObjectValue.of(Map.of("v", NumberValue.of(3))),
                readValues("DELETE", "{\"v\":\"3\"}", null, columns).data());
        assertEquals(ObjectValue.of(Map.of()), readValues("DELETE", "{\"v\":\"3\"}", "{}", columns).data());
        for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
            assertEquals(ObjectValue.of(Map.of("v", NumberValue.of(1))),
                    readValues(operation, operation.equals("DELETE") ? "not-json" : "{\"v\":\"1\"}",
                            operation.equals("DELETE") ? "{\"v\":\"1\"}" : "not-json", columns).data(),
                    "unselected images must not be decoded");
        }
    }

    @Test
    void missingMetadataDoesNotInferTypesAndNonInt64ColumnsStayUnchanged() {
        String json = "{\"v\":\"1\"}";
        ObjectValue text = ObjectValue.of(Map.of("v", new StringValue("1")));
        assertEquals(text, readValues("INSERT", json, null).data());
        assertEquals(text, readValues("INSERT", json, null, List.of()).data());
        assertEquals(text, readValues("INSERT", json, null, List.of(column("other", "INT64"))).data());
        for (String type : List.of("STRING", "NUMERIC", "BYTES", "JSON")) {
            assertEquals(text, readValues("INSERT", json, null, List.of(column("v", type))).data());
        }
        Struct arrayColumn = columnType("v", Value.json(
                "{\"code\":\"ARRAY\",\"array_element_type\":{\"code\":\"INT64\"}}"));
        assertEquals(ObjectValue.of(Map.of("v", ArrayValue.of(List.of(new StringValue("1"), NullValue.INSTANCE)))),
                readValues("INSERT", "{\"v\":[\"1\",null]}", null, List.of(arrayColumn)).data());
        assertEquals(ObjectValue.of(Map.of("raw", new StringValue("not-json"))),
                readValues("UPDATE", "not-json", null).data());
    }

    @Test
    void typeMetadataSupportsJsonAndStringTransportButRejectsMalformedDeclarations() {
        assertEquals(ObjectValue.of(Map.of("v", NumberValue.of(1))),
                readValues("INSERT", "{\"v\":\"1\"}", null,
                        List.of(columnType("v", Value.string("{\"code\":\"INT64\"}")))).data());
        for (String invalid : List.of("not-json", "null", "{}", "{\"code\":1}")) {
            assertSafePayloadFailure(() -> readValues("INSERT", "{\"v\":\"1\"}", null,
                    List.of(columnType("v", Value.json(invalid)))));
        }
        assertSafePayloadFailure(() -> readValues("INSERT", "{\"v\":\"1\"}", null,
                List.of(columnType("v", Value.json(null)))));
        assertSafePayloadFailure(() -> readValues("INSERT", "{\"v\":\"1\"}", null,
                List.of(column("v", "INT64"), column("v", "STRING"))));
    }

    private static void assertSafePayloadFailure(org.junit.jupiter.api.function.Executable action) {
        MulticloudDbException failure = assertThrows(MulticloudDbException.class, action);
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, failure.error().category());
        assertEquals(ProviderId.SPANNER, failure.error().provider());
        assertEquals("readChanges", failure.error().operation());
        assertFalse(failure.error().retryable());
        assertEquals(Map.of("reason", "invalid_document_payload"), failure.error().providerDetails());
        assertEquals("Provider response cannot be represented as a Document value.", failure.error().message());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static Struct column(String name, String code) {
        return columnType(name, Value.json("{\"code\":\"" + code + "\"}"));
    }

    private static Struct columnType(String name, Value type) {
        return Struct.newBuilder().set("name").to(name).set("type").to(type)
                .set("is_primary_key").to(false).set("ordinal_position").to(1L).build();
    }

    private static ChangeEvent readValues(String operation, String newer, String older) {
        return readValues(operation, newer, older, null);
    }

    private static ChangeEvent readValues(String operation, String newer, String older, List<Struct> columns) {
        Timestamp now = Timestamp.now();
        String keys = "{\"partitionKey\":\"tenant\",\"sortKey\":\"record\"}";
        Struct mod = Struct.newBuilder().set("keys").to(columns == null ? Value.string(keys) : Value.json(keys))
                .set("new_values").to(columns == null ? Value.string(newer) : Value.json(newer))
                .set("old_values").to(columns == null ? Value.string(older) : Value.json(older)).build();
        Struct.Builder record = Struct.newBuilder().set("commit_timestamp").to(now)
                .set("record_sequence").to("1").set("server_transaction_id").to("txn")
                .set("mod_type").to(operation).set("mods").toStructArray(mod.getType(), List.of(mod));
        if (columns != null) {
            record.set("column_types").toStructArray(
                    columns.isEmpty() ? column("v", "INT64").getType() : columns.get(0).getType(), columns);
        }
        Struct dataRecord = record.build();
        Struct outer = Struct.newBuilder().set("data_change_record")
                .toStructArray(dataRecord.getType(), List.of(dataRecord)).build();
        Struct row = Struct.newBuilder().set("ChangeRecord")
                .toStructArray(outer.getType(), List.of(outer)).build();
        DatabaseClient db = mock(DatabaseClient.class);
        ReadContext context = mock(ReadContext.class);
        when(db.singleUse()).thenReturn(context);
        when(context.executeQuery(any(Statement.class))).thenAnswer(
                call -> ResultSets.forRows(row.getType(), List.of(row)));
        ChangeFeedCursor cursor = new ChangeFeedCursor(new CursorToken(ProviderId.SPANNER, ADDR,
                System.currentTimeMillis(), CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("partition", now + "|0|0"))));
        return new SpannerChangeFeedReader(ProviderId.SPANNER, db, Map.of())
                .readChanges(ADDR, cursor, OperationOptions.defaults()).events().get(0);
    }

    @Test
    @DisplayName("Empty result placeholder: one __bootstrap__ cursor with issuedAt within call window")
    void emptyResult_mintsBootstrapPlaceholderWithFreshIssuedAt() {
        DatabaseClient db = mock(DatabaseClient.class);
        ReadContext ctx = mock(ReadContext.class);
        ResultSet rs = mock(ResultSet.class);
        when(db.singleUse()).thenReturn(ctx);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(rs);
        // Empty TVF result: first call to next() returns false.
        when(rs.next()).thenReturn(false);

        SpannerChangeFeedReader reader = new SpannerChangeFeedReader(
                ProviderId.SPANNER, db, Map.of());

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ADDR);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size(),
                "empty TVF result must mint exactly one bootstrap placeholder cursor");
        ChangeFeedCursor c = cursors.get(0);
        String partitionId = c.token().partitions().get(0).partitionId();
        assertNotNull(partitionId);
        assertEquals("__bootstrap__", partitionId,
                "placeholder partitionId must be __bootstrap__; was " + partitionId);
        long issuedAt = c.token().issuedAtEpochMillis();
        assertTrue(issuedAt >= preCall && issuedAt <= postCall,
                "placeholder issuedAt (" + issuedAt + ") must be within [preCall="
                        + preCall + ", postCall=" + postCall + "] — proving it was captured"
                        + " after the result was observed exhausted, not before the query was issued");
    }
}
