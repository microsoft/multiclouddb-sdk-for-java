// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.CursorExpiredException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Encodes/decodes the opaque token wire format used by
 * {@link com.multiclouddb.api.changefeed.ChangeFeedCursor#toToken()} and
 * {@link com.multiclouddb.api.changefeed.ChangeFeedCursor#fromToken(String)}.
 *
 * <h3>Wire format</h3>
 * Base64URL (no padding) of UTF-8 JSON:
 * <pre>{@code
 * {
 *   "v": 1,                       // codec version (see CursorToken.VERSION)
 *   "p": "cosmos",                // provider id (lowercase canonical form)
 *   "r": "database/collection",   // resource binding; omitted for unhydrated now() sentinel
 *   "i": 1735000000000,           // issued-at, millis since epoch (refreshed on every nextCursor)
 *   "a": "CONTINUING",            // anchor: NOW | BEGINNING_OF_RANGE | CONTINUING
 *   "e": 604800000,               // OPTIONAL: effective age cap in millis;
 *                                 //          omitted (or <= 24h) for the baseline.
 *                                 //          Stamped by producers under
 *                                 //          ChangeFeedConfig.extendedRetention(Duration).
 *   "s": [
 *     { "id": "<provider-opaque>", "c": "<continuation-or-null>" },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <h3>Error contract</h3>
 * Every decoding failure raises {@link CursorExpiredException} with a normalized
 * {@link MulticloudDbErrorCategory#CURSOR_EXPIRED} and a structured
 * {@code providerDetails.reason} field — never a raw parser exception:
 * <ul>
 *   <li>{@code MALFORMED} — base64 / JSON / required-field problems.</li>
 *   <li>{@code VERSION_UNSUPPORTED} — token codec version higher than the SDK supports.</li>
 *   <li>{@code TOKEN_AGED_OUT} — issued-at is older than {@link #MAX_TOKEN_AGE_MILLIS}.</li>
 * </ul>
 * Providers may additionally surface {@code PROVIDER_TRIMMED} (events trimmed
 * out from the provider's retention window) or {@code ITERATOR_EXPIRED} (a
 * persisted server-side iterator handle aged out before its next read) from
 * {@code readChanges}; see {@link com.multiclouddb.api.changefeed.CursorExpiredException}.
 * <p>
 * Provider mismatch and resource mismatch are <em>not</em> raised here — they
 * are detected at {@code readChanges(addr, cursor)} time when both the cursor
 * and the runtime context are known. See
 * {@link #validateProviderMatch(CursorToken, ProviderId)} and
 * {@link #validateResourceMatch(CursorToken, ResourceAddress, ProviderId)}.
 *
 * <h3>Concurrency</h3>
 * All methods are stateless and safe for concurrent use.
 */
public final class CursorTokenCodec {

    /** Reason key in {@link MulticloudDbError#providerDetails()}. */
    public static final String DETAIL_REASON = "reason";

    /** Reason values surfaced via {@link MulticloudDbError#providerDetails()}. */
    public static final String REASON_MALFORMED            = "MALFORMED";
    public static final String REASON_VERSION_UNSUPPORTED  = "VERSION_UNSUPPORTED";
    public static final String REASON_TOKEN_AGED_OUT       = "TOKEN_AGED_OUT";
    public static final String REASON_PROVIDER_MISMATCH    = "PROVIDER_MISMATCH";
    public static final String REASON_RESOURCE_MISMATCH    = "RESOURCE_MISMATCH";
    /**
     * Provider-side trim: the provider has dropped the events the cursor was
     * about to read (Cosmos {@code 410 GONE}, Dynamo
     * {@code TrimmedDataAccessException}, Spanner partition outside retention).
     * Recovery: re-bootstrap with
     * {@link com.multiclouddb.api.MulticloudDbClient#listCursors(
     * com.multiclouddb.api.ResourceAddress)} and accept the gap.
     */
    public static final String REASON_PROVIDER_TRIMMED     = "PROVIDER_TRIMMED";
    /**
     * Server-side iterator handle expired. The provider's bookmark addressing
     * the next page has aged out (e.g., DynamoDB Streams' ~5-minute inactivity
     * window on a persisted shard iterator); events at that position may still
     * exist in the stream but the bookmark is no longer addressable. Recovery:
     * re-bootstrap with {@link com.multiclouddb.api.MulticloudDbClient#listCursors(
     * com.multiclouddb.api.ResourceAddress)} from the live tip. Records produced
     * between the expired iterator's position and the new live tip will be
     * skipped — downstream pipelines must be idempotent at the primary-key level.
     */
    public static final String REASON_ITERATOR_EXPIRED     = "ITERATOR_EXPIRED";

    /**
     * Maximum client-side age of a token before {@link #decode(String)} treats
     * it as expired. Matches the v1 portable 24-hour baseline.
     * <p>
     * Note: {@code issuedAt} is refreshed every time a provider returns a
     * {@code nextCursor()}, so the 24h budget applies to time elapsed since the
     * SDK last successfully issued the token — not the original {@code now()}
     * call.
     */
    public static final long MAX_TOKEN_AGE_MILLIS = 24L * 60L * 60L * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private CursorTokenCodec() {
    }

    /**
     * Encode the given token to its Base64URL JSON wire form.
     *
     * @throws IllegalStateException if Jackson serialization fails (should not occur
     *                               with the well-formed types accepted by
     *                               {@link CursorToken})
     */
    public static String encode(CursorToken token) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("v", CursorToken.VERSION);
        node.put("p", token.providerId().id());
        if (token.resource() != null) {
            node.put("r", token.resource().database() + "/" + token.resource().collection());
        }
        node.put("i", token.issuedAtEpochMillis());
        node.put("a", token.anchor().name());

        // Encode the effective age cap ONLY when it exceeds the baseline. A
        // token minted at the 24h baseline omits "e" so the wire form stays
        // bit-for-bit identical to v1 for the common case (no extended
        // retention); an older decoder that does not know "e" falls back to
        // the same baseline. A newer decoder reading an older token (no "e")
        // also lands on the baseline. Producers that opt in to
        // ChangeFeedConfig.extendedRetention(Duration) stamp the opted-in
        // window here so a persisted token can outlive 24h client-side up to
        // the server-side retention.
        if (token.effectiveRetentionMillis() > MAX_TOKEN_AGE_MILLIS) {
            node.put("e", token.effectiveRetentionMillis());
        }

        ArrayNode arr = MAPPER.createArrayNode();
        for (PartitionPosition pos : token.partitions()) {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("id", pos.partitionId());
            if (pos.continuation() != null) {
                p.put("c", pos.continuation());
            }
            arr.add(p);
        }
        node.set("s", arr);

        try {
            byte[] json = MAPPER.writeValueAsBytes(node);
            return B64_ENC.encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode change-feed cursor token", e);
        }
    }

    /**
     * Decode a token string, validate version + age, and return the parsed
     * {@link CursorToken}.
     *
     * @param token Base64URL JSON string previously produced by {@link #encode}
     * @throws CursorExpiredException on any decoding failure or client-side
     *                                age-out (categorised in
     *                                {@link MulticloudDbError#providerDetails()
     *                                providerDetails["reason"]}).
     */
    public static CursorToken decode(String token) {
        return decode(token, System.currentTimeMillis());
    }

    /**
     * Decode with an injected "now" — for testability.
     */
    static CursorToken decode(String token, long nowEpochMillis) {
        if (token == null || token.isBlank()) {
            throw expired(REASON_MALFORMED, "token is null or blank", null);
        }

        byte[] raw;
        try {
            raw = B64_DEC.decode(token);
        } catch (IllegalArgumentException e) {
            throw expired(REASON_MALFORMED, "token is not valid Base64URL", null);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw expired(REASON_MALFORMED, "token payload is not valid JSON", null);
        }
        if (root == null || !root.isObject()) {
            throw expired(REASON_MALFORMED, "token payload is not a JSON object", null);
        }

        int version = root.path("v").asInt(-1);
        if (version < 0) {
            throw expired(REASON_MALFORMED, "token missing required field 'v'", null);
        }
        if (version > CursorToken.VERSION) {
            throw expired(REASON_VERSION_UNSUPPORTED,
                    "token version " + version + " is newer than this SDK supports (max "
                            + CursorToken.VERSION + ")",
                    null);
        }

        String providerId = textRequired(root, "p");
        long issuedAt = root.path("i").asLong(Long.MIN_VALUE);
        if (issuedAt == Long.MIN_VALUE) {
            throw expired(REASON_MALFORMED, "token missing required field 'i'", null);
        }
        String anchorName = textRequired(root, "a");
        CursorAnchor anchor;
        try {
            anchor = CursorAnchor.valueOf(anchorName);
        } catch (IllegalArgumentException e) {
            throw expired(REASON_MALFORMED, "token anchor is not a known value: " + anchorName, null);
        }

        ResourceAddress resource = null;
        if (root.has("r") && !root.get("r").isNull()) {
            String r = root.get("r").asText();
            int slash = r.indexOf('/');
            if (slash <= 0 || slash == r.length() - 1) {
                throw expired(REASON_MALFORMED,
                        "token resource binding 'r' is not in 'database/collection' form: " + r,
                        null);
            }
            try {
                resource = new ResourceAddress(r.substring(0, slash), r.substring(slash + 1));
            } catch (IllegalArgumentException e) {
                throw expired(REASON_MALFORMED, "token resource binding is invalid: " + e.getMessage(), null);
            }
        }

        // Read the per-token effective age cap. Defaults to the baseline when
        // absent so v1 tokens (and tokens minted by callers without the
        // extended-retention opt-in) keep the 24h portable floor. Producers
        // that opted in to ChangeFeedConfig.extendedRetention(Duration) stamp
        // their window here; the CursorToken constructor clamps to the
        // baseline floor so a malformed/tiny value cannot silently shorten
        // the portable guarantee.
        long effectiveRetentionMillis = MAX_TOKEN_AGE_MILLIS;
        if (root.has("e") && !root.get("e").isNull()) {
            JsonNode eNode = root.get("e");
            if (!eNode.isNumber() || !eNode.canConvertToLong()) {
                throw expired(REASON_MALFORMED,
                        "token effective-retention 'e' is not a long: " + eNode, null);
            }
            long encoded = eNode.asLong();
            if (encoded <= 0) {
                throw expired(REASON_MALFORMED,
                        "token effective-retention 'e' must be > 0; was " + encoded, null);
            }
            effectiveRetentionMillis = Math.max(MAX_TOKEN_AGE_MILLIS, encoded);
        }

        List<PartitionPosition> partitions = new ArrayList<>();
        JsonNode arr = root.path("s");
        if (!arr.isArray()) {
            throw expired(REASON_MALFORMED, "token missing or non-array partitions 's'", null);
        }
        for (JsonNode p : arr) {
            if (!p.isObject()) {
                throw expired(REASON_MALFORMED, "partition entry is not a JSON object", null);
            }
            String partId = p.path("id").asText(null);
            if (partId == null || partId.isBlank()) {
                throw expired(REASON_MALFORMED, "partition entry missing 'id'", null);
            }
            String cont = p.has("c") && !p.get("c").isNull() ? p.get("c").asText(null) : null;
            try {
                partitions.add(new PartitionPosition(partId, cont));
            } catch (IllegalArgumentException e) {
                throw expired(REASON_MALFORMED, "invalid partition entry: " + e.getMessage(), null);
            }
        }

        // Client-side age check.
        // Skip for unhydrated now() sentinels (anchor=NOW, no resource binding, no
        // partitions): they have no resumable position to expire — the next read
        // will hydrate them fresh against the runtime's live tip. This matches
        // ChangeFeedCursor.isUnhydratedSentinel() and the Javadoc on now().
        boolean unhydratedSentinel = (anchor == CursorAnchor.NOW)
                && (resource == null)
                && partitions.isEmpty();
        if (!unhydratedSentinel) {
            long ageMillis = nowEpochMillis - issuedAt;
            if (ageMillis > effectiveRetentionMillis) {
                String budgetLabel = effectiveRetentionMillis == MAX_TOKEN_AGE_MILLIS
                        ? "24h portable baseline"
                        : "configured extended-retention window of " + effectiveRetentionMillis + "ms";
                throw expired(REASON_TOKEN_AGED_OUT,
                        "token is older than the " + budgetLabel + " (age=" + ageMillis + "ms)",
                        null);
            }
        }

        ProviderId pid;
        try {
            pid = ProviderId.fromId(providerId);
        } catch (IllegalArgumentException e) {
            throw expired(REASON_MALFORMED, "token provider id is invalid: " + providerId, null);
        }

        return new CursorToken(pid, resource, issuedAt, anchor, partitions, effectiveRetentionMillis);
    }

    /**
     * Validate that the token's provider matches {@code runtimeProvider}, throwing
     * {@link CursorExpiredException} with reason {@code PROVIDER_MISMATCH} otherwise.
     * The thrown error carries {@code runtimeProvider} and
     * {@code operation="readChanges"} for diagnostics — this check is performed at
     * client {@code readChanges(...)} entry, before any provider call, so the
     * runtime provider is always known here.
     */
    public static void validateProviderMatch(CursorToken token, ProviderId runtimeProvider) {
        if (!token.providerId().equals(runtimeProvider)) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "token was minted by provider " + token.providerId().id()
                            + " but the active client uses " + runtimeProvider.id(),
                    runtimeProvider,
                    "readChanges",
                    false,
                    Map.of(DETAIL_REASON, REASON_PROVIDER_MISMATCH)));
        }
    }

    /**
     * Validate that the token's resource binding (if any) matches the supplied
     * {@code addr}, throwing {@link CursorExpiredException} with reason
     * {@code RESOURCE_MISMATCH} on mismatch. Unhydrated sentinel tokens
     * ({@code resource() == null}) are always accepted. The thrown error carries
     * {@code runtimeProvider} and {@code operation="readChanges"} for diagnostics.
     */
    public static void validateResourceMatch(CursorToken token, ResourceAddress addr,
                                             ProviderId runtimeProvider) {
        if (token.resource() == null) return; // unhydrated sentinel — bind on first use
        if (!token.resource().equals(addr)) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "token was minted for resource " + token.resource()
                            + " but the call targets " + addr,
                    runtimeProvider,
                    "readChanges",
                    false,
                    Map.of(DETAIL_REASON, REASON_RESOURCE_MISMATCH)));
        }
    }

    private static String textRequired(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (n.isMissingNode() || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw expired(REASON_MALFORMED, "token missing required field '" + field + "'", null);
        }
        return n.asText();
    }

    /**
     * Build a {@link CursorExpiredException} for a token-decode failure. After
     * the provider/resource-match validators were moved to dedicated
     * constructors that receive the runtime provider, this helper is scoped to
     * the decode path only — {@link #decode(String, long)} is invoked from
     * {@code ChangeFeedCursor.fromToken(...)}, never inside a provider call —
     * so {@code operation} is hard-coded to {@code "fromToken"} and
     * {@code provider} is left {@code null}: token decoding has no notion of
     * a runtime provider until {@code validateProviderMatch(...)} runs.
     */
    private static CursorExpiredException expired(String reason, String message, Throwable cause) {
        MulticloudDbError err = new MulticloudDbError(
                MulticloudDbErrorCategory.CURSOR_EXPIRED,
                message,
                null,
                "fromToken",
                false,
                Map.of(DETAIL_REASON, reason));
        return cause != null ? new CursorExpiredException(err, cause) : new CursorExpiredException(err);
    }
}
