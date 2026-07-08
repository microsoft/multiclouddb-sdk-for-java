// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import java.util.Objects;

/**
 * A single provider-side partition position carried inside a {@link CursorToken}.
 * <p>
 * A cursor covers one position initially (from {@code now()} or from
 * {@code listCursors}); it may grow to multiple positions after a provider-side
 * split is absorbed transparently inside {@code readChanges}.
 *
 * <h3>Field semantics</h3>
 * <ul>
 *   <li>{@code partitionId} — provider-opaque, stable identifier for the
 *       partition / shard / range / change-stream-partition-token. Stable
 *       string form is required (used in token JSON and as a cursor identity
 *       key for persistence on the user side).</li>
 *   <li>{@code continuation} — provider-specific continuation token recording
 *       the last successfully-read position within the partition. {@code null}
 *       when the cursor has not yet been read (paired with anchor BEGINNING /
 *       NOW), or when the provider has no separate continuation concept beyond
 *       the partition id itself.</li>
 * </ul>
 *
 * {@code partitionId} is validated non-blank. {@code continuation} may be
 * passed as {@code null} or empty; the empty string is normalised to
 * {@code null} on construction (an empty continuation conveys the same
 * "no position yet" semantic as a null one, and round-tripping the two
 * separately would surface as a spurious {@link #equals} difference in
 * cursor-token codecs and persistence layers). Instances are immutable.
 */
public final class PartitionPosition {

    private final String partitionId;
    private final String continuation;

    public PartitionPosition(String partitionId, String continuation) {
        if (partitionId == null || partitionId.isBlank()) {
            throw new IllegalArgumentException("partitionId must be non-blank");
        }
        this.partitionId = partitionId;
        this.continuation = (continuation == null || continuation.isEmpty()) ? null : continuation;
    }

    public String partitionId() {
        return partitionId;
    }

    public String continuation() {
        return continuation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PartitionPosition that)) return false;
        return partitionId.equals(that.partitionId)
                && Objects.equals(continuation, that.continuation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partitionId, continuation);
    }

    @Override
    public String toString() {
        return "PartitionPosition{" + partitionId
                + (continuation != null ? "@" + continuation : "@<unread>")
                + '}';
    }
}
