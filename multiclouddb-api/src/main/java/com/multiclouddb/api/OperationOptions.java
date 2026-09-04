// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.time.Duration;

/**
 * Portable operation options: timeout, TTL, and metadata controls.
 * <p>
 * All options are hints and may be honoured on a best-effort basis by provider
 * adapters. Use {@link #builder()} for the full set of options; the static
 * factory methods {@link #defaults()} and {@link #withTimeout(Duration)} are
 * backward-compatible shortcuts.
 */
public final class OperationOptions {

    private static final OperationOptions DEFAULTS = new OperationOptions(null, null, false);

    private final Duration timeout;
    /** TTL in seconds for create/upsert operations only; {@code null} means no TTL (FR-054). {@code update()} rejects a non-null value with INVALID_REQUEST. */
    private final Integer ttlSeconds;
    /** When {@code true}, providers that support {@link Capability#WRITE_TIMESTAMP} return {@link DocumentMetadata} (FR-058). */
    private final boolean includeMetadata;

    private OperationOptions(Duration timeout, Integer ttlSeconds, boolean includeMetadata) {
        this.timeout = timeout;
        this.ttlSeconds = ttlSeconds;
        this.includeMetadata = includeMetadata;
    }

    /** Returns the shared defaults instance (no timeout, no TTL, no metadata). */
    public static OperationOptions defaults() {
        return DEFAULTS;
    }

    /** Backward-compatible shortcut for setting only a timeout. */
    public static OperationOptions withTimeout(Duration timeout) {
        return new OperationOptions(timeout, null, false);
    }

    /** Returns a new {@link Builder} for constructing options with full control. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the caller-specified timeout, or {@code null} if not set (use provider defaults).
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Document TTL in seconds for {@code create()}/{@code upsert()} only, or {@code null} if
     * no TTL. TTL is <strong>not</strong> part of partial update: {@code update()} rejects a
     * non-null {@code ttlSeconds} with {@link MulticloudDbErrorCategory#INVALID_REQUEST}
     * before provider I/O. To set or reset TTL, pass it to a complete {@code create()} or
     * {@code upsert()} document. Providers that do not support {@link Capability#ROW_LEVEL_TTL}
     * ignore this field.
     */
    public Integer ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * When {@code true}, the provider will attempt to populate {@link DocumentResult#metadata()}
     * on read responses. Providers that do not support {@link Capability#WRITE_TIMESTAMP} will
     * return {@code null} metadata regardless.
     */
    public boolean includeMetadata() {
        return includeMetadata;
    }

    public static final class Builder {
        private Duration timeout;
        private Integer ttlSeconds;
        private boolean includeMetadata = false;

        private Builder() {
        }

        /**
         * Sets the operation timeout.
         *
         * @param timeout duration (must be positive)
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets a document TTL for {@code create()}/{@code upsert()} operations only.
         * A non-null TTL is rejected by {@code update()} in shared preflight.
         *
         * @param ttlSeconds time-to-live in seconds (must be >= 1)
         * @return this builder
         */
        public Builder ttlSeconds(int ttlSeconds) {
            if (ttlSeconds < 1) {
                throw new IllegalArgumentException("ttlSeconds must be >= 1");
            }
            this.ttlSeconds = ttlSeconds;
            return this;
        }

        /**
         * Requests write-metadata (last modified timestamp, TTL expiry, version) on read.
         * Providers that do not support {@link Capability#WRITE_TIMESTAMP} return {@code null}
         * metadata regardless.
         *
         * @param includeMetadata whether to request metadata
         * @return this builder
         */
        public Builder includeMetadata(boolean includeMetadata) {
            this.includeMetadata = includeMetadata;
            return this;
        }

        public OperationOptions build() {
            return new OperationOptions(timeout, ttlSeconds, includeMetadata);
        }
    }
}

