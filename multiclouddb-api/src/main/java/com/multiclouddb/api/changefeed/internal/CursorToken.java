// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Decoded form of the opaque token carried by
 * {@link com.multiclouddb.api.changefeed.ChangeFeedCursor#toToken()}.
 * <p>
 * The token's wire format is Base64URL-encoded JSON; see {@link CursorTokenCodec}
 * for the canonical encoding. This class is the in-memory representation that
 * provider adapters consume.
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@code providerId} — the provider that minted the token. Resuming with
 *       a different provider throws
 *       {@link com.multiclouddb.api.changefeed.CursorExpiredException}.</li>
 *   <li>{@code resource} — the {@link ResourceAddress} the cursor reads from,
 *       or {@code null} for an unhydrated {@code now()} sentinel. Resuming
 *       against a different address throws
 *       {@link com.multiclouddb.api.changefeed.CursorExpiredException}.</li>
 *   <li>{@code issuedAtEpochMillis} — wall-clock instant at which the
 *       bookmark carried by this token is effective. For tokens minted by
 *       {@code listCursors()} this is captured immediately after the provider
 *       returns the continuation (or, on the point-in-time fallback path, the
 *       same instant encoded in the continuation suffix); for tokens returned
 *       by {@code readChanges()} this is captured immediately after the page
 *       is read. Refreshed on every {@code readChanges} → {@code nextCursor()}.
 *       Tokens older than {@link #effectiveRetentionMillis()} fail
 *       client-side as expired.</li>
 *   <li>{@code anchor} — see {@link CursorAnchor}.</li>
 *   <li>{@code partitions} — list of {@link PartitionPosition}; empty for a
 *       {@code now()} sentinel that has not yet been read.</li>
 *   <li>{@code effectiveRetentionMillis} — client-side age cap that decode
 *       applies to this token. Defaults to
 *       {@link CursorTokenCodec#MAX_TOKEN_AGE_MILLIS} (the 24-hour portable
 *       baseline). Provider readers minting a cursor against a client that
 *       opted in to {@code ChangeFeedConfig.extendedRetention(...)} stamp the
 *       opted-in retention here so a persisted token can outlive the 24-hour
 *       baseline up to the server-side retention window. The codec never
 *       accepts a value below the baseline; missing in older tokens is
 *       interpreted as the baseline.</li>
 * </ul>
 *
 * Instances are immutable; {@code partitions} is defensively copied.
 */
public final class CursorToken {

    /** Current token codec version. */
    public static final int VERSION = 1;

    private final ProviderId providerId;
    private final ResourceAddress resource;
    private final long issuedAtEpochMillis;
    private final CursorAnchor anchor;
    private final List<PartitionPosition> partitions;
    private final long effectiveRetentionMillis;

    /**
     * Construct a token using the portable 24-hour baseline as the
     * effective retention. Equivalent to passing
     * {@link CursorTokenCodec#MAX_TOKEN_AGE_MILLIS} to the 6-arg constructor.
     */
    public CursorToken(ProviderId providerId,
                       ResourceAddress resource,
                       long issuedAtEpochMillis,
                       CursorAnchor anchor,
                       List<PartitionPosition> partitions) {
        this(providerId, resource, issuedAtEpochMillis, anchor, partitions,
                CursorTokenCodec.MAX_TOKEN_AGE_MILLIS);
    }

    public CursorToken(ProviderId providerId,
                       ResourceAddress resource,
                       long issuedAtEpochMillis,
                       CursorAnchor anchor,
                       List<PartitionPosition> partitions,
                       long effectiveRetentionMillis) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.resource = resource; // nullable for unhydrated sentinel
        this.issuedAtEpochMillis = issuedAtEpochMillis;
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.partitions = partitions == null
                ? Collections.emptyList()
                : List.copyOf(partitions);
        // Clamp at the baseline so a buggy mint site cannot accidentally
        // shorten the portable floor.
        this.effectiveRetentionMillis = Math.max(
                CursorTokenCodec.MAX_TOKEN_AGE_MILLIS, effectiveRetentionMillis);
    }

    public ProviderId providerId() {
        return providerId;
    }

    /** May be {@code null} for an unhydrated {@code now()} sentinel. */
    public ResourceAddress resource() {
        return resource;
    }

    public long issuedAtEpochMillis() {
        return issuedAtEpochMillis;
    }

    public CursorAnchor anchor() {
        return anchor;
    }

    /** Unmodifiable; never {@code null} (empty list for an unhydrated sentinel). */
    public List<PartitionPosition> partitions() {
        return partitions;
    }

    /**
     * Client-side age cap applied by {@link CursorTokenCodec#decode(String)}.
     * Always {@code >=} {@link CursorTokenCodec#MAX_TOKEN_AGE_MILLIS}; equals
     * the baseline unless this token was minted against a client that opted
     * in to {@code ChangeFeedConfig.extendedRetention(...)}.
     */
    public long effectiveRetentionMillis() {
        return effectiveRetentionMillis;
    }

    /**
     * Return a copy of this token with {@code issuedAtEpochMillis} refreshed
     * to {@code newIssuedAt}, preserving all other fields. Used by providers
     * when minting the {@code nextCursor()} after a successful read.
     */
    public CursorToken withIssuedAt(long newIssuedAt) {
        return new CursorToken(providerId, resource, newIssuedAt, anchor, partitions,
                effectiveRetentionMillis);
    }

    /**
     * Return a copy of this token with the partitions list replaced, anchor
     * set to {@link CursorAnchor#CONTINUING}, and {@code issuedAtEpochMillis}
     * refreshed. Used by providers to mint the {@code nextCursor()} after a
     * read advances or after a split is absorbed.
     */
    public CursorToken withPartitions(List<PartitionPosition> newPartitions, long newIssuedAt) {
        return new CursorToken(providerId, resource, newIssuedAt,
                CursorAnchor.CONTINUING, newPartitions, effectiveRetentionMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CursorToken that)) return false;
        return issuedAtEpochMillis == that.issuedAtEpochMillis
                && effectiveRetentionMillis == that.effectiveRetentionMillis
                && providerId.equals(that.providerId)
                && Objects.equals(resource, that.resource)
                && anchor == that.anchor
                && partitions.equals(that.partitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, resource, issuedAtEpochMillis, anchor, partitions,
                effectiveRetentionMillis);
    }

    @Override
    public String toString() {
        return "CursorToken{v=" + VERSION
                + ", p=" + providerId.id()
                + ", r=" + resource
                + ", i=" + issuedAtEpochMillis
                + ", anchor=" + anchor
                + ", positions=" + partitions.size()
                + ", effectiveRetentionMs=" + effectiveRetentionMillis
                + '}';
    }
}
