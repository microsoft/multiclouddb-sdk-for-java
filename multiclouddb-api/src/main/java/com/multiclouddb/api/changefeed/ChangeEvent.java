// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.MulticloudDbKey;

import java.time.Instant;
import java.util.Objects;

/**
 * A single row-level change event surfaced by the portable change feed.
 * <p>
 * Equality is based on {@link #providerEventId()} within a given
 * {@code (provider, resource)} scope; the value class itself does not implement
 * {@code equals}/{@code hashCode}, so callers wanting deduplication should key
 * on a tuple of {@code (providerId, providerEventId)}.
 *
 * <h3>Timestamp semantics</h3>
 * {@link #commitTimestamp()} is the provider's <em>authoritative ordering
 * timestamp</em>, not necessarily a true commit timestamp:
 * <ul>
 *   <li><b>Cosmos DB</b> — surfaces {@code _ts} (1-second resolution).</li>
 *   <li><b>DynamoDB</b> — surfaces {@code ApproximateCreationDateTime}
 *       (sub-second, approximate).</li>
 *   <li><b>Spanner</b> — surfaces the true commit timestamp.</li>
 * </ul>
 * Ordering is guaranteed within a single partition; cross-partition ordering
 * is not portably defined.
 */
public final class ChangeEvent {

    private final MulticloudDbKey key;
    private final ChangeType type;
    private final Instant commitTimestamp;
    private final Object data;
    private final boolean hasData;
    private final String providerEventId;

    /**
     * Construct a change event.
     *
     * @param key             the document key the event applies to (never {@code null})
     * @param type            the {@link ChangeType} (never {@code null})
     * @param commitTimestamp the provider's authoritative ordering timestamp
     *                        (never {@code null})
     * @param data            the provider-selected payload, possibly partial or a
     *                        before-image; {@code null} if no payload was supplied.
     *                        Use the explicit-presence constructor for present-null.
     * @param providerEventId a stable provider-side identifier for this event,
     *                        suitable for deduplication; never {@code null}.
     * @throws NullPointerException if any non-nullable argument is {@code null}
     */
    public ChangeEvent(MulticloudDbKey key,
                       ChangeType type,
                       Instant commitTimestamp,
                       Object data,
                       String providerEventId) {
        this(key, type, commitTimestamp, data, providerEventId, data != null);
    }

    /**
     * Constructs an event with explicit payload presence.
     *
     * @param key non-null document key
     * @param type non-null change type
     * @param commitTimestamp non-null provider ordering timestamp
     * @param data neutral Map/List/scalar/null payload; must be null when absent
     * @param providerEventId non-null provider event identifier
     * @param hasData whether a payload was supplied, including explicit null
     * @throws IllegalArgumentException if absent data is non-null
     * @throws NullPointerException if a required argument is null
     */
    public ChangeEvent(MulticloudDbKey key,
                       ChangeType type,
                       Instant commitTimestamp,
                       Object data,
                       String providerEventId,
                       boolean hasData) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.commitTimestamp = Objects.requireNonNull(commitTimestamp, "commitTimestamp");
        this.providerEventId = Objects.requireNonNull(providerEventId, "providerEventId");
        if (!hasData && data != null) {
            throw new IllegalArgumentException("Absent change data must be null.");
        }
        this.data = data;
        this.hasData = hasData;
    }

    /** The document key the event applies to. */
    public MulticloudDbKey key() {
        return key;
    }

    /** The kind of mutation that produced the event. */
    public ChangeType type() {
        return type;
    }

    /**
     * Provider's authoritative ordering timestamp. See class Javadoc for
     * per-provider semantics.
     */
    public Instant commitTimestamp() {
        return commitTimestamp;
    }

    /**
     * Provider-selected neutral payload. Maps and Lists are mutable and detached
     * from retained native responses. Use {@link #hasData()} to distinguish
     * absent data from an explicit null payload.
     * This is not a completeness or post-image guarantee. Image selection and
     * metadata visibility retain the provider's existing behavior.
     */
    public Object data() {
        return data;
    }

    /** Whether a payload was supplied, including explicit null or an empty object. */
    public boolean hasData() {
        return hasData;
    }

    /**
     * A stable provider-side identifier for this event, suitable for downstream
     * idempotency. Uniqueness scope is {@code (providerId, resourceAddress,
     * providerEventId)} — the same identifier may legitimately appear on different
     * providers or different resources.
     */
    public String providerEventId() {
        return providerEventId;
    }

    @Override
    public String toString() {
        return "ChangeEvent{" + type + " " + key
                + " @" + commitTimestamp
                + " id=" + providerEventId
                + (hasData ? " (data)" : "")
                + '}';
    }
}
