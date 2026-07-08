// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.multiclouddb.api.changefeed.internal.PartitionPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Cosmos DB pull-mode change-feed reader.
 * <p>
 * Maps the {@link com.multiclouddb.api.MulticloudDbClient} change-feed primitives
 * onto Cosmos DB's pull-model change-feed API:
 * <ul>
 *   <li>{@link CosmosContainer#getFeedRanges()} → one cursor per feed range from
 *       {@link #listCursors}.</li>
 *   <li>{@link CosmosContainer#queryChangeFeed(CosmosChangeFeedRequestOptions, Class)}
 *       drives every {@link #readChanges} call, always in
 *       <b>All-Versions-and-Deletes (AVAD)</b> mode via
 *       {@link CosmosChangeFeedRequestOptions#allVersionsAndDeletes()}.</li>
 *   <li>Continuation tokens flow back into the next cursor; if a request returns
 *       {@code 410 GONE} the cursor is treated as expired.</li>
 * </ul>
 * <p>
 * AVAD mode is the only mode supported by this reader, so
 * {@link ChangeEvent#type()} faithfully distinguishes
 * {@link ChangeType#CREATE}, {@link ChangeType#UPDATE}, and
 * {@link ChangeType#DELETE} for every event — matching the contract
 * surfaced by the Dynamo and Spanner readers. The Cosmos container the
 * caller targets must therefore be provisioned with a Cosmos AVAD change-feed
 * policy on an account that supports it (see
 * {@code ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(Duration)}); a
 * container without AVAD provisioning will surface a Cosmos {@code 400}
 * BadRequest the first time {@code listCursors} or {@code readChanges}
 * is called, which this reader normalises to
 * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} with
 * {@code providerDetails.reason="avad_not_enabled"} — portable with the
 * Dynamo and Spanner {@code reason="stream_not_enabled"} normalisations
 * for the same operational mistake.
 */
final class CosmosChangeFeedReader {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosChangeFeedReader.class);
    private static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * Sentinel continuation used in a hydrated-but-unread cursor for a freshly
     * minted partition. The Cosmos pull-API does not expose "live tip" as a
     * continuation token directly; we encode it as a synthetic marker and replace
     * it with {@link CosmosChangeFeedRequestOptions#createForProcessingFromNow(FeedRange)}
     * on the first read.
     */
    private static final String CONT_FROM_NOW = "@@FROM_NOW";
    /**
     * Continuation prefix anchoring a cursor to a specific point in time, captured at
     * {@code listCursors()} or {@code now()}-hydrate time. The suffix is an epoch-millis
     * timestamp. Using a point-in-time anchor (rather than the {@code CONT_FROM_NOW}
     * sentinel which re-anchors to the moment of the read) prevents silent event loss
     * for writes that happen between cursor mint and first read.
     */
    private static final String CONT_PIT_PREFIX = "@@PIT:";

    private final ProviderId providerId;
    /**
     * Effective client-side age cap stamped onto every minted {@link CursorToken}.
     * Resolved from {@code MulticloudDbClientConfig.changeFeed().extendedRetention()}:
     * the opt-in window when present, else the portable 24h baseline. Persisted
     * tokens carry this so an opted-in caller can resume a cursor older than 24h
     * (up to the server-side retention) without {@code TOKEN_AGED_OUT}.
     */
    private final long effectiveRetentionMillis;

    CosmosChangeFeedReader(ProviderId providerId) {
        this(providerId, CursorTokenCodec.MAX_TOKEN_AGE_MILLIS);
    }

    CosmosChangeFeedReader(ProviderId providerId, long effectiveRetentionMillis) {
        this.providerId = providerId;
        this.effectiveRetentionMillis = effectiveRetentionMillis;
    }

    /**
     * Enumerate the container's feed ranges and mint one {@link ChangeFeedCursor}
     * per range, each positioned at the live tip.
     * <p>
     * For each range we eagerly execute a {@link CosmosChangeFeedRequestOptions#createForProcessingFromNow(FeedRange)
     * createForProcessingFromNow} query and persist the returned continuation
     * token. This "warmup" round-trip captures a real bookmark at mint time so
     * that any subsequent {@code readChanges()} resumes from that exact point —
     * not from a re-anchored {@code FROM_NOW} at read-time, which would silently
     * skip any events written between cursor mint and first read. If the warmup
     * fails (network error, no continuation returned), we fall back to a
     * {@link #CONT_PIT_PREFIX} timestamp anchor, then to the legacy
     * {@link #CONT_FROM_NOW} sentinel.
     * <p>
     * The {@code issuedAtEpochMillis} stamped on each minted {@link CursorToken}
     * is captured <em>after</em> the warmup query returns (warmup-success path)
     * or <em>at the moment the PIT fallback decision is made</em>, i.e. after
     * the warmup attempt fails (PIT fallback path) — never before the warmup
     * started. This preserves the public contract that {@code readChanges()}
     * must not surface events that occurred before {@code listCursors()}
     * returns: a PIT timestamp captured pre-warmup would re-anchor at that
     * earlier instant and re-surface any events written during the warmup call
     * itself. On the PIT fallback path the encoded anchor and stamped
     * {@code issuedAt} agree by construction (the same {@code nowMs} is
     * embedded in the {@code @@PIT:<nowMs>} suffix and used as
     * {@code issuedAtEpochMillis}). This matches the semantics already used by
     * {@link #readChanges} (which captures {@code System.currentTimeMillis()}
     * after each page is read).
     */
    List<ChangeFeedCursor> listCursors(CosmosContainer container, ResourceAddress address) {
        try {
            List<FeedRange> ranges = container.getFeedRanges();
            if (ranges == null || ranges.isEmpty()) {
                ranges = Collections.singletonList(FeedRange.forFullRange());
            }
            List<ChangeFeedCursor> cursors = new ArrayList<>(ranges.size());
            for (FeedRange range : ranges) {
                WarmupResult w = warmupContinuation(container, range);
                PartitionPosition pos = new PartitionPosition(encodeRange(range), w.continuation());
                CursorToken token = new CursorToken(
                        providerId, address, w.effectiveAtMs(), CursorAnchor.NOW, List.of(pos),
                        effectiveRetentionMillis);
                cursors.add(new ChangeFeedCursor(token));
            }
            return cursors;
        } catch (CosmosException e) {
            MulticloudDbException unsupported = maybeAvadNotEnabled(e, "listCursors");
            if (unsupported != null) throw unsupported;
            throw CosmosErrorMapper.map(e, "listCursors");
        }
    }

    /**
     * Detect the Cosmos 400-BadRequest fingerprint of "container is not
     * provisioned for All-Versions-and-Deletes" and re-map it to the portable
     * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} so callers get
     * the same actionable signal the Dynamo and Spanner readers surface for
     * the same operational mistake (stream / change-stream not enabled).
     * <p>
     * Without this re-mapping a non-AVAD container would surface
     * {@code INVALID_REQUEST} via the generic {@link CosmosErrorMapper},
     * forcing portable consumers to substring-match the message on Cosmos to
     * disambiguate provisioning failures from genuine malformed-input errors.
     * Returns {@code null} if the exception is not the AVAD-not-enabled
     * fingerprint, so the caller falls through to the generic mapper.
     */
    private MulticloudDbException maybeAvadNotEnabled(CosmosException e, String operation) {
        if (e.getStatusCode() != 400) return null;
        String msg = e.getMessage();
        if (msg == null) return null;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        // Cosmos surfaces this with phrasing that includes either the policy
        // class name or the "change feed mode" wording depending on the
        // service-side message version. Match defensively on both.
        boolean fingerprint = lower.contains("allversionsanddeletes")
                || lower.contains("all versions and deletes")
                || lower.contains("change feed mode")
                || lower.contains("changefeedpolicy");
        if (!fingerprint) return null;
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                "Cosmos container is not provisioned for All-Versions-and-Deletes (AVAD). "
                        + "Recreate the container with ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(...) "
                        + "on an account that supports continuous backup. Underlying message: " + msg,
                providerId, operation, false,
                Map.of("reason", "avad_not_enabled",
                        "statusCode", String.valueOf(e.getStatusCode()))), e);
    }

    /**
     * Holder for a warmup result: the continuation token that bookmarks the
     * live tip plus the wall-clock instant at which that bookmark is effective.
     * <p>
     * On the warmup-success path {@code effectiveAtMs} is captured immediately
     * after {@code FeedResponse.getContinuationToken()} returns, so it reflects
     * when the bookmark actually materialised (not when the warmup call
     * started). On the PIT fallback path {@code effectiveAtMs == nowMs} by
     * construction — the same value is embedded in the
     * {@code @@PIT:<nowMs>} suffix so the encoded anchor and the stamped
     * {@code issuedAtEpochMillis} agree.
     */
    private record WarmupResult(String continuation, long effectiveAtMs) {}

    /**
     * Execute one {@code createForProcessingFromNow(range)} query to obtain a real
     * Cosmos continuation token that bookmarks the live tip at this instant.
     * Falls back to a {@link #CONT_PIT_PREFIX} timestamp anchor (then to
     * {@link #CONT_FROM_NOW}) if the warmup query cannot produce one.
     * <p>
     * The returned {@link WarmupResult} carries both the continuation string
     * and the wall-clock instant at which that bookmark is effective. On the
     * warmup-success path {@code effectiveAtMs} is captured immediately after
     * {@link FeedResponse#getContinuationToken()} returns. On the PIT fallback
     * path {@code effectiveAtMs} is captured <em>at the fallback decision
     * point</em> (i.e. after the warmup attempt has completed or failed) — not
     * before warmup started — so any event written during the warmup itself is
     * <em>strictly older</em> than the encoded PIT anchor and stays out of the
     * first page. The same value is embedded in {@code @@PIT:<nowMs>} so the
     * encoded anchor and the {@code issuedAtEpochMillis} stamped on the cursor
     * agree by construction.
     */
    private WarmupResult warmupContinuation(CosmosContainer container, FeedRange range) {
        try {
            CosmosChangeFeedRequestOptions warmup =
                    CosmosChangeFeedRequestOptions.createForProcessingFromNow(range)
                            .allVersionsAndDeletes();
            warmup.setMaxItemCount(1);
            Iterator<FeedResponse<JsonNode>> it =
                    container.queryChangeFeed(warmup, JsonNode.class).iterableByPage().iterator();
            if (it.hasNext()) {
                String c = it.next().getContinuationToken();
                // Capture wall-clock immediately after the bookmark materialises so
                // the CursorToken's issuedAt matches the instant the continuation
                // is valid for — not the moment we *started* warming up.
                long effectiveAt = System.currentTimeMillis();
                if (c != null && !c.isBlank()) return new WarmupResult(c, effectiveAt);
            }
        } catch (RuntimeException e) {
            // Fall through to the timestamp anchor below.
        }
        // PIT fallback: capture the timestamp *at this point* — after the warmup
        // attempt completed or failed — so any event written during the warmup is
        // strictly older than the encoded anchor and stays out of the first page.
        long fallbackAt = System.currentTimeMillis();
        return new WarmupResult(CONT_PIT_PREFIX + fallbackAt, fallbackAt);
    }

    /**
     * Drain one page of events from the given cursor. The returned page carries
     * a fresh {@code nextCursor()} with refreshed continuation tokens.
     */
    ChangeFeedPage readChanges(CosmosContainer container, ResourceAddress address,
                               ChangeFeedCursor cursor, OperationOptions options) {
        CursorToken token;
        if (cursor.isUnhydratedSentinel()) {
            // Hydrate the sentinel by re-discovering ranges.
            List<ChangeFeedCursor> all = listCursors(container, address);
            // Merge their partition positions into a single token.
            List<PartitionPosition> positions = new ArrayList<>(all.size());
            for (ChangeFeedCursor c : all) positions.addAll(c.token().partitions());
            token = new CursorToken(providerId, address,
                    System.currentTimeMillis(), CursorAnchor.NOW, positions,
                    effectiveRetentionMillis);
        } else {
            token = cursor.token();
        }

        if (token.partitions().isEmpty()) {
            // Nothing to read; return an empty caught-up page.
            CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
        }

        // Round-robin across partitions: read one page from the head partition
        // per call. The partition list order is the active-partition state —
        // see the rotation at the bottom of the try block. The 410 GONE catch
        // throws CursorExpiredException, so no cursor is returned and rotation
        // is moot on that path — recovery is a fresh listCursors() call.
        // Without rotation, multi-partition cursors (e.g., the now() sentinel
        // hydrate that merges all feed ranges) would starve every partition
        // after index 0.
        List<PartitionPosition> partitions = new ArrayList<>(token.partitions());
        PartitionPosition pos = partitions.get(0);
        FeedRange range = decodeRange(pos.partitionId());

        CosmosChangeFeedRequestOptions opts;
        String cont = pos.continuation();
        if (cont != null && cont.startsWith(CONT_PIT_PREFIX)) {
            // Resume from the exact instant captured at mint time. A corrupted /
            // tampered token (non-numeric suffix) must surface as the portable
            // CursorExpiredException(MALFORMED) — never as an unchecked
            // NumberFormatException.
            long pitMs;
            try {
                pitMs = Long.parseLong(cont.substring(CONT_PIT_PREFIX.length()));
            } catch (NumberFormatException nfe) {
                throw new CursorExpiredException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CURSOR_EXPIRED,
                        "Cosmos cursor has a malformed @@PIT continuation: " + cont,
                        providerId, "readChanges", false,
                        Map.of(CursorTokenCodec.DETAIL_REASON, CursorTokenCodec.REASON_MALFORMED)), nfe);
            }
            opts = CosmosChangeFeedRequestOptions.createForProcessingFromPointInTime(
                    java.time.Instant.ofEpochMilli(pitMs), range);
        } else if (CONT_FROM_NOW.equals(cont) || cont == null) {
            opts = CosmosChangeFeedRequestOptions.createForProcessingFromNow(range);
        } else {
            opts = CosmosChangeFeedRequestOptions.createForProcessingFromContinuation(cont);
        }
        opts = opts.allVersionsAndDeletes();
        opts.setMaxItemCount(DEFAULT_PAGE_SIZE);

        try {
            CosmosPagedIterable<JsonNode> iterable = container.queryChangeFeed(opts, JsonNode.class);
            Iterator<FeedResponse<JsonNode>> pageIter = iterable.iterableByPage().iterator();

            List<ChangeEvent> events = new ArrayList<>();
            String newContinuation = pos.continuation();
            boolean hasMore = false;

            if (pageIter.hasNext()) {
                FeedResponse<JsonNode> page = pageIter.next();
                for (JsonNode item : page.getResults()) {
                    events.add(mapEvent(item));
                }
                String c = page.getContinuationToken();
                if (c != null && !c.isBlank()) newContinuation = c;
                // hasMore is true iff the page hit the size cap; otherwise we're caught up.
                hasMore = page.getResults().size() >= DEFAULT_PAGE_SIZE;
            }

            partitions.set(0, new PartitionPosition(pos.partitionId(), newContinuation));
            // Rotate the just-advanced partition to the end so the next call
            // visits the next partition in round-robin order.
            if (partitions.size() > 1) {
                Collections.rotate(partitions, -1);
                // If this call returned events from a multi-partition cursor,
                // the OTHER partitions may also have events ready; signal
                // hasMore so the caller keeps draining before sleeping.
                if (!events.isEmpty()) hasMore = true;
            }
            CursorToken nextToken = token.withPartitions(partitions, System.currentTimeMillis());
            return new ChangeFeedPage(events, new ChangeFeedCursor(nextToken), hasMore, false);
        } catch (CosmosException e) {
            // 410 GONE → cursor expired (provider trimmed events)
            if (e.getStatusCode() == 410) {
                throw new CursorExpiredException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CURSOR_EXPIRED,
                        "Cosmos returned 410 GONE; the cursor's events were trimmed",
                        providerId,
                        "readChanges",
                        false,
                        Map.of(CursorTokenCodec.DETAIL_REASON, CursorTokenCodec.REASON_PROVIDER_TRIMMED,
                                "statusCode", String.valueOf(e.getStatusCode()))), e);
            }
            // 400 BadRequest with the AVAD-not-enabled fingerprint maps to the
            // portable UNSUPPORTED_CAPABILITY category so callers get the same
            // actionable signal the Dynamo / Spanner readers surface for the
            // same operational condition (change-feed not provisioned).
            MulticloudDbException unsupported = maybeAvadNotEnabled(e, "readChanges");
            if (unsupported != null) throw unsupported;
            throw CosmosErrorMapper.map(e, "readChanges");
        }
    }

    /**
     * Map one Cosmos AVAD envelope into a portable {@link ChangeEvent}.
     * <p>
     * The Cosmos AVAD pull-mode response is an outer JSON object of shape:
     * <pre>{@code
     * {
     *   "current":  { ...document fields..., "id": "...", "_ts": ..., "_etag": "..." },
     *   "metadata": { "operationType": "create"|"replace"|"delete", "crts": <seconds>,
     *                 // for DELETE the metadata block additionally carries:
     *                 "id": "...", "partitionKey": { "<pkPathSegment>": "<value>" } },
     *   "previous": { ...document fields... }   // present on replace and (when
     *                                           // still within retention) delete
     * }
     * }</pre>
     * For CREATE / UPDATE the body is taken from {@code current}. For DELETE
     * the body is taken from {@code previous} when present (carries the
     * just-deleted document); if {@code previous} is absent or empty (e.g.,
     * the document was deleted before the change-feed retention had a chance
     * to capture it, or the Cosmos emulator omits {@code previous} for
     * fresh deletes), the key/id pair is recovered from the {@code metadata}
     * block — which always carries them for DELETE in AVAD mode — and the
     * surfaced body is an empty JSON object (Cosmos has no document left
     * to ship). The {@link ChangeEvent#data()} surfaced to the caller is
     * the unwrapped body (never the envelope), so the public contract is
     * identical to Dynamo and Spanner: a portable consumer sees the
     * document fields directly, not the provider's transport shape.
     * <p>
     * If a payload arrives without the expected AVAD envelope (for example,
     * if some future Cosmos behaviour surfaces a bare document), {@code type}
     * falls back to {@link ChangeType#UPDATE} and the whole payload is
     * surfaced as the body so the caller still receives something usable
     * instead of a hard failure.
     */
    private ChangeEvent mapEvent(JsonNode envelope) {
        JsonNode metadata = envelope.path("metadata");
        String op = metadata.path("operationType").asText("").toLowerCase(java.util.Locale.ROOT);

        ChangeType type;
        switch (op) {
            case "create": type = ChangeType.CREATE; break;
            case "delete": type = ChangeType.DELETE; break;
            case "replace":
            case "update":
            default:       type = ChangeType.UPDATE; break;
        }

        // Pick the most informative body:
        //   - DELETE  → previous (the document that was just deleted)
        //   - others  → current
        // For DELETE the Cosmos emulator (and short-retention production
        // accounts) may omit `previous` entirely and emit `current` as an
        // empty object; in that case the surfaced body is empty but the
        // metadata block still carries id + partitionKey, which we recover
        // below.
        JsonNode body = type == ChangeType.DELETE
                ? envelope.path("previous")
                : envelope.path("current");
        if (isEmptyOrMissing(body)) {
            JsonNode alt = type == ChangeType.DELETE
                    ? envelope.path("current")
                    : envelope.path("previous");
            if (!isEmptyOrMissing(alt)) {
                body = alt;
            } else if (body.isMissingNode() || body.isNull()) {
                // Neither side present → fall back to the envelope itself so
                // _ts / _etag lookups below remain safe (will simply return
                // empty / 0, and the metadata-driven fallbacks take over).
                body = alt.isMissingNode() || alt.isNull() ? envelope : alt;
            }
            // else: body is a present-but-empty JSON object (the AVAD
            // DELETE-tombstone case). Keep it as-is — an empty {} is a
            // valid, portable payload for a tombstone — and let the
            // metadata block supply id/pk.
        }

        // ── Identity ─────────────────────────────────────────────────────
        // 1) Prefer fields on the document body (CREATE/UPDATE always; DELETE
        //    when `previous` was carried).
        // 2) Fall back to the AVAD metadata block, which always carries id
        //    and partitionKey for DELETE (`partitionKey` is shipped as a
        //    nested object keyed by the partition-key path segment, e.g.
        //    `{"partitionKey":"<value>"}` for a container with `/partitionKey`,
        //    so we extract the first leaf text value).
        String id = textOrEmpty(body.get("id"));
        if (id.isBlank()) id = textOrEmpty(metadata.get("id"));

        String pk = textOrEmpty(firstPresent(body, "partitionKey", "pk", "_pk"));
        if (pk.isBlank()) pk = extractMetadataPartitionKey(metadata);
        if (pk.isBlank()) pk = id;

        if (pk.isBlank() || id.isBlank()) {
            // Truly key-less envelope (neither body nor metadata carried an
            // identity). Surface as PROVIDER_ERROR so the operator sees a
            // structured failure instead of an opaque IllegalArgumentException
            // from MulticloudDbKey.of(...).
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Cosmos change-feed envelope carries no recoverable id/partitionKey",
                    providerId,
                    "readChanges",
                    false,
                    Map.of("reason", "ENVELOPE_MISSING_KEY",
                            "operationType", op)));
        }
        MulticloudDbKey key = MulticloudDbKey.of(pk, id);

        // Prefer the AVAD metadata's conflict-resolution timestamp (`crts`)
        // which is the authoritative ordering instant for the change record;
        // fall back to `_ts` (second-resolution wall clock) on the body.
        long tsSeconds = metadata.path("crts").asLong(body.path("_ts").asLong(0L));
        Instant commitTs = tsSeconds > 0
                ? Instant.ofEpochSecond(tsSeconds)
                : Instant.now();

        // _etag is a stable per-version identifier; pair with id+ts as a
        // last-resort eventId when the SDK strips it.
        String eventId = textOrEmpty(body.get("_etag"));
        if (eventId.isBlank()) eventId = id + "@" + tsSeconds;

        return new ChangeEvent(key, type, commitTs, body, eventId);
    }

    private static boolean isEmptyOrMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull()
                || (node.isObject() && node.size() == 0);
    }

    /**
     * Extract the partition-key value from an AVAD metadata block.
     * For DELETE events Cosmos wraps the value in a single-entry object whose
     * field name is the partition-key path segment (e.g.
     * {@code {"partitionKey":"abc"}} for a container with path
     * {@code /partitionKey}); this helper unwraps that and also tolerates the
     * less common shape where the value is shipped as a bare string.
     */
    private static String extractMetadataPartitionKey(JsonNode metadata) {
        JsonNode pk = metadata.get("partitionKey");
        if (pk == null || pk.isNull()) return "";
        if (pk.isValueNode()) return pk.asText("");
        if (pk.isObject() && pk.size() >= 1) {
            JsonNode first = pk.fields().next().getValue();
            if (first != null && first.isValueNode()) return first.asText("");
        }
        if (pk.isArray() && pk.size() >= 1 && pk.get(0).isValueNode()) {
            return pk.get(0).asText("");
        }
        return "";
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static JsonNode firstPresent(JsonNode item, String... fields) {
        for (String f : fields) {
            JsonNode n = item.get(f);
            if (n != null && !n.isNull()) return n;
        }
        return null;
    }

    // ── FeedRange wire format ────────────────────────────────────────────────
    // FeedRange's toString() is documented as a stable JSON form that
    // FeedRange.fromString() can decode. We use that as our partitionId.

    private static String encodeRange(FeedRange range) {
        return range.toString();
    }

    private FeedRange decodeRange(String partitionId) {
        try {
            return FeedRange.fromString(partitionId);
        } catch (Exception e) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "Unable to decode Cosmos FeedRange from cursor partitionId: " + partitionId,
                    providerId,
                    "readChanges",
                    false,
                    Map.of(CursorTokenCodec.DETAIL_REASON, CursorTokenCodec.REASON_MALFORMED)), e);
        }
    }
}
