// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.fasterxml.jackson.databind.JsonNode;
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
    private final JsonNode data;
    private final String providerEventId;

    /**
     * Construct a change event.
     *
     * @param key             the document key the event applies to (never {@code null})
     * @param type            the {@link ChangeType} (never {@code null})
     * @param commitTimestamp the provider's authoritative ordering timestamp
     *                        (never {@code null})
     * @param data            the new item state if the provider supplied a full image,
     *                        otherwise {@code null}. For DELETE events this is
     *                        typically {@code null}.
     * @param providerEventId a stable provider-side identifier for this event,
     *                        suitable for deduplication; never {@code null}.
     * @throws NullPointerException if any non-nullable argument is {@code null}
     */
    public ChangeEvent(MulticloudDbKey key,
                       ChangeType type,
                       Instant commitTimestamp,
                       JsonNode data,
                       String providerEventId) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.commitTimestamp = Objects.requireNonNull(commitTimestamp, "commitTimestamp");
        this.providerEventId = Objects.requireNonNull(providerEventId, "providerEventId");
        this.data = data;
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
     * New item state, when the provider supplied a full image.
     * {@code null} when the provider does not include a post-image (e.g. DELETE
     * events) or when configuration suppresses images.
     */
    public JsonNode data() {
        return data;
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
                + (data != null ? " (data)" : "")
                + '}';
    }
}
