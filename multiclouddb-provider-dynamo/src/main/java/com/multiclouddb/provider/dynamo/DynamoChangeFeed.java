// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DynamoDB Streams change-feed adapter.
 * <p>
 * Maps the SDK's portable change-feed contract onto Dynamo's
 * {@code DescribeStream} + {@code GetShardIterator} + {@code GetRecords}
 * primitives.
 *
 * <p>{@link NewItemStateMode#INCLUDE_IF_AVAILABLE} populates {@code data}
 * when the stream's {@code StreamViewType} includes {@code NEW_IMAGE} or
 * {@code NEW_AND_OLD_IMAGES}; otherwise {@code data} is {@code null}.
 */
final class DynamoChangeFeed {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoChangeFeed.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DynamoDbClient dynamo;
    private final DynamoDbStreamsClient streams;

    DynamoChangeFeed(DynamoDbClient dynamo, DynamoDbStreamsClient streams) {
        this.dynamo = dynamo;
        this.streams = streams;
    }

    ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options) {
        Instant start = Instant.now();
        try {
            String streamArn = resolveStreamArn(request.address());

            // Resolve which shards to read this call
            ShardSelection selection = resolveShards(request, streamArn);
            // The "anchor" is the original starting intent (Beginning / Now). It
            // is persisted in the continuation token so that, on resume after a
            // page that observed no records on a shard (lastSeq still null), we
            // request the correct iterator type instead of falling through to
            // TRIM_HORIZON for a now()-based feed (which would silently replay
            // the entire 24h shard history).
            String anchor = selection.anchor != null
                    ? selection.anchor
                    : anchorFromStartPosition(request.startPosition());

            int maxRecordsPerShard = request.maxPageSize() > 0 ? request.maxPageSize() : 100;
            List<ChangeEvent> events = new ArrayList<>();
            Map<String, ShardCursor> nextCursors = new LinkedHashMap<>();

            for (Shard shard : selection.shardsToRead) {
                ShardCursor existing = selection.cursors.get(shard.shardId());
                String iterator = obtainIterator(streamArn, shard, existing, anchor);
                if (iterator == null) {
                    // already closed and fully consumed — skip
                    continue;
                }
                GetRecordsResponse resp;
                try {
                    resp = streams.getRecords(GetRecordsRequest.builder()
                            .shardIterator(iterator)
                            .limit(maxRecordsPerShard)
                            .build());
                } catch (ExpiredIteratorException eie) {
                    // The persisted nextShardIterator has expired between calls.
                    // Re-issue an iterator from anchor / sequence number and retry.
                    LOG.debug("DynamoDB shard iterator expired for shard {}; refreshing", shard.shardId());
                    ShardCursor refreshed = existing == null ? null
                            : new ShardCursor(existing.lastSeq, existing.drained);
                    String fresh = obtainIterator(streamArn, shard, refreshed, anchor);
                    if (fresh == null) continue;
                    resp = streams.getRecords(GetRecordsRequest.builder()
                            .shardIterator(fresh)
                            .limit(maxRecordsPerShard)
                            .build());
                }

                String lastSeq = existing != null ? existing.lastSeq : null;
                for (Record r : resp.records()) {
                    ChangeEvent ev = mapEvent(r, request);
                    if (ev != null) {
                        events.add(ev);
                    }
                    lastSeq = r.dynamodb().sequenceNumber();
                }

                if (resp.nextShardIterator() == null) {
                    // shard is closed and fully drained; do not include it in the next cursor
                } else {
                    nextCursors.put(shard.shardId(), new ShardCursor(
                            lastSeq, false, resp.nextShardIterator(),
                            System.currentTimeMillis()));
                }
            }

            String token = encodeToken(request.address(), streamArn, nextCursors, anchor);

            OperationDiagnostics diag = OperationDiagnostics
                    .builder(ProviderId.DYNAMO, "readChanges",
                            Duration.between(start, Instant.now()))
                    .itemCount(events.size())
                    .build();
            return new ChangeFeedPage(events, token, diag);
        } catch (MulticloudDbException e) {
            throw e;
        } catch (Exception e) {
            throw mapDynamoException(e, "readChanges");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String resolveStreamArn(ResourceAddress address) {
        // The provider encodes (database, collection) as a single physical
        // table name via DynamoConstants.tableNameFor — every CRUD/query call
        // site goes through resolveTableName(). Using bare address.collection()
        // here would skip that encoding and fail to find the table for any
        // non-empty database.
        String tableName = DynamoConstants.tableNameFor(address);
        DescribeTableResponse resp = dynamo.describeTable(DescribeTableRequest.builder()
                .tableName(tableName)
                .build());
        String arn = resp.table().latestStreamArn();
        if (arn == null || arn.isBlank()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "DynamoDB table '" + tableName + "' does not have a stream enabled. "
                            + "Enable streams (StreamSpecification.StreamEnabled=true) before using "
                            + "the change feed.",
                    ProviderId.DYNAMO, "readChanges", false,
                    Map.of("table", tableName)));
        }
        return arn;
    }


    private List<Shard> describeAllShards(String streamArn) {
        List<Shard> all = new ArrayList<>();
        String exclusive = null;
        while (true) {
            DescribeStreamResponse resp = streams.describeStream(DescribeStreamRequest.builder()
                    .streamArn(streamArn)
                    .exclusiveStartShardId(exclusive)
                    .build());
            all.addAll(resp.streamDescription().shards());
            exclusive = resp.streamDescription().lastEvaluatedShardId();
            if (exclusive == null || exclusive.isEmpty()) {
                break;
            }
        }
        return all;
    }

    private ShardSelection resolveShards(ChangeFeedRequest request, String streamArn) {
        Map<String, ShardCursor> cursors = decodeCursors(request, streamArn);
        String anchor = anchorFromToken(request);
        List<Shard> all = describeAllShards(streamArn);

        // EntireCollection — read all open shards (or shards still on the cursor).
        Set<String> liveIds = new HashSet<>();
        for (Shard s : all) liveIds.add(s.shardId());

        // Cursors referencing shards that are no longer in the stream
        // description are dangerous: they can mean (a) the shard was
        // drained and aged out (legitimate), or (b) the stream was
        // recreated / the cursor predates retention, in which case
        // silently dropping it loses unread events. Fail with
        // CHECKPOINT_EXPIRED so the caller restarts deliberately.
        Set<String> vanished = new LinkedHashSet<>();
        for (String sid : cursors.keySet()) {
            if (!liveIds.contains(sid)) vanished.add(sid);
        }
        if (!vanished.isEmpty()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                    "Continuation token references shards " + vanished
                            + " that are no longer present in the DynamoDB stream description "
                            + "(stream may have been recreated, or the cursor is older than the "
                            + "24h retention window). Restart from beginning() or now().",
                    ProviderId.DYNAMO, "readChanges", false, Map.of()));
        }

        // Read shards that are open OR have a cursor (need to drain closed-but-cursored).
        // For a fresh beginning() request (no cursors), also include closed
        // parent shards: TRIM_HORIZON on a child shard starts at the split
        // point, so any pre-split records still inside the closed parent
        // would otherwise be missed. Records older than the 24h trim
        // horizon are gone either way.
        boolean fromBeginningFresh = cursors.isEmpty()
                && ANCHOR_BEGINNING.equals(anchor != null ? anchor
                        : anchorFromStartPosition(request.startPosition()));
        List<Shard> toRead = new ArrayList<>();
        for (Shard s : all) {
            if (!isClosed(s)
                    || cursors.containsKey(s.shardId())
                    || fromBeginningFresh) {
                toRead.add(s);
            }
        }
        return new ShardSelection(toRead, cursors, anchor);
    }

    private Map<String, ShardCursor> decodeCursors(ChangeFeedRequest request, String streamArn) {
        if (!(request.startPosition() instanceof StartPosition.FromContinuationToken token)) {
            return new LinkedHashMap<>();
        }
        JsonNode inner = ContinuationTokenCodec.decode(token.token(),
                ProviderId.DYNAMO, request.address());
        if (!inner.isObject()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Malformed Dynamo continuation token (inner object expected)",
                    ProviderId.DYNAMO, "readChanges", false, Map.of()));
        }
        String tokenArn = inner.path("streamArn").asText("");
        if (!streamArn.equals(tokenArn)) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Continuation token was issued for stream '" + tokenArn
                            + "' but the table now points at '" + streamArn + "'. "
                            + "The stream was recreated; restart from beginning() or now().",
                    ProviderId.DYNAMO, "readChanges", false, Map.of()));
        }
        Map<String, ShardCursor> map = new LinkedHashMap<>();
        for (JsonNode shard : inner.path("shards")) {
            String iter = shard.path("iter").asText(null);
            long iterAt = shard.path("iterAt").asLong(0L);
            map.put(shard.path("shardId").asText(),
                    new ShardCursor(shard.path("lastSeq").asText(null), false, iter, iterAt));
        }
        return map;
    }

    /**
     * Recover the original starting intent from the continuation token (if
     * any). Older tokens that pre-date this field will return {@code null} —
     * the caller must fall back to {@link #anchorFromStartPosition} for fresh
     * requests, or default to {@code TRIM_HORIZON} (fail-safe) on legacy
     * resume.
     */
    private String anchorFromToken(ChangeFeedRequest request) {
        if (!(request.startPosition() instanceof StartPosition.FromContinuationToken token)) {
            return null;
        }
        JsonNode inner = ContinuationTokenCodec.decode(token.token(),
                ProviderId.DYNAMO, request.address());
        String anchor = inner.path("anchor").asText(null);
        return (anchor == null || anchor.isEmpty()) ? null : anchor;
    }

    private static String anchorFromStartPosition(StartPosition sp) {
        if (sp instanceof StartPosition.Now) return ANCHOR_NOW;
        if (sp instanceof StartPosition.Beginning) return ANCHOR_BEGINNING;
        // FromContinuationToken — anchor is recovered from the token
        // itself. Return null so callers fall back.
        return null;
    }

    private String encodeToken(ResourceAddress address, String streamArn,
                               Map<String, ShardCursor> cursors, String anchor) {
        if (cursors.isEmpty()) {
            return null;
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("streamArn", streamArn);
        if (anchor != null) {
            envelope.put("anchor", anchor);
        }
        ArrayNode arr = envelope.putArray("shards");
        for (Map.Entry<String, ShardCursor> e : cursors.entrySet()) {
            ObjectNode s = MAPPER.createObjectNode();
            s.put("shardId", e.getKey());
            ShardCursor c = e.getValue();
            if (c.lastSeq != null) {
                s.put("lastSeq", c.lastSeq);
            }
            if (c.lastSeq == null && c.nextIter != null) {
                // Only persist nextIter when there's no sequence-number checkpoint:
                // sequence numbers are the durable cursor; iterators are a 5-min
                // optimization for the empty-now case.
                s.put("iter", c.nextIter);
                s.put("iterAt", c.iterAtMs);
            }
            arr.add(s);
        }
        return ContinuationTokenCodec.encode(ProviderId.DYNAMO, address, envelope);
    }

    private String obtainIterator(String streamArn, Shard shard, ShardCursor cursor,
                                  String anchor) {
        // If the prior call left a still-fresh nextShardIterator (and we have no
        // sequence-number checkpoint), use it directly. This closes the gap that
        // otherwise occurs on a now()-anchored shard whose first read returned
        // zero records: falling back to LATEST on resume would skip any records
        // written between calls.
        if (cursor != null && cursor.lastSeq == null && cursor.nextIter != null) {
            long age = System.currentTimeMillis() - cursor.iterAtMs;
            if (age >= 0 && age <= ITERATOR_MAX_AGE_MS) {
                return cursor.nextIter;
            }
            LOG.debug("DynamoDB nextShardIterator for shard {} is older than {}ms (age={}ms); "
                    + "falling back to anchor-based iterator (gap possible if records arrived "
                    + "after the previous empty read)", shard.shardId(), ITERATOR_MAX_AGE_MS, age);
        }
        GetShardIteratorRequest.Builder req = GetShardIteratorRequest.builder()
                .streamArn(streamArn)
                .shardId(shard.shardId());
        if (cursor != null && cursor.lastSeq != null) {
            req.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    .sequenceNumber(cursor.lastSeq);
        } else if (ANCHOR_NOW.equals(anchor)) {
            // Original intent was now() — must keep using LATEST on resume so
            // that idle shards don't replay 24h of history when they finally
            // emit records.
            req.shardIteratorType(ShardIteratorType.LATEST);
        } else {
            // Beginning (or unknown anchor — fail safe to TRIM_HORIZON).
            req.shardIteratorType(ShardIteratorType.TRIM_HORIZON);
        }
        try {
            GetShardIteratorResponse resp = streams.getShardIterator(req.build());
            return resp.shardIterator();
        } catch (TrimmedDataAccessException e) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                    "DynamoDB stream cursor for shard " + shard.shardId()
                            + " has been trimmed (24-hour retention exceeded). "
                            + "Restart from StartPosition.beginning() or now().",
                    ProviderId.DYNAMO, "readChanges", false,
                    Map.of("shardId", shard.shardId())), e);
        }
    }

    private List<String> findChildren(String streamArn, String shardId) {
        List<Shard> all = describeAllShards(streamArn);
        List<String> children = new ArrayList<>();
        for (Shard s : all) {
            if (shardId.equals(s.parentShardId())) {
                children.add(s.shardId());
            }
        }
        return children;
    }

    private boolean isClosed(Shard s) {
        return s.sequenceNumberRange() != null
                && s.sequenceNumberRange().endingSequenceNumber() != null;
    }

    private ChangeEvent mapEvent(Record r, ChangeFeedRequest request) {
        if (r == null || r.dynamodb() == null) {
            return null;
        }
        StreamRecord sr = r.dynamodb();
        ChangeType type;
        OperationType op = r.eventName();
        if (op == OperationType.INSERT) {
            type = ChangeType.CREATE;
        } else if (op == OperationType.MODIFY) {
            type = ChangeType.UPDATE;
        } else if (op == OperationType.REMOVE) {
            type = ChangeType.DELETE;
        } else {
            LOG.debug("Skipping Dynamo record with unknown eventName '{}'", op);
            return null;
        }

        // Key extraction — we use the existing convention (partitionKey, sortKey attributes).
        Map<String, AttributeValue> keyAttrs = sr.keys();
        if (keyAttrs == null || keyAttrs.isEmpty()) {
            return null;
        }
        AttributeValue pk = keyAttrs.get(DynamoConstants.ATTR_PARTITION_KEY);
        AttributeValue sk = keyAttrs.get(DynamoConstants.ATTR_SORT_KEY);
        if (pk == null) {
            return null;
        }
        String pkStr = attrToString(pk);
        String skStr = sk != null ? attrToString(sk) : null;
        MulticloudDbKey key = (skStr != null && !skStr.equals(pkStr))
                ? MulticloudDbKey.of(pkStr, skStr)
                : MulticloudDbKey.of(pkStr);

        ObjectNode data = null;
        if (request.newItemStateMode() != NewItemStateMode.OMIT
                && type != ChangeType.DELETE
                && sr.newImage() != null && !sr.newImage().isEmpty()) {
            data = imageToJson(sr.newImage());
        }

        Instant ts = sr.approximateCreationDateTime();
        return new ChangeEvent(ProviderId.DYNAMO, sr.sequenceNumber(), type,
                request.address(), key, data, ts);
    }

    private static String attrToString(AttributeValue v) {
        if (v.s() != null) return v.s();
        if (v.n() != null) return v.n();
        if (v.bool() != null) return v.bool().toString();
        return v.toString();
    }

    static ObjectNode imageToJson(Map<String, AttributeValue> image) {
        ObjectNode obj = MAPPER.createObjectNode();
        for (Map.Entry<String, AttributeValue> e : image.entrySet()) {
            obj.set(e.getKey(), attrToJson(e.getValue()));
        }
        return obj;
    }

    /**
     * Recursively map a DynamoDB {@link AttributeValue} into a Jackson node.
     * Covers all wire types: S, N, BOOL, NULL, M, L, B, SS, NS, BS. Unknown
     * shapes fall back to a {@link com.fasterxml.jackson.databind.node.NullNode}
     * with a debug log so we never silently corrupt the payload.
     */
    private static JsonNode attrToJson(AttributeValue v) {
        if (v == null) return MAPPER.nullNode();
        if (Boolean.TRUE.equals(v.nul())) return MAPPER.nullNode();
        if (v.s() != null) return MAPPER.getNodeFactory().textNode(v.s());
        if (v.n() != null) {
            try {
                return MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal(v.n()));
            } catch (NumberFormatException nfe) {
                return MAPPER.getNodeFactory().textNode(v.n());
            }
        }
        if (v.bool() != null) return MAPPER.getNodeFactory().booleanNode(v.bool());
        if (v.b() != null) {
            // Binary scalar — emit as base64 string (round-trippable through JSON).
            return MAPPER.getNodeFactory().textNode(
                    java.util.Base64.getEncoder().encodeToString(v.b().asByteArray()));
        }
        if (v.hasM()) {
            ObjectNode obj = MAPPER.createObjectNode();
            for (Map.Entry<String, AttributeValue> e : v.m().entrySet()) {
                obj.set(e.getKey(), attrToJson(e.getValue()));
            }
            return obj;
        }
        if (v.hasL()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (AttributeValue child : v.l()) {
                arr.add(attrToJson(child));
            }
            return arr;
        }
        if (v.hasSs()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (String s : v.ss()) arr.add(s);
            return arr;
        }
        if (v.hasNs()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (String n : v.ns()) {
                try {
                    arr.add(new java.math.BigDecimal(n));
                } catch (NumberFormatException nfe) {
                    LOG.debug("Dynamo NS element '{}' failed to parse as BigDecimal; "
                            + "storing as string", n);
                    arr.add(n);
                }
            }
            return arr;
        }
        if (v.hasBs()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (software.amazon.awssdk.core.SdkBytes b : v.bs()) {
                arr.add(java.util.Base64.getEncoder().encodeToString(b.asByteArray()));
            }
            return arr;
        }
        LOG.debug("Unknown DynamoDB AttributeValue shape; emitting null. type={}", v.type());
        return MAPPER.nullNode();
    }


    private MulticloudDbException mapDynamoException(Exception e, String op) {
        if (e instanceof TrimmedDataAccessException) {
            return new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                    "DynamoDB stream data has been trimmed (24h retention exceeded). "
                            + "Restart from beginning() or now(). Original: " + e.getMessage(),
                    ProviderId.DYNAMO, op, false, Map.of()), e);
        }
        // Defer to the provider-wide error mapper so ResourceNotFoundException,
        // ValidationException, AccessDeniedException, ThrottlingException, etc.
        // keep their normal NOT_FOUND / INVALID_REQUEST / AUTHORIZATION_FAILED /
        // THROTTLED categorization — the same contract the rest of the Dynamo
        // module honours.
        if (e instanceof DynamoDbException dde) {
            return DynamoErrorMapper.map(dde, op);
        }
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "DynamoDB Streams error: " + e.getMessage(),
                ProviderId.DYNAMO, op, false,
                Map.of("exceptionType", e.getClass().getName())), e);
    }

    /**
     * Per-shard cursor persisted in the continuation token.
     * <ul>
     *   <li>{@code lastSeq} — sequence number of the last record returned, used
     *       as {@code AFTER_SEQUENCE_NUMBER} on resume. {@code null} when the
     *       shard returned zero records on the prior call.</li>
     *   <li>{@code drained} — {@code true} once the shard is closed and fully
     *       consumed; such cursors are dropped from subsequent tokens.</li>
     *   <li>{@code nextIter} — the {@code NextShardIterator} returned by the
     *       prior {@code GetRecords} call. Used to resume an empty
     *       {@link StartPosition#now()} read without falling back to
     *       {@code LATEST} (which would skip records written between calls).
     *       Iterators expire after ~5 minutes — we still fall back gracefully
     *       on {@link ExpiredIteratorException}.</li>
     *   <li>{@code iterAtMs} — wall-clock millis at which {@code nextIter}
     *       was issued; we only attempt to reuse iterators younger than
     *       {@link #ITERATOR_MAX_AGE_MS}.</li>
     * </ul>
     */
    private record ShardCursor(String lastSeq, boolean drained, String nextIter, long iterAtMs) {
        ShardCursor(String lastSeq, boolean drained) {
            this(lastSeq, drained, null, 0L);
        }
    }

    /** Iterators returned by GetRecords expire after 5 min; allow some safety margin. */
    private static final long ITERATOR_MAX_AGE_MS = 4 * 60 * 1000L;

    private record ShardSelection(List<Shard> shardsToRead, Map<String, ShardCursor> cursors,
                                  String anchor) { }

    /** Anchor token persisted in the continuation envelope. */
    private static final String ANCHOR_BEGINNING = "Beginning";
    private static final String ANCHOR_NOW = "Now";
}
