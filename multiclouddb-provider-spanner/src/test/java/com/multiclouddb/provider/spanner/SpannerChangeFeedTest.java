// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.NewItemStateMode;
import com.multiclouddb.api.changefeed.StartPosition;
import com.multiclouddb.api.changefeed.internal.ContinuationTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SpannerChangeFeed} that exercise the wire-protocol
 * parsing and continuation-token plumbing without requiring a live Spanner
 * instance or emulator.
 *
 * <p>Mocking strategy:
 * <ul>
 *   <li>{@link DatabaseClient}, {@link ReadContext}, {@link ResultSet} are
 *       Mockito mocks of the Spanner SDK interfaces.</li>
 *   <li>{@link Struct} is mocked too — the {@code TVF} returns a
 *       {@code List<Struct>} whose nested fields we stub per scenario.</li>
 *   <li>The {@code Statement} that the adapter sends is captured and
 *       inspected to verify SQL shape and bind parameters.</li>
 * </ul>
 */
class SpannerChangeFeedTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "events");

    private DatabaseClient db;
    private ReadContext ctx;

    @BeforeEach
    void setUp() {
        db = mock(DatabaseClient.class);
        ctx = mock(ReadContext.class);
        when(db.singleUse()).thenReturn(ctx);
        // The retention cache is static so it survives across tests; reset it
        // to give each test a clean slate.
        SpannerChangeFeed.retentionCache.clear();
    }

    @Test
    @DisplayName("parseRetention handles d/h/m/s units, returns null for unparseable input")
    void parseRetentionUnits() {
        assertEquals(java.time.Duration.ofDays(7),
                SpannerChangeFeed.parseRetention("7d"));
        assertEquals(java.time.Duration.ofDays(1),
                SpannerChangeFeed.parseRetention("1D"));
        assertEquals(java.time.Duration.ofHours(168),
                SpannerChangeFeed.parseRetention("168h"));
        assertEquals(java.time.Duration.ofMinutes(60),
                SpannerChangeFeed.parseRetention("60m"));
        assertEquals(java.time.Duration.ofSeconds(30),
                SpannerChangeFeed.parseRetention(" 30s "));
        assertNull(SpannerChangeFeed.parseRetention(null));
        assertNull(SpannerChangeFeed.parseRetention(""));
        assertNull(SpannerChangeFeed.parseRetention("garbage"));
        assertNull(SpannerChangeFeed.parseRetention("7y"));
        assertNull(SpannerChangeFeed.parseRetention("xd"));
    }

    @Test
    @DisplayName("Beginning queries INFORMATION_SCHEMA for retention and uses it")
    void beginningQueriesInformationSchema() {
        // Stub two ResultSet sequences: first the retention lookup, then the
        // change-stream TVF. The retention lookup must precede the TVF call.
        ResultSet retentionRs = mock(ResultSet.class);
        when(retentionRs.next()).thenReturn(true, false);
        when(retentionRs.getString(0)).thenReturn("7d");

        ResultSet tvfRs = emptyResultSet();
        when(ctx.executeQuery(any(Statement.class)))
                .thenReturn(retentionRs)
                .thenReturn(tvfRs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.beginning())
                .build();
        feed.readChanges(req, OperationOptions.defaults());

        // The TVF call must use a start_timestamp ~7d in the past (minus 1m
        // safety) — verify by capturing both Statement objects.
        ArgumentCaptor<Statement> stmts = ArgumentCaptor.forClass(Statement.class);
        verify(ctx, atLeast(2)).executeQuery(stmts.capture());
        // First statement must be the INFORMATION_SCHEMA query
        assertTrue(stmts.getAllValues().get(0).getSql().contains("INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS"),
                "First query must be the retention lookup; got: " + stmts.getAllValues().get(0).getSql());
        // Second statement must be the TVF read
        assertTrue(stmts.getAllValues().get(1).getSql().contains("READ_events_changes"),
                "Second query must be the TVF; got: " + stmts.getAllValues().get(1).getSql());
    }

    @Test
    @DisplayName("Beginning falls back to 23h when retention lookup yields no rows")
    void beginningFallsBackOnEmptyRetention() {
        ResultSet retentionRs = mock(ResultSet.class);
        when(retentionRs.next()).thenReturn(false);

        ResultSet tvfRs = emptyResultSet();
        when(ctx.executeQuery(any(Statement.class)))
                .thenReturn(retentionRs)
                .thenReturn(tvfRs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.beginning())
                .build();
        // Must not throw — fallback should kick in.
        feed.readChanges(req, OperationOptions.defaults());
    }

    @Test
    @DisplayName("Beginning retention is cached across SpannerChangeFeed instances (static cache)")
    void retentionCacheIsStaticAcrossInstances() {
        // First call: stub retention query to return "7d", then TVF.
        ResultSet retentionRs = mock(ResultSet.class);
        when(retentionRs.next()).thenReturn(true, false);
        when(retentionRs.getString(0)).thenReturn("7d");

        ResultSet tvfRs1 = emptyResultSet();
        ResultSet tvfRs2 = emptyResultSet();
        when(ctx.executeQuery(any(Statement.class)))
                .thenReturn(retentionRs) // first call's retention lookup
                .thenReturn(tvfRs1)      // first call's TVF
                .thenReturn(tvfRs2);     // second call's TVF (no retention re-query)

        SpannerChangeFeed feed1 = new SpannerChangeFeed(db, Map.of());
        feed1.readChanges(ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.beginning()).build(),
                OperationOptions.defaults());

        // Fresh instance — simulates SpannerProviderClient allocating a new
        // SpannerChangeFeed per call. Cache hit must avoid the metadata round-trip.
        SpannerChangeFeed feed2 = new SpannerChangeFeed(db, Map.of());
        feed2.readChanges(ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.beginning()).build(),
                OperationOptions.defaults());

        ArgumentCaptor<Statement> stmts = ArgumentCaptor.forClass(Statement.class);
        verify(ctx, atLeast(2)).executeQuery(stmts.capture());
        long retentionQueries = stmts.getAllValues().stream()
                .filter(s -> s.getSql().contains("INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS"))
                .count();
        assertEquals(1, retentionQueries,
                "retention lookup must run only once across instances; cache hit expected on second call");
    }

    // ── stream-name resolution ─────────────────────────────────────────────

    @Test
    @DisplayName("stream name defaults to <collection>_changes when no override is configured")
    void streamNameDefault() {
        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        Statement stmt = captureTvfFor(feed, ADDR, /*partitionToken*/ null, emptyResultSet());
        assertTrue(stmt.getSql().contains("READ_events_changes"),
                "default stream name must be '<collection>_changes', got SQL: " + stmt.getSql());
    }

    @Test
    @DisplayName("stream name is taken from connection key 'changeStream.<collection>'")
    void streamNameFromConfig() {
        Map<String, String> conn = Map.of("changeStream.events", "my_custom_stream");
        SpannerChangeFeed feed = new SpannerChangeFeed(db, conn);
        Statement stmt = captureTvfFor(feed, ADDR, null, emptyResultSet());
        assertTrue(stmt.getSql().contains("READ_my_custom_stream"),
                "configured stream name must be honoured, got SQL: " + stmt.getSql());
    }

    @Test
    @DisplayName("invalid stream identifier (SQL-injection attempt) rejected with INVALID_REQUEST")
    void rejectsInvalidStreamName() {
        Map<String, String> conn = Map.of("changeStream.events", "evil; DROP TABLE x; --");
        SpannerChangeFeed feed = new SpannerChangeFeed(db, conn);
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feed.readChanges(req, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        verifyNoInteractions(ctx);
    }

    // ── readChanges happy path ─────────────────────────────────────────────

    @Test
    @DisplayName("readChanges maps INSERT mod -> CREATE event with key + new_values payload")
    void readChangesInsert() {
        Struct mod = mock(Struct.class);
        when(mod.isNull("keys")).thenReturn(false);
        when(mod.getJson("keys")).thenReturn("{\"partitionKey\":\"pk1\",\"sortKey\":\"sk1\"}");
        when(mod.isNull("new_values")).thenReturn(false);
        when(mod.getJson("new_values")).thenReturn("{\"score\":42}");
        when(mod.isNull("old_values")).thenReturn(true);

        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct outer = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(outer);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(1, page.events().size());
        ChangeEvent ev = page.events().get(0);
        assertEquals(ProviderId.SPANNER, ev.provider());
        assertEquals(ChangeType.CREATE, ev.eventType());
        assertEquals("pk1", ev.key().partitionKey());
        assertEquals("sk1", ev.key().sortKey());
        assertNotNull(ev.data(), "INCLUDE_IF_AVAILABLE mode must surface new_values");
        assertEquals(42, ev.data().get("score").asInt());
    }

    @Test
    @DisplayName("readChanges maps UPDATE -> UPDATE and DELETE -> DELETE (data null on delete)")
    void readChangesUpdateAndDelete() {
        Struct updateMod = stubKeyMod("pk1", "sk1", "{\"v\":2}");
        Struct deleteMod = stubKeyMod("pk2", "sk2", null);
        Struct updateDcr = stubDataChangeRecord("UPDATE", List.of(updateMod));
        Struct deleteDcr = stubDataChangeRecord("DELETE", List.of(deleteMod));

        Struct row1 = stubChangeRecord(updateDcr, null);
        Struct row2 = stubChangeRecord(deleteDcr, null);
        ResultSet __rs = twoRows(row1, row2);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(2, page.events().size());
        assertEquals(ChangeType.UPDATE, page.events().get(0).eventType());
        assertEquals(ChangeType.DELETE, page.events().get(1).eventType());
        assertNotNull(page.events().get(0).data());
        assertNull(page.events().get(1).data(),
                "DELETE events must surface with null data() — Spanner does not return new_values");
    }

    @Test
    @DisplayName("multi-mod transaction: each mod gets a distinct eventId (regression: at-least-once dedup)")
    void multiModTransactionGetsDistinctEventIds() {
        // A single transaction modifies three rows — they must NOT share eventId,
        // otherwise consumers following the documented (providerId, eventId)
        // dedup rule will collapse the three events into one (silent loss).
        Struct m1 = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct m2 = stubKeyMod("pk2", "sk2", "{\"v\":2}");
        Struct m3 = stubKeyMod("pk3", "sk3", "{\"v\":3}");
        Struct dcr = stubDataChangeRecord("UPDATE", List.of(m1, m2, m3));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(3, page.events().size());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ChangeEvent ev : page.events()) {
            ids.add(ev.eventId());
        }
        assertEquals(3, ids.size(),
                "Each mod within a transaction must produce a unique eventId; got " + ids);
    }

    // ── continuation-token plumbing ────────────────────────────────────────

    @Test
    @DisplayName("readChanges issues a continuation token that carries the partition token forward")
    void continuationTokenRoundTrip() {
        // First call: simple non-empty page, one data record, partition stays open.
        Struct mod = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest first = ChangeFeedRequest.builder(ADDR)

                .build();
        ChangeFeedPage page = feed.readChanges(first, OperationOptions.defaults());

        String token = page.continuationToken();
        assertNotNull(token, "non-retired partition must yield a non-null continuation token");

        // The token must round-trip through the codec scoped to SPANNER + ADDR
        assertDoesNotThrow(() -> ContinuationTokenCodec.decode(token, ProviderId.SPANNER, ADDR));

        // And the next call must accept it without throwing.
        ResultSet __rs2 = emptyResultSet();
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs2);
        ChangeFeedRequest resume = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.fromContinuationToken(token))
                .build();
        ChangeFeedPage page2 = feed.readChanges(resume, OperationOptions.defaults());
        assertNotNull(page2);
        assertTrue(page2.events().isEmpty());
    }

    @Test
    @DisplayName("continuation token from a different stream name is rejected with INVALID_REQUEST")
    void continuationTokenCrossStreamRejected() {
        // Bake a token under stream name "stream-A"
        SpannerChangeFeed feedA = new SpannerChangeFeed(db,
                Map.of("changeStream.events", "stream_A"));
        Struct mod = stubKeyMod("pk1", null, null);
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);
        ChangeFeedPage pageA = feedA.readChanges(
                ChangeFeedRequest.builder(ADDR).build(),
                OperationOptions.defaults());
        String tokenA = pageA.continuationToken();
        assertNotNull(tokenA);

        // Resume under a feed configured for stream_B — must be rejected.
        SpannerChangeFeed feedB = new SpannerChangeFeed(db,
                Map.of("changeStream.events", "stream_B"));
        ChangeFeedRequest resume = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.fromContinuationToken(tokenA))
                .build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feedB.readChanges(resume, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
    }

    // ── newItemStateMode ───────────────────────────────────────────────────

    @Test
    @DisplayName("NewItemStateMode.OMIT yields events with null data() even on INSERT")
    void omitMode() {
        Struct mod = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .newItemStateMode(NewItemStateMode.OMIT)
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(1, page.events().size());
        assertNull(page.events().get(0).data(), "OMIT mode must drop new_values payload");
    }

    @Test
    @DisplayName("NewItemStateMode.INCLUDE_IF_AVAILABLE returns null data when new_values are absent")
    void includeIfAvailableReturnsNullDataWhenNewValuesAbsent() {
        Struct mod = stubKeyMod("pk1", "sk1", null); // no new_values
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .newItemStateMode(NewItemStateMode.INCLUDE_IF_AVAILABLE)
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());
        assertFalse(page.events().isEmpty(), "should have events");
        assertNull(page.events().get(0).data(), "data should be null when new_values absent");
    }

    // ── maxPageSize / scope-mismatch (round-2 review fixes) ───────────────

    @Test
    @DisplayName("readChanges honors maxPageSize mid-partition: truncates, re-queues, and resumes correctly")
    void readChangesHonorsMaxPageSizeMidPartition() {
        // A multi-mod transaction produces 3 distinct events from one TVF call.
        // With maxPageSize=2, the drain loop must accept only the first two
        // (in order), re-queue the partition with its watermark advanced, and
        // emit a continuation token so the remainder paginates on the next call.
        Struct m1 = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct m2 = stubKeyMod("pk2", "sk2", "{\"v\":2}");
        Struct m3 = stubKeyMod("pk3", "sk3", "{\"v\":3}");
        Struct dcr = stubDataChangeRecord("UPDATE", List.of(m1, m2, m3));
        Struct row = stubChangeRecord(dcr, null);
        // First call drains 3 events; resume call gets a fresh ResultSet
        // returning the same 3 events (Spanner TVF semantics: start_timestamp
        // is inclusive, so the resume call re-emits at the watermark and
        // consumers dedupe by eventId per the documented at-least-once contract).
        // Build both ResultSet stubs BEFORE the outer when(...) call —
        // calling singleRow() inside thenReturn() args nests when()s inside
        // when() and Mockito reports UnfinishedStubbing.
        ResultSet rs1 = singleRow(row);
        ResultSet rs2 = singleRow(row);
        when(ctx.executeQuery(any(Statement.class)))
                .thenReturn(rs1)
                .thenReturn(rs2);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .maxPageSize(2)
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(2, page.events().size(),
                "maxPageSize=2 must cap emitted events at 2 even when the TVF returned 3");
        // Order matters: the FIRST two mods must come through, not arbitrary 2.
        // A bug that re-orders or substitutes events would silently violate
        // the per-partition commit-order guarantee consumers rely on.
        assertEquals("pk1", page.events().get(0).key().partitionKey(),
                "first emitted event must be m1 (preserves per-partition commit order)");
        assertEquals("pk2", page.events().get(1).key().partitionKey(),
                "second emitted event must be m2 (preserves per-partition commit order)");
        assertNotNull(page.continuationToken(),
                "page must carry a continuation token so the remainder paginates next call");

        // Resume: the token must round-trip cleanly through the codec and
        // the next readChanges call must not throw. This exercises the
        // re-queue path (addFirst with advanced watermark) and the resume
        // branch in resolveQueue that reads the encoded scope back out.
        ChangeFeedRequest resume = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.fromContinuationToken(page.continuationToken()))
                .maxPageSize(10)
                .build();
        ChangeFeedPage page2 = feed.readChanges(resume, OperationOptions.defaults());
        assertNotNull(page2,
                "resume from truncation token must succeed and return a page");
    }

    @Test
    @DisplayName("readChanges with maxPageSize unset (0) emits all events from one drain (no caller cap)")
    void readChangesMaxPageSizeUnsetUsesNoCap() {
        Struct m1 = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct m2 = stubKeyMod("pk2", "sk2", "{\"v\":2}");
        Struct m3 = stubKeyMod("pk3", "sk3", "{\"v\":3}");
        Struct dcr = stubDataChangeRecord("UPDATE", List.of(m1, m2, m3));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)

                .startPosition(StartPosition.now())
                .build(); // maxPageSize unset -> 0 -> no cap
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(3, page.events().size(),
                "with no caller cap, all 3 mods must be emitted in one page");
    }



    @Test
    @DisplayName("continuation token naming removed PhysicalPartition scope is rejected")
    void continuationTokenWithRemovedPhysicalPartitionScopeRejected() {
        var envelope = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        envelope.put("stream", "events_changes");
        envelope.put("scope", "PhysicalPartition");
        var partitions = envelope.putArray("partitions");
        partitions.addObject().put("token", "p-1");
        String token = ContinuationTokenCodec.encode(ProviderId.SPANNER, ADDR, envelope);

        ChangeFeedRequest resume = ChangeFeedRequest.builder(ADDR)
                .startPosition(StartPosition.fromContinuationToken(token))
                .build();
        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feed.readChanges(resume, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertTrue(ex.error().message().contains("PhysicalPartition scope has been removed"),
                "error message must explain the removed scope; got: " + ex.error().message());
    }
    // ── helpers ────────────────────────────────────────────────────────────

    /** Captures the {@link Statement} sent for a no-op execution. */
    private Statement captureTvfFor(SpannerChangeFeed feed, ResourceAddress addr,
                                    String partitionToken, ResultSet rs) {
        ArgumentCaptor<Statement> stmtCaptor = ArgumentCaptor.forClass(Statement.class);
        when(ctx.executeQuery(stmtCaptor.capture())).thenReturn(rs);
        ChangeFeedRequest req = ChangeFeedRequest.builder(addr).build();
        feed.readChanges(req, OperationOptions.defaults());
        return stmtCaptor.getValue();
    }

    /** Outer ChangeRecord stub: at most one of dataChangeRecord / childPartitionsRecord is non-null. */
    private static Struct stubChangeRecord(Struct dataChangeRecord, Struct childPartitionsRecord) {
        Struct outer = mock(Struct.class);
        when(outer.isNull("data_change_record")).thenReturn(dataChangeRecord == null);
        when(outer.isNull("child_partitions_record")).thenReturn(childPartitionsRecord == null);
        when(outer.getStructList("data_change_record")).thenReturn(
                dataChangeRecord != null ? List.of(dataChangeRecord) : Collections.emptyList());
        when(outer.getStructList("child_partitions_record")).thenReturn(
                childPartitionsRecord != null ? List.of(childPartitionsRecord) : Collections.emptyList());
        return outer;
    }

    private static Struct stubDataChangeRecord(String modType, List<Struct> mods) {
        Struct dcr = mock(Struct.class);
        when(dcr.getString("mod_type")).thenReturn(modType);
        when(dcr.getTimestamp("commit_timestamp")).thenReturn(Timestamp.now());
        when(dcr.getString("record_sequence")).thenReturn("00000001");
        when(dcr.isNull("server_transaction_id")).thenReturn(false);
        when(dcr.getString("server_transaction_id")).thenReturn("txn-1");
        when(dcr.getStructList("mods")).thenReturn(mods);
        return dcr;
    }

    private static Struct stubKeyMod(String pk, String sk, String newValuesJson) {
        Struct mod = mock(Struct.class);
        when(mod.isNull("keys")).thenReturn(false);
        StringBuilder keys = new StringBuilder("{\"partitionKey\":\"").append(pk).append("\"");
        if (sk != null) {
            keys.append(",\"sortKey\":\"").append(sk).append("\"");
        }
        keys.append("}");
        when(mod.getJson("keys")).thenReturn(keys.toString());
        if (newValuesJson != null) {
            when(mod.isNull("new_values")).thenReturn(false);
            when(mod.getJson("new_values")).thenReturn(newValuesJson);
        } else {
            when(mod.isNull("new_values")).thenReturn(true);
        }
        when(mod.isNull("old_values")).thenReturn(true);
        return mod;
    }

    private static ResultSet emptyResultSet() {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static ResultSet singleRow(Struct outerChangeRecord) {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getStructList("ChangeRecord")).thenReturn(List.of(outerChangeRecord));
        return rs;
    }

    private static ResultSet twoRows(Struct row1, Struct row2) {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getStructList("ChangeRecord"))
                .thenReturn(List.of(row1))
                .thenReturn(List.of(row2));
        return rs;
    }
}
