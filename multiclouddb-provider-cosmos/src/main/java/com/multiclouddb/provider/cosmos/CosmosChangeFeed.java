// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Cosmos change-feed adapter — implements {@code readChanges} on top of the
 * Cosmos {@link com.azure.cosmos.CosmosContainer#queryChangeFeed} API.
 * <p>
 * Always requests {@link CosmosChangeFeedRequestOptions#allVersionsAndDeletes()}
 * so the SDK can map distinct CREATE / UPDATE / DELETE events. If the
 * container is not provisioned for that mode, Cosmos returns a 400-class
 * error which we re-raise as
 * {@link MulticloudDbErrorCategory#INVALID_REQUEST} with an actionable
 * message.
 */
final class CosmosChangeFeed {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosChangeFeed.class);
    /**
     * Per-(endpoint, database, collection) cache of the resolved partition-key
     * field name. Including the account endpoint in the key prevents two clients
     * pointed at different accounts but using the same database/container names
     * from sharing each other's PK definitions. The PK definition of a container
     * is immutable after creation, so we read the metadata once and reuse the
     * result across change-feed pages — avoiding a metadata round-trip and RU
     * charge on every readChanges call.
     *
     * <p>Bounded with insertion-order eviction to avoid unbounded growth in
     * multi-tenant apps that rotate through many containers.
     */
    private static final int PK_FIELD_CACHE_MAX = 1024;
    static final java.util.Map<String, String> PK_FIELD_CACHE =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(64, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                            return size() > PK_FIELD_CACHE_MAX;
                        }
                    });

    private final CosmosProviderClient client;

    CosmosChangeFeed(CosmosProviderClient client) {
        this.client = client;
    }

    ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options) {
        Instant start = Instant.now();
        try {
            CosmosContainer container = client.getContainerInternal(request.address());
            String cacheKey = pkFieldCacheKey(client.endpoint(), request.address());
            String pkField = resolveAndCachePkField(cacheKey, container);
            CosmosChangeFeedRequestOptions cfOptions = buildRequestOptions(request);
            cfOptions.allVersionsAndDeletes();
            if (request.maxPageSize() > 0) {
                cfOptions.setMaxItemCount(request.maxPageSize());
            }

            Iterator<FeedResponse<JsonNode>> pages =
                    container.queryChangeFeed(cfOptions, JsonNode.class).iterableByPage().iterator();

            List<ChangeEvent> events = new ArrayList<>();
            String cosmosContinuation = null;
            int statusCode = 304; // Cosmos returns 304 Not Modified when no new events
            int dropped = 0;

            if (pages.hasNext()) {
                FeedResponse<JsonNode> page = pages.next();
                // An empty results list maps to the
                // 304-Not-Modified semantics of the Cosmos change-feed REST
                // contract; an empty page is otherwise indistinguishable
                // from a 200 with zero results.
                statusCode = page.getResults().isEmpty() ? 304 : 200;
                for (JsonNode raw : page.getResults()) {
                    ChangeEvent ev = mapEvent(raw, request, pkField);
                    if (ev != null) {
                        events.add(ev);
                    } else {
                        dropped++;
                    }
                }
                cosmosContinuation = page.getContinuationToken();
            }

            if (dropped > 0) {
                LOG.debug("Cosmos change-feed: dropped {} unparseable record(s) on this page "
                        + "(pkField={}, address={})", dropped, pkField, request.address());
            }

            String token = cosmosContinuation != null
                    ? ContinuationTokenCodec.encode(ProviderId.COSMOS, request.address(),
                            buildCursorEnvelope(cosmosContinuation))
                    : null;

            OperationDiagnostics diag = OperationDiagnostics
                    .builder(ProviderId.COSMOS, "readChanges",
                            Duration.between(start, Instant.now()))
                    .itemCount(events.size())
                    .statusCode(statusCode)
                    .build();
            return new ChangeFeedPage(events, token, diag);
        } catch (CosmosException e) {
            // Cosmos returns 400 for AVAD-not-enabled containers; promote to INVALID_REQUEST
            if (e.getStatusCode() == 400) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Cosmos rejected the change-feed request. The container must be configured "
                                + "with change-feed mode 'AllVersionsAndDeletes' (continuous backup "
                                + "+ container settings) for distinct CREATE/UPDATE/DELETE events. "
                                + "Original message: " + e.getMessage(),
                        ProviderId.COSMOS, "readChanges", false,
                        Map.of("statusCode", String.valueOf(e.getStatusCode()))),
                        e);
            }
            if (e.getStatusCode() == 410 || e.getStatusCode() == 412) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                        "Cosmos change-feed cursor is no longer valid; restart from beginning() or now(). "
                                + "Original message: " + e.getMessage(),
                        ProviderId.COSMOS, "readChanges", false,
                        Map.of("statusCode", String.valueOf(e.getStatusCode()))),
                        e);
            }
            throw CosmosErrorMapper.map(e, "readChanges");
        }
    }

    private CosmosChangeFeedRequestOptions buildRequestOptions(ChangeFeedRequest request) {
        // Resolve the FeedRange from the scope
        FeedRange feedRange = resolveFeedRange(request);

        StartPosition sp = request.startPosition();
        if (sp instanceof StartPosition.Beginning) {
            return CosmosChangeFeedRequestOptions.createForProcessingFromBeginning(feedRange);
        }
        if (sp instanceof StartPosition.Now) {
            return CosmosChangeFeedRequestOptions.createForProcessingFromNow(feedRange);
        }
        if (sp instanceof StartPosition.FromContinuationToken token) {
            String cosmosToken = decodeAndValidateResumeToken(token.token(), request);
            return CosmosChangeFeedRequestOptions.createForProcessingFromContinuation(cosmosToken);
        }
        throw new IllegalStateException("Unhandled StartPosition: " + sp);
    }

    /**
     * Decode the SDK envelope and validate scope on resume. Returns the
     * Cosmos-native cursor string ready to hand to
     * {@link CosmosChangeFeedRequestOptions#createForProcessingFromContinuation(String)}.
     *
     * <p>Package-private so unit tests can exercise the validation paths
     * without standing up a real CosmosContainer.
     */
    static String decodeAndValidateResumeToken(String envelopeToken, ChangeFeedRequest request) {
        JsonNode inner = ContinuationTokenCodec.decode(envelopeToken,
                ProviderId.COSMOS, request.address());
        // Backward-compat: legacy tokens wrapped only the cursor as a
        // bare TextNode. Newer tokens wrap an object
        // {cursor, scope, partitionValue}.
        String cosmosToken;
        String tokScope = "";
        String tokPartitionValue = "";
        boolean envelopeAware = false;
        if (inner.isObject()) {
            cosmosToken = inner.path("cursor").asText("");
            tokScope = inner.path("scope").asText("");
            tokPartitionValue = inner.path("partitionValue").asText("");
            envelopeAware = !tokScope.isEmpty();
        } else {
            cosmosToken = inner.asText("");
        }
        if (cosmosToken == null || cosmosToken.isEmpty()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Continuation token does not contain a Cosmos cursor",
                    ProviderId.COSMOS, "readChanges", false, Map.of()));
        }
        if (envelopeAware) {
            if ("PhysicalPartition".equals(tokScope)) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "PhysicalPartition scope has been removed; restart with a fresh StartPosition.",
                        ProviderId.COSMOS, "readChanges", false, Map.of()));
            }
            String reqScope = scopeKind();
            if (!tokScope.equals(reqScope)) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Continuation token was issued for scope '" + tokScope
                                + "' but the resume request uses scope '" + reqScope
                                + "'. Resume with the original scope or restart "
                                + "from a fresh StartPosition.",
                        ProviderId.COSMOS, "readChanges", false, Map.of()));
            }
        }
        return cosmosToken;
    }

    /** Package-private accessor used by unit tests to build a token envelope. */
    static ObjectNode buildCursorEnvelopeForTest(String cosmosCursor) {
        return buildCursorEnvelope(cosmosCursor);
    }

    private FeedRange resolveFeedRange(ChangeFeedRequest request) {
        return FeedRange.forFullRange();
    }

    /** Token-encoded marker for which scope produced the continuation token. */
    private static final String SCOPE_KIND_ENTIRE = "EntireCollection";

    private static String scopeKind() {
        return SCOPE_KIND_ENTIRE;
    }

    /**
     * Wrap the native Cosmos continuation string in an envelope that also
     * records the scope it was issued for, so a resume that switches scope can
     * be rejected explicitly rather than silently ignored.
     */
    private static ObjectNode buildCursorEnvelope(String cosmosCursor) {
        ObjectNode env = JsonNodeFactory.instance.objectNode();
        env.put("cursor", cosmosCursor);
        env.put("scope", scopeKind());
        env.put("partitionValue", "");
        return env;
    }

    /**
     * Map a single AllVersionsAndDeletes record into a portable
     * {@link ChangeEvent}. Returns {@code null} if the record cannot be parsed
     * (best-effort skip, logged at debug).
     *
     * <p>Package-private static so unit tests can exercise key-resolution
     * without standing up a real CosmosContainer.
     *
     * @param pkField the container's resolved partition-key field name (without
     *                the leading slash). Used to extract the PK from the record
     *                payload — falls back to the SDK convention {@code "partitionKey"}
     *                if the container's PK path could not be resolved.
     */
    static ChangeEvent mapEvent(JsonNode raw, ChangeFeedRequest request, String pkField) {
        if (raw == null || !raw.isObject()) {
            return null;
        }
        JsonNode metadata = raw.path("metadata");
        if (metadata.isMissingNode()) {
            // LatestVersion mode shape — refuse: design requires AVAD.
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Cosmos change-feed record has no 'metadata' field. The container appears to be "
                            + "in LatestVersion mode; switch to AllVersionsAndDeletes for distinct "
                            + "CREATE/UPDATE/DELETE events.",
                    ProviderId.COSMOS, "readChanges", false, Map.of()));
        }
        String op = metadata.path("operationType").asText("");
        ChangeType type;
        switch (op.toLowerCase(java.util.Locale.ROOT)) {
            case "create": type = ChangeType.CREATE; break;
            case "replace":
            case "update": type = ChangeType.UPDATE; break;
            case "delete": type = ChangeType.DELETE; break;
            default:
                LOG.debug("Skipping change-feed record with unknown operationType '{}'", op);
                return null;
        }

        JsonNode current = raw.path("current");
        JsonNode previous = raw.path("previous");
        JsonNode source = current != null && current.isObject() && current.size() > 0
                ? current : previous;
        if (source == null || !source.isObject() || source.size() == 0) {
            // Cannot extract key
            return null;
        }

        String pk = readPkValue(source, pkField);
        // Some AVAD payloads expose the partition key under the system-field
        // alias `_pk` regardless of the user-declared path; consult both.
        if (pk == null || pk.isEmpty()) {
            pk = source.path("_pk").asText(null);
        }
        // Final fallback: if the container's PK path matches `id` (rare but
        // legal — single-field containers using `/id`), the previous lookup
        // already covers it. Otherwise log and drop so silent loss is visible.
        String id = source.path("id").asText(null);
        if (pk == null || pk.isEmpty()) {
            LOG.debug("Cosmos change-feed: dropping record — no value at pkField '{}' "
                    + "(operationType={}, raw fields={})",
                    pkField, op, fieldNames(source));
            return null;
        }
        MulticloudDbKey key = (id != null && !id.equals(pk))
                ? MulticloudDbKey.of(pk, id)
                : MulticloudDbKey.of(pk);

        // eventId — prefer metadata._lsn, fall back to current._lsn / _etag
        String eventId = metadata.path("_lsn").asText(null);
        if (eventId == null || eventId.isEmpty()) {
            eventId = source.path("_lsn").asText(null);
        }
        if (eventId == null || eventId.isEmpty()) {
            eventId = source.path("_etag").asText(null);
        }
        if (eventId == null || eventId.isEmpty()) {
            // Last resort — synthesise from id + ts
            eventId = id + ":" + source.path("_ts").asLong(0);
        }

        Instant ts = null;
        long tsSec = source.path("_ts").asLong(0);
        if (tsSec > 0) {
            ts = Instant.ofEpochSecond(tsSec);
        }

        ObjectNode data = null;
        if (request.newItemStateMode() != NewItemStateMode.OMIT
                && current != null && current.isObject() && current.size() > 0) {
            // Deep-copy so caller mutations don't leak back into the
            // change-feed page; payload is left intact (system fields
            // such as _etag / _ts / _rid / _self are preserved as-is
            // because consumers may rely on them for dedup or auditing).
            data = (ObjectNode) current.deepCopy();
        }
        return new ChangeEvent(ProviderId.COSMOS, eventId, type,
                request.address(), key, data, ts);
    }

    /**
     * Resolve the partition-key path from the container's
     * {@code PartitionKeyDefinition}. The returned value is the path with the
     * leading slash stripped — e.g. {@code "userId"} for {@code "/userId"} or
     * {@code "address/city"} for a hierarchical path {@code "/address/city"}.
     * Callers should walk slash-separated segments when reading the value
     * from the change record (see {@link #readPkValue}).
     *
     * <p>Best-effort: if the metadata read fails for any reason, fall back to
     * the SDK convention ({@link CosmosConstants#FIELD_PARTITION_KEY}) rather
     * than failing the call.
     */
    /**
     * Composite cache key (endpoint + database + collection) so two clients
     * targeting different Cosmos accounts cannot share each other's resolved
     * PK paths even when their database / container names collide.
     */
    static String pkFieldCacheKey(String endpoint, ResourceAddress address) {
        return (endpoint == null ? "" : endpoint) + "|"
                + address.database() + "|" + address.collection();
    }

    /**
     * Resolve the partition-key field for {@code container} and cache it
     * <em>only on success</em>. A transient failure on {@code container.read()}
     * returns the SDK-default fallback for the current call but leaves the
     * cache empty, so a later call can re-attempt the metadata read. Caching
     * the fallback would permanently mis-key events on a container whose real
     * PK path is something other than {@code "partitionKey"}.
     */
    private String resolveAndCachePkField(String cacheKey, CosmosContainer container) {
        String cached = PK_FIELD_CACHE.get(cacheKey);
        if (cached != null) return cached;
        String resolved = resolvePartitionKeyDefinitionStrict(container);
        if (resolved != null) {
            // PK definition is immutable after container creation, so caching
            // the successful value is safe and avoids the metadata round-trip
            // (and RU charge) on every readChanges call.
            PK_FIELD_CACHE.putIfAbsent(cacheKey, resolved);
            return resolved;
        }
        return CosmosConstants.FIELD_PARTITION_KEY;
    }

    /**
     * Read the container's {@code PartitionKeyDefinition} and return the
     * top-level path (with the leading {@code /} stripped). Returns
     * {@code null} when the metadata read fails or the definition is missing /
     * empty — the caller decides what fallback to use without poisoning any
     * cache.
     *
     * <p><b>Limitation:</b> only the first partition-key path is used.
     * Cosmos containers with <em>hierarchical partition keys</em> (multiple
     * paths, e.g. {@code ["/tenantId", "/userId"]}) will key change-feed
     * events by the first level only. This is consistent with
     * {@link com.multiclouddb.api.MulticloudDbKey}, which carries a single
     * {@code partitionKey} string.
     */
    private String resolvePartitionKeyDefinitionStrict(CosmosContainer container) {
        try {
            var props = container.read().getProperties();
            var def = props.getPartitionKeyDefinition();
            if (def != null) {
                java.util.List<String> paths = def.getPaths();
                if (paths != null && !paths.isEmpty()) {
                    String path = paths.get(0);
                    if (path != null && path.startsWith("/")) {
                        String trimmed = path.substring(1);
                        if (!trimmed.isEmpty()) {
                            return trimmed;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            LOG.debug("Could not read PartitionKeyDefinition (falling back to '{}'): {}",
                    CosmosConstants.FIELD_PARTITION_KEY, e.getMessage());
            return null;
        }
    }

    /**
     * Read the partition-key scalar from a change-feed source object,
     * walking slash-separated segments to support hierarchical PK paths
     * (e.g. {@code "address/city"} reads {@code source.address.city}).
     * Returns {@code null} if any intermediate node is missing or the
     * final node is not a scalar.
     */
    static String readPkValue(JsonNode source, String pkPath) {
        if (source == null || pkPath == null || pkPath.isEmpty()) return null;
        JsonNode node = source;
        int from = 0;
        while (from <= pkPath.length()) {
            int slash = pkPath.indexOf('/', from);
            String seg = (slash < 0) ? pkPath.substring(from) : pkPath.substring(from, slash);
            if (seg.isEmpty()) return null;
            node = node.path(seg);
            if (node.isMissingNode() || node.isNull()) return null;
            if (slash < 0) break;
            from = slash + 1;
        }
        return node.isValueNode() ? node.asText(null) : null;
    }

    private static String fieldNames(JsonNode obj) {
        if (obj == null || !obj.isObject()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        java.util.Iterator<String> it = obj.fieldNames();
        while (it.hasNext()) {
            if (!first) sb.append(',');
            sb.append(it.next());
            first = false;
        }
        return sb.append(']').toString();
    }
}
