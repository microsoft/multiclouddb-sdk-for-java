// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.ResourceAddress;

import java.util.Objects;

/**
 * A request for a page of changes from a collection's change feed.
 * <p>
 * Construct via {@link #builder(ResourceAddress)}. All fields except
 * {@code address} have defaults:
 * <ul>
 *   <li>{@code startPosition} → {@link StartPosition#beginning()}</li>
 *   <li>{@code newItemStateMode} → {@link NewItemStateMode#INCLUDE_IF_AVAILABLE}</li>
 *   <li>{@code maxPageSize} → {@code 0} (provider default)</li>
 * </ul>
 *
 * <p>The change feed always reads the entire collection. The SDK fans out
 * across provider-native partitions internally, preserving per-partition
 * order without exposing provider-specific partition semantics.
 *
 * <p>When resuming from a saved checkpoint the {@code startPosition} should
 * be {@link StartPosition#fromContinuationToken(String)}. The token's
 * provider id and resource fingerprint are always validated by
 * {@code ContinuationTokenCodec}. Provider-specific resume metadata is also
 * validated:
 * <ul>
 *   <li><b>Spanner</b> — encodes the internal partition queue.
 *       Legacy tokens that name the removed {@code PhysicalPartition} scope
 *       are rejected with {@code INVALID_REQUEST}.</li>
 *   <li><b>DynamoDB</b> — encodes per-shard cursors and validates them against
 *       the current stream topology.</li>
 *   <li><b>Cosmos</b> — encodes scope metadata in the token. Legacy tokens
 *       that name the removed {@code PhysicalPartition} scope are rejected
 *       with {@code INVALID_REQUEST}.</li>
 * </ul>
 * In all cases mismatches surface as
 * {@link com.multiclouddb.api.MulticloudDbErrorCategory#INVALID_REQUEST}.
 */
public final class ChangeFeedRequest {

    private final ResourceAddress address;
    private final StartPosition startPosition;
    private final NewItemStateMode newItemStateMode;
    private final int maxPageSize;

    private ChangeFeedRequest(Builder b) {
        this.address = Objects.requireNonNull(b.address, "address");
        this.startPosition = b.startPosition != null
                ? b.startPosition : StartPosition.beginning();
        this.newItemStateMode = b.newItemStateMode != null
                ? b.newItemStateMode : NewItemStateMode.INCLUDE_IF_AVAILABLE;
        this.maxPageSize = b.maxPageSize;
    }

    public ResourceAddress address() { return address; }
    public StartPosition startPosition() { return startPosition; }
    public NewItemStateMode newItemStateMode() { return newItemStateMode; }
    /** {@code 0} means provider default. */
    public int maxPageSize() { return maxPageSize; }

    public static Builder builder(ResourceAddress address) {
        return new Builder(address);
    }

    /** Convenience: read the entire collection from the beginning. */
    public static ChangeFeedRequest fromBeginning(ResourceAddress address) {
        return builder(address).build();
    }

    public static final class Builder {
        private final ResourceAddress address;
        private StartPosition startPosition;
        private NewItemStateMode newItemStateMode;
        private int maxPageSize;

        private Builder(ResourceAddress address) {
            this.address = Objects.requireNonNull(address, "address");
        }

        public Builder startPosition(StartPosition startPosition) {
            this.startPosition = startPosition;
            return this;
        }

        public Builder newItemStateMode(NewItemStateMode mode) {
            this.newItemStateMode = mode;
            return this;
        }

        /** {@code 0} means provider default. */
        public Builder maxPageSize(int maxPageSize) {
            if (maxPageSize < 0) {
                throw new IllegalArgumentException("maxPageSize must be >= 0");
            }
            this.maxPageSize = maxPageSize;
            return this;
        }

        public ChangeFeedRequest build() {
            return new ChangeFeedRequest(this);
        }
    }
}
