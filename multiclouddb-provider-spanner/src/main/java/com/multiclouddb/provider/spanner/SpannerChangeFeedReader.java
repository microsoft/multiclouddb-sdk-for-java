// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TimestampBound;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import com.multiclouddb.api.changefeed.internal.CursorAnchor;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.CursorTokenCodec;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.changefeed.internal.PartitionPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud Spanner change-stream backed change-feed reader.
 * <p>
 * Spanner change streams are surfaced through a table-valued function (TVF) of the
 * shape:
 * <pre>
 *   SELECT * FROM READ_&lt;stream&gt;(
 *       start_timestamp        =&gt; @start_ts,
 *       end_timestamp          =&gt; @end_ts,
 *       partition_token        =&gt; @partition_token,
 *       heartbeat_milliseconds =&gt; @heartbeat
 *   )
 * </pre>
 * Each row carries either a {@code data_change_record}, a {@code heartbeat_record},
 * or a {@code child_partitions_record}. This reader bounds every TVF call with a
 * finite {@code end_timestamp} window (default: {@link #WINDOW_MS}) so each
 * {@link #readChanges} call returns in deterministic time.
 *
 * <h3>Provisioning prerequisite</h3>
 * The Spanner database must have a change stream watching the target table:
 * <pre>
 *   CREATE CHANGE STREAM &lt;collection&gt;_changes FOR &lt;collection&gt;
 *       OPTIONS (value_capture_type = 'NEW_ROW');
 * </pre>
 * The stream name can be overridden per collection via the
 * {@code changeStream.&lt;collection&gt;} connection key; otherwise
 * {@code &lt;collection&gt;_changes} is used.
 *
 * <h3>Partition state</h3>
 * Each {@link PartitionPosition} carries the Spanner partition token as its
 * {@code partitionId} and a "{@code commit_ts|record_seq}" continuation string
 * encoding the last successfully processed mod. The next read resumes
 * <em>strictly after</em> that point.
 *
 * <h3>Split / merge absorption</h3>
 * Whenever the TVF returns a {@code child_partitions_record}, its child partition
 * tokens are appended to the next cursor and the old (parent) partition is removed
 * once its window closes naturally.
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Without local emulator coverage this reader is verified only by the
 *       compiler and mock-based unit tests; live behaviour is exercised by the
 *       conformance suite.</li>
 *   <li>The TVF row schema varies slightly across Spanner versions. The reader
 *       reads field-by-field using {@link Struct} introspection to stay
 *       tolerant of optional columns.</li>
 * </ul>
 */
final class SpannerChangeFeedReader {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerChangeFeedReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default time window per readChanges call (5 seconds). */
    private static final long WINDOW_MS = 5_000L;
    /** Default heartbeat (1 second). */
    private static final long HEARTBEAT_MS = 1_000L;
    /**
     * Soft event-count cap used to signal {@code hasMore=true} on a
     * single-partition page that returned a backlog. Spanner's TVF is
     * time-bounded (a {@link #WINDOW_MS} window), not size-bounded, so this
     * is a proxy for Cosmos / Dynamo's "page hit cap" symbol. Matches
     * {@code DEFAULT_PAGE_SIZE} on the other two readers so the portable
     * contract — {@code hasMore=true} means more events are immediately
     * available, keep reading without sleeping — fires under the same
     * backlog conditions on all three providers.
     */
    private static final int DEFAULT_PAGE_SIZE = 100;
    /** Connection config key prefix: {@code changeStream.<collection>} overrides default. */
    private static final String CONFIG_STREAM_PREFIX = "changeStream.";

    private final ProviderId providerId;
    private final DatabaseClient databaseClient;
    private final Map<String, String> connection;
    /**
     * Effective client-side age cap stamped onto every minted {@link CursorToken}.
     * Resolved from {@code MulticloudDbClientConfig.changeFeed().extendedRetention()}:
     * the opt-in window when present, else the portable 24h baseline. Persisted
     * tokens carry this so an opted-in caller can resume a cursor older than 24h
     * (up to the Spanner change-stream {@code retention_period}) without
     * {@code TOKEN_AGED_OUT}.
     */
    private final long effectiveRetentionMillis;

    SpannerChangeFeedReader(ProviderId providerId, DatabaseClient databaseClient,
                            Map<String, String> connection) {
        this(providerId, databaseClient, connection, CursorTokenCodec.MAX_TOKEN_AGE_MILLIS);
    }

    SpannerChangeFeedReader(ProviderId providerId, DatabaseClient databaseClient,
                            Map<String, String> connection, long effectiveRetentionMillis) {
        this.providerId = providerId;
        this.databaseClient = databaseClient;
        this.connection = connection;
        this.effectiveRetentionMillis = effectiveRetentionMillis;
    }

    static SpannerChangeFeedReader create(ProviderId providerId, DatabaseClient databaseClient,
                                          MulticloudDbClientConfig config) {
        long effectiveRetentionMillis = config.changeFeed().extendedRetention()
                .map(java.time.Duration::toMillis)
                .orElse(CursorTokenCodec.MAX_TOKEN_AGE_MILLIS);
        return new SpannerChangeFeedReader(providerId, databaseClient, config.connection(),
                effectiveRetentionMillis);
    }

    String streamNameFor(String collection) {
        String key = CONFIG_STREAM_PREFIX + collection;
        return connection.getOrDefault(key, collection + "_changes");
    }

    /**
     * Bootstrap the partition tree by calling the TVF with a NULL partition token,
     * which returns the root {@code child_partitions_record} entries.
     * <p>
     * The {@code issuedAtEpochMillis} stamped on each minted {@link CursorToken}
     * is captured <em>per row</em>, immediately after {@code ResultSet.next()}
     * returns {@code true} for the {@code child_partitions_record} row that
     * yielded that cursor's partition token. This matches the instant the
     * partition bookmark is effective rather than a single pre-loop timestamp
     * shared across the whole list, and aligns with the semantics already used
     * by {@link #readChanges} (which captures
     * {@link System#currentTimeMillis()} after each page is read). On the
     * bootstrap-placeholder path (TVF returned no child partitions) the
     * timestamp is captured at the moment the placeholder is minted.
     */
    List<ChangeFeedCursor> listCursors(ResourceAddress address) {
        String streamName = streamNameFor(address.collection());
        // Use Spanner-derived "now" rather than Java wall-clock. The Spanner
        // emulator (and real Spanner under TrueTime) assigns commit_timestamps
        // that can lead the Java client's System.currentTimeMillis(), so a
        // cursor anchored at Java's now() may end up BEFORE prior commits and
        // would surface them on the next readChanges. A strong-read snapshot
        // timestamp is guaranteed >= all commits acknowledged before listCursors
        // started, so it is a safe live-tip anchor.
        Timestamp now = readStrongSnapshotTimestamp();
        Timestamp end = addMillis(now, WINDOW_MS);

        Statement stmt = Statement.newBuilder(
                "SELECT * FROM READ_" + sanitize(streamName) + "("
                        + "start_timestamp => @start_ts, "
                        + "end_timestamp => @end_ts, "
                        + "partition_token => NULL, "
                        + "heartbeat_milliseconds => @heartbeat)")
                .bind("start_ts").to(now)
                .bind("end_ts").to(end)
                .bind("heartbeat").to(HEARTBEAT_MS)
                .build();

        List<ChangeFeedCursor> cursors = new ArrayList<>();
        long exhaustedAtMs = 0L;
        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
            while (true) {
                if (!rs.next()) {
                    // Capture the moment the TVF result is observed exhausted.
                    // This is the effective instant of the placeholder bookmark
                    // below, used both when the result was empty from the start
                    // and when rows existed but emitted no child partitions.
                    exhaustedAtMs = System.currentTimeMillis();
                    break;
                }
                // Capture wall-clock immediately after the row materialises so
                // every cursor minted from this row's child_partitions_record
                // carries an issuedAt matching the instant the bookmark is
                // effective, not the moment we started the TVF query.
                long effectiveAtMs = System.currentTimeMillis();
                Struct row = rs.getCurrentRowAsStruct();
                for (LogicalRecord lr : decomposeRow(row)) {
                    if (lr.kind != RecordKind.CHILD_PARTITIONS) continue;
                    // The child_partitions_record carries the partition's own
                    // start_timestamp (typically the moment the partition was
                    // created — could be far in the past for pre-existing
                    // partitions). Spanner rejects any start_timestamp earlier
                    // than that. But we ALSO must not anchor the cursor before
                    // `now` — otherwise events committed before listCursors()
                    // would surface, breaking FR-cf-006 (now() cursor must
                    // ignore prior events). So the bookmark is the LATER of
                    // `now` (the live tip listCursors was asked for) and
                    // childStart (the earliest readable instant for this
                    // partition). For pre-existing partitions this resolves to
                    // `now`; the partition's own start_timestamp is only used
                    // if the partition was created at or after `now`, which
                    // cannot happen here (the TVF returned it at start_ts=now).
                    Timestamp childStart = childPartitionStart(lr.record, now);
                    Timestamp bookmark = maxTimestamp(now, childStart);
                    long anchorMs = timestampToEpochMillis(now);
                    for (String token : extractChildPartitionTokens(lr.record)) {
                        PartitionPosition pos = new PartitionPosition(token,
                                continuation(bookmark, 0L, anchorMs));
                        CursorToken tok = new CursorToken(
                                providerId, address, effectiveAtMs, CursorAnchor.NOW, List.of(pos),
                                effectiveRetentionMillis);
                        cursors.add(new ChangeFeedCursor(tok));
                    }
                }
            }
        } catch (SpannerException e) {
            throw mapSpannerException(e, "listCursors");
        }

        if (cursors.isEmpty()) {
            // Edge case: TVF returned no child partitions (either empty result
            // or rows that emitted none). Mint a placeholder that re-bootstraps
            // on the next read. Use the instant the result-set was observed
            // exhausted as issuedAt — the closest available approximation for
            // a "we read the stream and there was nothing" bookmark.
            long placeholderAt = exhaustedAtMs != 0L ? exhaustedAtMs : System.currentTimeMillis();
            PartitionPosition placeholder = new PartitionPosition(
                    "__bootstrap__", continuation(now, 0L, timestampToEpochMillis(now)));
            CursorToken tok = new CursorToken(
                    providerId, address, placeholderAt, CursorAnchor.NOW, List.of(placeholder),
                    effectiveRetentionMillis);
            cursors.add(new ChangeFeedCursor(tok));
        }

        return cursors;
    }

    /**
     * Drain one bounded TVF window across the cursor's first partition.
     */
    ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor,
                               OperationOptions options) {
        CursorToken token;
        if (cursor.isUnhydratedSentinel()) {
            // Hydrate by re-discovering partitions.
            List<ChangeFeedCursor> all = listCursors(address);
            List<PartitionPosition> merged = new ArrayList<>();
            for (ChangeFeedCursor c : all) merged.addAll(c.token().partitions());
            token = new CursorToken(providerId, address,
                    System.currentTimeMillis(), CursorAnchor.NOW, merged,
                    effectiveRetentionMillis);
        } else {
            token = cursor.token();
        }

        if (token.partitions().isEmpty()) {
            // A hydrated cursor whose partition list has drained (e.g., the
            // sole partition was merged out of existence on the last
            // readChanges call) is terminal — there is nothing left to read
            // and the caller should re-bootstrap via listCursors() to gain a
            // fresh partition assignment after the merge.
            CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, true);
        }

        List<PartitionPosition> partitions = new ArrayList<>(token.partitions());
        PartitionPosition pos = partitions.get(0);
        String partitionToken = pos.partitionId();

        // Re-bootstrap if this is the placeholder.
        if ("__bootstrap__".equals(partitionToken)) {
            List<ChangeFeedCursor> all = listCursors(address);
            List<PartitionPosition> newPositions = new ArrayList<>();
            for (ChangeFeedCursor c : all) newPositions.addAll(c.token().partitions());
            if (newPositions.isEmpty()) {
                CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
                return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
            }
            CursorToken next = token.withPartitions(newPositions, System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(next), true, false);
        }

        String streamName = streamNameFor(address.collection());
        ContState state;
        try {
            state = parseContinuation(pos.continuation());
        } catch (MalformedContinuation mc) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    mc.getMessage(),
                    providerId, "readChanges", false,
                    Map.of(CursorTokenCodec.DETAIL_REASON, CursorTokenCodec.REASON_MALFORMED)), mc);
        }
        Timestamp end = addMillis(state.startTs, WINDOW_MS);

        Statement stmt = Statement.newBuilder(
                "SELECT * FROM READ_" + sanitize(streamName) + "("
                        + "start_timestamp => @start_ts, "
                        + "end_timestamp => @end_ts, "
                        + "partition_token => @partition_token, "
                        + "heartbeat_milliseconds => @heartbeat)")
                .bind("start_ts").to(state.startTs)
                .bind("end_ts").to(end)
                .bind("partition_token").to(partitionToken)
                .bind("heartbeat").to(HEARTBEAT_MS)
                .build();

        List<ChangeEvent> events = new ArrayList<>();
        Timestamp lastCommitTs = state.startTs;
        long lastRecordSeq = state.recordSeq;
        boolean partitionClosed = false;
        List<PartitionPosition> newChildren = new ArrayList<>();

        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
            while (rs.next()) {
                Struct row = rs.getCurrentRowAsStruct();
                for (LogicalRecord lr : decomposeRow(row)) {
                    switch (lr.kind) {
                        case DATA:
                            DataChangeBatch batch = readDataChange(lr.record, address);
                            for (ChangeEvent ev : batch.events) {
                                // Drop events that committed before the cursor's
                                // live-tip anchor. The Spanner change-stream TVF's
                                // start_timestamp is not always a strict lower
                                // bound under the emulator (the emulator's commit
                                // timestamps can lead the Java wall-clock that
                                // listCursors() captured), so the cursor honors
                                // its now() contract client-side via this filter.
                                if (state.anchorMs > 0L
                                        && ev.commitTimestamp().toEpochMilli() < state.anchorMs) {
                                    continue;
                                }
                                events.add(ev);
                            }
                            if (batch.commitTs != null) lastCommitTs = batch.commitTs;
                            lastRecordSeq = batch.recordSeq;
                            break;
                        case CHILD_PARTITIONS:
                            partitionClosed = true;
                            // Use the child partition's own start_timestamp when
                            // available — readChanges of a child partition
                            // rejects any start_timestamp earlier than that.
                            // Inherit the parent's anchorMs so the live-tip
                            // filter continues to apply to events from the
                            // newly-spawned children.
                            Timestamp childStart = childPartitionStart(lr.record, lastCommitTs);
                            for (String child : extractChildPartitionTokens(lr.record)) {
                                newChildren.add(new PartitionPosition(child,
                                        continuation(childStart, 0L, state.anchorMs)));
                            }
                            break;
                        case HEARTBEAT:
                            Timestamp hb = extractHeartbeatTimestamp(lr.record);
                            if (hb != null) lastCommitTs = hb;
                            break;
                        case UNKNOWN:
                        default:
                            // Silently skip; logged at debug.
                            LOG.debug("Skipping unrecognised change-stream row");
                    }
                }
            }
        } catch (SpannerException e) {
            // OUT_OF_RANGE is Spanner change-stream's idiomatic "partition
            // token has aged past the change-stream retention window" error —
            // map it to the portable CURSOR_EXPIRED(PROVIDER_TRIMMED).
            if (e.getErrorCode() == ErrorCode.OUT_OF_RANGE) {
                throw new CursorExpiredException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CURSOR_EXPIRED,
                        "Spanner change stream rejected the cursor — partition token outside the change-stream retention window: "
                                + e.getMessage(),
                        providerId, "readChanges", false,
                        Map.of(CursorTokenCodec.DETAIL_REASON, CursorTokenCodec.REASON_PROVIDER_TRIMMED,
                                "errorCode", e.getErrorCode().name())), e);
            }
            // NOT_FOUND on READ_<stream> with a "change stream" message is the
            // operator forgot to provision the change stream — symmetric with
            // Dynamo's UNSUPPORTED_CAPABILITY(stream_not_enabled) for the same
            // condition. Mapping this to CURSOR_EXPIRED would send the docs'
            // recovery path ("re-bootstrap with listCursors()") into an
            // infinite loop, because listCursors() would hit the same
            // NOT_FOUND.
            if (e.getErrorCode() == ErrorCode.NOT_FOUND
                    && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("change stream")) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                        "Spanner change stream '" + streamName + "' does not exist. "
                                + "Run: CREATE CHANGE STREAM " + streamName
                                + " FOR <collection> OPTIONS (value_capture_type='NEW_ROW'); ",
                        providerId, "readChanges", false,
                        Map.of("reason", "stream_not_enabled")), e);
            }
            // Other INVALID_ARGUMENT / NOT_FOUND / failures: route through the
            // shared mapper rather than blanket-mapping to CURSOR_EXPIRED.
            // INVALID_ARGUMENT in particular covers malformed SQL, type-bind
            // errors, and bad parameter values — bugs in this reader would
            // otherwise masquerade as cursor expiry.
            throw mapSpannerException(e, "readChanges");
        } catch (MalformedContinuation mc) {
            // Thrown from parseSequence(...) when record_sequence is present
            // but unparseable on a row read from the TVF result (mid-stream,
            // not at parseContinuation() time). The same MALFORMED contract
            // applies — silently substituting 0L would allow reordering
            // across crash/resume because the continuation
            // "commitTs|0|anchorMs" re-reads from the start of that commit
            // timestamp.
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    mc.getMessage(),
                    providerId, "readChanges", false,
                    Map.of(CursorTokenCodec.DETAIL_REASON, CursorTokenCodec.REASON_MALFORMED)), mc);
        }

        // Update partition state.
        // The partition list order is the active-partition state — without
        // rotation, multi-partition cursors (e.g., the now() sentinel hydrate
        // that merges all child partitions, or a cursor that has absorbed
        // split/merge children) would starve every partition after index 0.
        if (partitionClosed) {
            // Replace this partition with its children. Append the children to
            // the END of the partition list so we naturally visit the OTHER
            // pre-existing partitions before draining the new children — fair
            // round-robin rather than starvation of the rest of the cursor.
            partitions.remove(0);
            partitions.addAll(newChildren);
        } else {
            // Advance the continuation to the end of the window so the next call
            // picks up where we left off, then rotate the just-advanced
            // partition to the end so the next call visits the next partition.
            partitions.set(0, new PartitionPosition(partitionToken,
                    continuation(end, lastRecordSeq, state.anchorMs)));
            if (partitions.size() > 1) Collections.rotate(partitions, -1);
        }

        // terminal: this cursor has been fully merged out of existence — the
        // sole partition closed with no children to take its place. Without
        // surfacing this, the caller would loop forever on empty pages with
        // hasMore=false. ChangeFeedPage's Javadoc cites Spanner as the
        // canonical case for isTerminal().
        boolean terminal = partitionClosed && partitions.isEmpty();
        // hasMore: caller should re-call without sleeping. Symmetric with
        // Cosmos and Dynamo readers, which set hasMore=true whenever a page
        // returns events.size() >= DEFAULT_PAGE_SIZE (the page hit the cap).
        // Spanner's TVF is time-bounded (a WINDOW_MS slice), not size-bounded,
        // so we use the event count as a backlog-proxy: a well-populated
        // window means the live tip is producing faster than this call drained
        // and the caller should keep reading without a back-off. Cases:
        //   - partitionClosed: multi-partition with children to drain;
        //   - partitions.size() > 1 with events: other partitions in the
        //     cursor may have events at the live tip;
        //   - events.size() >= DEFAULT_PAGE_SIZE: backlog on the head
        //     partition (Cosmos / Dynamo parity).
        // partitionClosed is always followed by either children to drain
        // (multi-partition) or terminal=true (handled above).
        boolean hasMore = !terminal
                && (partitionClosed
                        || (partitions.size() > 1 && !events.isEmpty())
                        || events.size() >= DEFAULT_PAGE_SIZE);
        CursorToken next = token.withPartitions(partitions, System.currentTimeMillis());
        return new ChangeFeedPage(events, new ChangeFeedCursor(next), hasMore, terminal);
    }

    // ── Record dispatch ────────────────────────────────────────────────────

    private enum RecordKind { DATA, HEARTBEAT, CHILD_PARTITIONS, UNKNOWN }

    /** One logical change record (data/heartbeat/child-partitions) carried by a TVF row. */
    private static final class LogicalRecord {
        final RecordKind kind;
        final Struct record;
        LogicalRecord(RecordKind kind, Struct record) {
            this.kind = kind;
            this.record = record;
        }
    }

    /**
     * Decompose a Spanner change-stream TVF row into the logical records it carries.
     * <p>
     * In the current schema each row has a single column {@code ChangeRecord} of
     * type {@code ARRAY<STRUCT<data_change_record ARRAY<...>, heartbeat_record
     * ARRAY<...>, child_partitions_record ARRAY<...>>>}. Typically the outer
     * array has one element, and exactly one of the three sub-arrays is
     * non-empty.
     */
    private List<LogicalRecord> decomposeRow(Struct row) {
        List<LogicalRecord> out = new ArrayList<>();
        String chgCol = canonicalField(row, "ChangeRecord");
        if (chgCol != null && !row.isNull(chgCol)) {
            for (Struct outer : row.getStructList(chgCol)) {
                for (Struct rec : getStructListOrEmpty(outer, "data_change_record")) {
                    out.add(new LogicalRecord(RecordKind.DATA, rec));
                }
                for (Struct rec : getStructListOrEmpty(outer, "heartbeat_record")) {
                    out.add(new LogicalRecord(RecordKind.HEARTBEAT, rec));
                }
                for (Struct rec : getStructListOrEmpty(outer, "child_partitions_record")) {
                    out.add(new LogicalRecord(RecordKind.CHILD_PARTITIONS, rec));
                }
            }
        }
        return out;
    }

    private static boolean hasNonNullField(Struct s, String field) {
        String canonical = canonicalField(s, field);
        return canonical != null && !s.isNull(canonical);
    }

    // ── Data change extraction ─────────────────────────────────────────────

    private static final class DataChangeBatch {
        final List<ChangeEvent> events;
        final Timestamp commitTs;
        final long recordSeq;
        DataChangeBatch(List<ChangeEvent> events, Timestamp commitTs, long recordSeq) {
            this.events = events;
            this.commitTs = commitTs;
            this.recordSeq = recordSeq;
        }
    }

    private DataChangeBatch readDataChange(Struct rec, ResourceAddress address) {
        // address is reserved for future use (table_name fallback). `rec` is the
        // inner `data_change_record` struct produced by decomposeRow(...) — all
        // field accesses below go through canonicalField(...) helpers so we
        // tolerate Spanner returning TVF column names with different casing
        // across client versions.
        Timestamp commitTs = getTimestampOrNull(rec, "commit_timestamp");
        long recordSeq = parseSequence(rec, "record_sequence");
        String txnIdRaw = getStringOrNull(rec, "server_transaction_id");
        String txnId = txnIdRaw != null ? txnIdRaw : "";
        String modTypeRaw = getStringOrNull(rec, "mod_type");
        String modType = modTypeRaw != null ? modTypeRaw : "UPDATE";
        ChangeType type = mapModType(modType);

        List<Struct> mods = getStructListOrEmpty(rec, "mods");
        List<ChangeEvent> out = new ArrayList<>(mods.size());
        int idx = 0;
        for (Struct mod : mods) {
            String eventId = txnId + ":" + (commitTs != null ? commitTs.toString() : "") + ":"
                    + recordSeq + ":" + idx;
            MulticloudDbKey key = extractKey(mod);
            JsonNode data = extractValues(mod, type);
            Instant eventInstant = commitTs != null
                    ? Instant.ofEpochSecond(commitTs.getSeconds(), commitTs.getNanos())
                    : Instant.EPOCH;
            out.add(new ChangeEvent(key, type, eventInstant, data, eventId));
            idx++;
        }
        return new DataChangeBatch(out, commitTs != null ? commitTs : Timestamp.now(), recordSeq);
    }

    private MulticloudDbKey extractKey(Struct mod) {
        // mods.keys is a JSON STRING ({ "partitionKey": "...", "sortKey": "..." }).
        //
        // PROVIDER SYMMETRY:
        // Cosmos (CosmosChangeFeedReader#extractKey) and Dynamo
        // (DynamoChangeFeedReader#extractKey) both THROW
        // MulticloudDbException(PROVIDER_ERROR, reason=missing_partition_key)
        // when the envelope is missing a partition key, instead of silently
        // minting MulticloudDbKey.of("") or a raw-blob key. Silently emitting
        // an empty / blob key would attribute every malformed record to a
        // phantom partition, corrupting downstream CursorState dedupe and per-
        // key checkpointing — and the divergence is NOT gated by
        // CapabilitySet, so callers have no portable way to detect it.
        //
        // The wire-format reason token MUST match Dynamo's exactly
        // ("missing_partition_key" / "malformed_envelope") so application-
        // level retry / dead-letter logic that branches on
        // providerDetails.reason stays portable across providers.
        String keysJson = getStringOrNull(mod, "keys");
        if (keysJson == null) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Spanner change-stream record is missing the 'keys' field; "
                            + "cannot derive partition key.",
                    providerId, OperationNames.READ_CHANGES, false,
                    Map.of("reason", "missing_partition_key")));
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(keysJson);
        } catch (Exception e) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Spanner change-stream record's 'keys' field is not valid JSON: " + keysJson,
                    providerId, OperationNames.READ_CHANGES, false,
                    Map.of("reason", "malformed_envelope")), e);
        }
        if (!node.has("partitionKey") || node.get("partitionKey").isNull()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Spanner change-stream record's 'keys' JSON has no 'partitionKey' field: " + keysJson,
                    providerId, OperationNames.READ_CHANGES, false,
                    Map.of("reason", "missing_partition_key")));
        }
        String pk = node.get("partitionKey").asText();
        JsonNode skNode = node.get("sortKey");
        if (skNode != null && !skNode.isNull()) return MulticloudDbKey.of(pk, skNode.asText());
        return MulticloudDbKey.of(pk);
    }

    private JsonNode extractValues(Struct mod, ChangeType type) {
        // For CREATE/UPDATE prefer new_values; for DELETE fall back to old_values.
        String field;
        if (type == ChangeType.DELETE && hasNonNullField(mod, "old_values")) {
            field = "old_values";
        } else if (hasNonNullField(mod, "new_values")) {
            field = "new_values";
        } else if (hasNonNullField(mod, "old_values")) {
            field = "old_values";
        } else {
            return MAPPER.createObjectNode();
        }
        String json = getStringOrNull(mod, field);
        if (json == null) return MAPPER.createObjectNode();
        try {
            JsonNode raw = MAPPER.readTree(json);
            return filterByFieldData(raw);
        } catch (Exception e) {
            ObjectNode wrap = MAPPER.createObjectNode();
            wrap.put("raw", json);
            return wrap;
        }
    }

    /**
     * Apply the SDK's {@link SpannerConstants#FIELD_DATA} metadata filter to a
     * raw change-stream {@code new_values}/{@code old_values} JSON object.
     * <p>
     * Background: the SDK writes every document field individually plus a
     * {@code FIELD_DATA} JSON-array column listing which fields were written by
     * the latest call. {@link SpannerRowMapper} consumes that metadata when
     * reads return rows, so {@code client.read()} surfaces only the keys the
     * caller most recently wrote — preserving full-document-replacement
     * semantics for {@code upsert(...)} even though the underlying mutation is
     * now {@code INSERT_OR_UPDATE} (was {@code REPLACE} prior to the
     * change-feed parity flip).
     * <p>
     * The same filter must be applied on the change-stream path: Spanner change
     * streams under {@code value_capture_type='NEW_ROW'} emit the entire
     * current row (including columns left behind by prior writes), so without
     * this filter {@link ChangeEvent#data()} on Spanner would leak stale
     * columns that Cosmos AVAD and Dynamo {@code NEW_AND_OLD_IMAGES} do not.
     * <p>
     * If the input is not an object, or carries no {@code FIELD_DATA} key, or
     * the metadata fails to parse, the input is returned unchanged (only the
     * {@code FIELD_DATA} key itself is stripped if present). This mirrors
     * {@link SpannerRowMapper#parseFieldMetadata}'s "no/invalid metadata =
     * project every column" fallback — a legacy row that pre-dates the
     * {@code FIELD_DATA} regime continues to surface every column.
     */
    private static JsonNode filterByFieldData(JsonNode raw) {
        if (!(raw instanceof ObjectNode obj)) return raw;
        JsonNode fdNode = obj.get(SpannerConstants.FIELD_DATA);
        if (fdNode == null) return obj;
        // Always strip FIELD_DATA itself — it is an SDK-internal metadata column
        // and must never surface in ChangeEvent.data().
        obj.remove(SpannerConstants.FIELD_DATA);
        if (!fdNode.isTextual()) return obj;
        String fdValue = fdNode.asText();
        if (fdValue == null || !fdValue.startsWith("[")) return obj;
        java.util.Set<String> allowed;
        try {
            allowed = new java.util.HashSet<>(MAPPER.readValue(fdValue,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}));
        } catch (Exception e) {
            // Malformed metadata — match SpannerRowMapper's "treat as legacy row".
            return obj;
        }
        java.util.Iterator<String> it = obj.fieldNames();
        java.util.List<String> drop = new java.util.ArrayList<>();
        while (it.hasNext()) {
            String name = it.next();
            // Always whitelist the primary-key columns: SpannerRowMapper does the
            // same on the read path (SpannerRowMapper.java:90-96), and the
            // portable contract on ChangeEvent.data() is that the post-image
            // includes the document's PK/SK fields — matching Cosmos AVAD
            // (which carries id+partitionKey) and Dynamo NEW_AND_OLD_IMAGES
            // (which carries pk+sk attrs). Without this whitelist, a portable
            // consumer reading event.data().get("partitionKey") would see the
            // value on Cosmos/Dynamo but null on Spanner.
            if (allowed.contains(name)
                    || SpannerConstants.FIELD_PARTITION_KEY.equals(name)
                    || SpannerConstants.FIELD_SORT_KEY.equals(name)) {
                continue;
            }
            drop.add(name);
        }
        for (String name : drop) obj.remove(name);
        return obj;
    }

    private ChangeType mapModType(String modType) {
        if (modType == null) return ChangeType.UPDATE;
        // Locale.ROOT — guards against the Turkish-locale trap where the
        // platform-default upper-case of "insert" turns into a non-ASCII
        // form and falls through to UPDATE. Matches the
        // CosmosChangeFeedReader.mapEvent toLowerCase(Locale.ROOT) idiom.
        switch (modType.toUpperCase(java.util.Locale.ROOT)) {
            case "INSERT": return ChangeType.CREATE;
            case "DELETE": return ChangeType.DELETE;
            case "UPDATE":
            default:        return ChangeType.UPDATE;
        }
    }

    // ── Child partitions extraction ────────────────────────────────────────

    /**
     * Extract the {@code start_timestamp} of a {@code child_partitions_record}
     * (the inclusive timestamp from which the child partitions become readable).
     * Falls back to the supplied default if the record omits it.
     */
    private static Timestamp childPartitionStart(Struct rec, Timestamp fallback) {
        Timestamp ts = getTimestampOrNull(rec, "start_timestamp");
        return ts != null ? ts : fallback;
    }

    /** Extract child partition tokens from an inner {@code child_partitions_record} struct. */
    private List<String> extractChildPartitionTokens(Struct rec) {
        List<String> tokens = new ArrayList<>();
        for (Struct c : getStructListOrEmpty(rec, "child_partitions")) {
            String tk = getStringOrNull(c, "token");
            if (tk != null) tokens.add(tk);
        }
        return tokens;
    }

    /** Extract the heartbeat timestamp from an inner {@code heartbeat_record} struct. */
    private Timestamp extractHeartbeatTimestamp(Struct rec) {
        return getTimestampOrNull(rec, "timestamp");
    }

    // ── Field name canonicalization ─────────────────────────────────────────

    /**
     * Resolve the actual (case-sensitive) field name Spanner returned for a
     * case-insensitive lookup key. Returns {@code null} if no field with that
     * name (under any casing) is present in the struct's type.
     */
    private static String canonicalField(Struct s, String field) {
        try {
            return s.getType().getStructFields().stream()
                    .map(f -> f.getName())
                    .filter(n -> n.equalsIgnoreCase(field))
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── Continuation encoding ──────────────────────────────────────────────

    private static final class ContState {
        final Timestamp startTs;
        final long recordSeq;
        /**
         * Wall-clock millisecond at which {@code listCursors()} minted this
         * cursor's lineage. Used to filter out events whose commit_timestamp
         * is before the cursor's intended live-tip anchor — necessary because
         * the Spanner change-stream TVF's {@code start_timestamp} parameter
         * is not always a strict lower bound on what the emulator returns
         * (commit-timestamp generation may lead the Java wall-clock that
         * {@code listCursors} captured). Zero or negative means "no filter
         * applied" — preserved for backward compatibility with continuations
         * minted before this filter was introduced.
         */
        final long anchorMs;
        ContState(Timestamp startTs, long recordSeq, long anchorMs) {
            this.startTs = startTs;
            this.recordSeq = recordSeq;
            this.anchorMs = anchorMs;
        }
    }

    /** Two-field continuation (legacy form — no anchor; no event filtering). */
    private static String continuation(Timestamp ts, long recordSeq) {
        return ts.toString() + "|" + recordSeq;
    }

    /**
     * Three-field continuation with an anchor wall-clock millisecond. New
     * cursors minted by {@code listCursors()} use this form so subsequent
     * {@code readChanges} calls can filter out events whose commit_timestamp
     * predates the live-tip anchor. The format is backward-compatible: older
     * 2-field continuations parse to {@code anchorMs == 0} (no filtering).
     */
    private static String continuation(Timestamp ts, long recordSeq, long anchorMs) {
        if (anchorMs <= 0L) return continuation(ts, recordSeq);
        return ts.toString() + "|" + recordSeq + "|" + anchorMs;
    }

    /**
     * Parse a Spanner change-feed continuation. Returns a {@link ContState} starting
     * from "now" only when the continuation is null/blank (a freshly bootstrapped
     * cursor with no recorded position). Any non-empty continuation that fails to
     * parse is surfaced as a {@link MalformedContinuation} — silently falling back
     * to {@link Timestamp#now()} would skip history and lie about the cursor's
     * actual position. Tolerates both 2-field (legacy, no anchor) and 3-field
     * (with anchorMs) formats for backward compatibility.
     */
    private static ContState parseContinuation(String c) {
        if (c == null || c.isBlank()) return new ContState(Timestamp.now(), 0L, 0L);
        try {
            String[] parts = c.split("\\|", -1);
            if (parts.length == 1) return new ContState(Timestamp.parseTimestamp(parts[0]), 0L, 0L);
            if (parts.length == 2) {
                return new ContState(Timestamp.parseTimestamp(parts[0]),
                        Long.parseLong(parts[1]), 0L);
            }
            if (parts.length == 3) {
                return new ContState(Timestamp.parseTimestamp(parts[0]),
                        Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]));
            }
            throw new IllegalArgumentException("expected 1, 2, or 3 pipe-delimited fields; got " + parts.length);
        } catch (RuntimeException e) {
            throw new MalformedContinuation(c, e);
        }
    }

    /** Marker for a continuation string we could not parse. Caught by readChanges. */
    private static final class MalformedContinuation extends RuntimeException {
        final String continuation;
        MalformedContinuation(String continuation, Throwable cause) {
            super("malformed Spanner change-feed continuation: " + continuation, cause);
            this.continuation = continuation;
        }
    }

    private static long parseSequence(Struct rec, String field) {
        String cf = canonicalField(rec, field);
        // Absent / null → 0L is fine (legacy continuations also use 0).
        if (cf == null || rec.isNull(cf)) return 0L;
        // record_sequence is typically STRING in Spanner change streams; the
        // emulator returns it as INT64 in some versions. Distinguish
        // "field absent" (handled above) from "field present but unparseable"
        // (e.g., a Spanner schema migration changed the column type or a row
        // is corrupted): the latter must surface as MalformedContinuation so
        // it is mapped to CursorExpired(MALFORMED) by readChanges — silently
        // substituting 0L would allow reordering across crash/resume, since
        // a continuation "commitTs|0|anchorMs" re-reads from the start of
        // that commit timestamp.
        try {
            return Long.parseLong(rec.getString(cf));
        } catch (Exception e1) {
            try {
                return rec.getLong(cf);
            } catch (Exception e2) {
                throw new MalformedContinuation(
                        "spanner change-stream record_sequence is present but unparseable", e2);
            }
        }
    }

    /**
     * Safe string accessor that honours canonical (case-insensitive) field lookup.
     * <p>
     * Spanner change-stream rows carry {@code mods.keys}, {@code mods.new_values}
     * and {@code mods.old_values} as {@code JSON}, not {@code STRING}, but emitter
     * versions disagree, so we transparently accept either Spanner type and
     * return the JSON-encoded payload as a Java {@link String}.
     */
    private static String getStringOrNull(Struct s, String field) {
        String cf = canonicalField(s, field);
        if (cf == null || s.isNull(cf)) return null;
        com.google.cloud.spanner.Type.Code code = s.getColumnType(cf).getCode();
        if (code == com.google.cloud.spanner.Type.Code.JSON) return s.getJson(cf);
        return s.getString(cf);
    }

    /** Safe timestamp accessor that honours canonical (case-insensitive) field lookup. */
    private static Timestamp getTimestampOrNull(Struct s, String field) {
        String cf = canonicalField(s, field);
        return (cf != null && !s.isNull(cf)) ? s.getTimestamp(cf) : null;
    }

    /** Safe struct-list accessor that honours canonical (case-insensitive) field lookup. */
    private static List<Struct> getStructListOrEmpty(Struct s, String field) {
        String cf = canonicalField(s, field);
        return (cf != null && !s.isNull(cf)) ? s.getStructList(cf) : List.of();
    }

    private static Timestamp addMillis(Timestamp t, long millis) {
        long total = t.getSeconds() * 1000L + t.getNanos() / 1_000_000L + millis;
        long secs = total / 1000L;
        int nanos = (int) ((total % 1000L) * 1_000_000L);
        return Timestamp.ofTimeSecondsAndNanos(secs, nanos);
    }

    /** Returns the later of two Spanner timestamps (a if equal). */
    private static Timestamp maxTimestamp(Timestamp a, Timestamp b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Convert a Spanner Timestamp to wall-clock epoch milliseconds (truncated). */
    private static long timestampToEpochMillis(Timestamp t) {
        return t.getSeconds() * 1000L + t.getNanos() / 1_000_000L;
    }

    /**
     * Obtain a Spanner-side strong-read snapshot timestamp. Guaranteed to be
     * greater than or equal to every commit_timestamp acknowledged before this
     * call started — exactly the "live tip" semantics a now() cursor needs.
     * <p>
     * Falls back to {@link Timestamp#now()} (Java wall-clock) if the strong-read
     * probe fails for any reason — preferring a possibly-too-early anchor over
     * a hard failure of {@code listCursors()}. In practice the probe succeeds
     * whenever the database is reachable.
     */
    private Timestamp readStrongSnapshotTimestamp() {
        try (ReadOnlyTransaction ro =
                databaseClient.singleUseReadOnlyTransaction(TimestampBound.strong())) {
            try (ResultSet rs = ro.executeQuery(Statement.of("SELECT 1"))) {
                if (rs.next()) {
                    Timestamp ts = ro.getReadTimestamp();
                    if (ts != null) return ts;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to Java wall-clock fallback
        }
        return Timestamp.now();
    }

    private static String sanitize(String streamName) {
        // Defensive: change-stream names follow Spanner identifier rules.
        // We refuse anything outside [A-Za-z0-9_].
        for (int i = 0; i < streamName.length(); i++) {
            char ch = streamName.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ok) {
                throw new IllegalArgumentException(
                        "Invalid Spanner change-stream name (must match [A-Za-z0-9_]+): " + streamName);
            }
        }
        return streamName;
    }

    private MulticloudDbException mapSpannerException(SpannerException e, String operation) {
        Map<String, String> details = new HashMap<>();
        details.put("errorCode", e.getErrorCode().name());
        boolean retryable = e.isRetryable()
                || e.getErrorCode() == ErrorCode.UNAVAILABLE
                || e.getErrorCode() == ErrorCode.DEADLINE_EXCEEDED;
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR, e.getMessage(),
                providerId, operation, retryable, details), e);
    }
}
