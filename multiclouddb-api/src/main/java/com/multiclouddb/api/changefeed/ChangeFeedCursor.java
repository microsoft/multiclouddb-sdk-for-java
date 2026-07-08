// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.changefeed.internal.CursorAnchor;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.CursorTokenCodec;

import java.util.Collections;
import java.util.Objects;

/**
 * Opaque, immutable handle to one or more provider-side change-feed positions.
 * <p>
 * A cursor is the only stateful concept on the read path. The portable
 * change-feed API exposes three operations on it:
 * <ul>
 *   <li>{@link #now()} — create an unhydrated sentinel that, on its first
 *       {@code readChanges} call, starts at the live tip of the supplied
 *       {@link com.multiclouddb.api.ResourceAddress}.</li>
 *   <li>{@link #fromToken(String)} — resume from a previously persisted token
 *       returned by {@link #toToken()}. Resumption fails with
 *       {@link CursorExpiredException} when the token is older than the v1
 *       portable 24-hour baseline, was minted by a different provider, was
 *       minted for a different resource, or is malformed.</li>
 *   <li>{@link #toToken()} — serialize the cursor's position so it can be
 *       persisted in any user-managed store. The wire format is a Base64URL
 *       JSON string. It is structurally validated but otherwise opaque — do
 *       not parse it.</li>
 * </ul>
 *
 * <h3>Cursor scope</h3>
 * A cursor returned by {@code now()} or by {@code listCursors} initially covers
 * <em>one</em> partition position. After a provider-side split is absorbed
 * transparently inside {@code readChanges}, the same cursor's
 * {@link ChangeFeedPage#nextCursor() nextCursor} may internally track multiple
 * positions. This is invisible to the user — re-call
 * {@link com.multiclouddb.api.MulticloudDbClient#listCursors(
 * com.multiclouddb.api.ResourceAddress) listCursors} when you want to
 * <em>gain</em> parallelism after a split (see the <em>Change Feeds</em>
 * chapter of {@code docs/guide.md}).
 *
 * <h3>Thread safety</h3>
 * Instances are immutable and safe for concurrent reads. Each
 * {@code readChanges} call is single-shot — concurrent calls on the same
 * cursor produce undefined event ordering. Use one cursor per worker thread
 * (the §5 patterns in {@code docs/guide.md}).
 *
 * <h3>What this class deliberately does not expose</h3>
 * <ul>
 *   <li>{@code fromTimestamp(...)} — DynamoDB Streams has no timestamp-seek
 *       primitive; "from beginning" means different things on each provider.
 *       Use {@link #now()} or a persisted token.</li>
 *   <li>Per-partition enumeration — see
 *       {@link com.multiclouddb.api.MulticloudDbClient#listCursors(
 *       com.multiclouddb.api.ResourceAddress) listCursors} instead.</li>
 *   <li>Mutable state — {@code readChanges} returns a new cursor in the
 *       {@link ChangeFeedPage#nextCursor() page}.</li>
 * </ul>
 */
public final class ChangeFeedCursor {

    private final CursorToken token;

    /** SPI constructor — providers construct cursors from a decoded {@link CursorToken}. */
    public ChangeFeedCursor(CursorToken token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    /**
     * Create an unhydrated <em>sentinel</em> cursor for use with any provider.
     * <p>
     * The returned cursor carries no resource binding and no partition
     * positions — the first {@code client.readChanges(addr, cursor)} call
     * hydrates it to the live tip of {@code addr}. No events that occurred
     * before that call are returned.
     * <p>
     * Calling {@link #toToken()} on a {@code now()} sentinel before it has
     * been read returns a token without a resource binding. Persisting that
     * raw sentinel and resuming hours later produces a fresh hydration on the
     * resume target — equivalent to a new {@code now()} call. To <em>resume
     * after work</em>, persist the {@link ChangeFeedPage#nextCursor() nextCursor}
     * returned by {@code readChanges}; that cursor carries the live position.
     */
    public static ChangeFeedCursor now() {
        // Sentinel: no resource, no partitions, anchor=NOW, issued-at=now.
        // ProviderId is set to a non-strict placeholder ("multicloud") which
        // becomes a real provider id when the cursor is first hydrated. Validation
        // of provider-match is deferred until the token carries CONTINUING anchor.
        CursorToken sentinel = new CursorToken(
                SENTINEL_PROVIDER,
                null,
                System.currentTimeMillis(),
                CursorAnchor.NOW,
                Collections.emptyList());
        return new ChangeFeedCursor(sentinel);
    }

    /**
     * Resume from a token previously returned by {@link #toToken()}.
     * <p>
     * Decoding validates the wire format, codec version, and client-side
     * 24-hour age. Provider and resource binding are checked later, inside
     * {@code readChanges(addr, cursor)} when both the cursor and the runtime
     * context are known.
     *
     * @throws CursorExpiredException if the token is malformed, has an
     *         unsupported codec version, or is older than the 24-hour
     *         portable baseline. The
     *         {@link com.multiclouddb.api.MulticloudDbError#providerDetails()
     *         providerDetails["reason"]} field carries one of the constants
     *         defined on
     *         {@link com.multiclouddb.api.changefeed.internal.CursorTokenCodec}.
     */
    public static ChangeFeedCursor fromToken(String token) {
        return new ChangeFeedCursor(CursorTokenCodec.decode(token));
    }

    /**
     * Serialize this cursor for persistence.
     * <p>
     * Tokens are <em>opaque</em> — do not parse them. The SDK guarantees that
     * passing the returned string back to {@link #fromToken(String)} recovers
     * an equivalent cursor (subject to the 24-hour age check).
     */
    public String toToken() {
        return CursorTokenCodec.encode(token);
    }

    /** SPI accessor — providers read the decoded token to drive their reads. */
    public CursorToken token() {
        return token;
    }

    /**
     * {@code true} if this is a {@link #now()} sentinel that has not yet been
     * hydrated by a {@code readChanges} call. Provider implementations use this
     * to bootstrap from the live tip of the supplied address.
     * <p>
     * Detection is purely structural <em>plus</em> a {@link #SENTINEL_PROVIDER}
     * provider-id check, so a hand-crafted token that happens to match the
     * sentinel shape but carries a real provider id (e.g., {@code dynamo})
     * does not bypass {@code DefaultMulticloudDbClient}'s
     * {@code validateProviderMatch} / {@code validateResourceMatch} checks.
     * Without the provider-id gate, a token forged with a foreign provider id
     * would be silently accepted by any runtime client.
     */
    public boolean isUnhydratedSentinel() {
        return token.providerId().equals(SENTINEL_PROVIDER)
                && token.anchor() == CursorAnchor.NOW
                && token.resource() == null
                && token.partitions().isEmpty();
    }

    @Override
    public String toString() {
        return "ChangeFeedCursor{" + token + '}';
    }

    /**
     * Placeholder provider id stamped on a fresh {@link #now()} sentinel. Any
     * concrete client may consume it; the first hydration replaces it with the
     * client's real provider id, after which subsequent
     * {@link #fromToken(String)} calls enforce provider match.
     */
    public static final ProviderId SENTINEL_PROVIDER = ProviderId.register(
            "multicloud", "(unhydrated cursor)");
}
