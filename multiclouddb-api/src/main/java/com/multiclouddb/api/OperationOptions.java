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
    /** TTL hint for create/update/upsert; see {@link #ttlSeconds()} for support and defaults. */
    private final Integer ttlSeconds;
    /** Opt-in read metadata hint; see {@link #includeMetadata()} for provider-specific availability. */
    private final boolean includeMetadata;

    private OperationOptions(Duration timeout, Integer ttlSeconds, boolean includeMetadata) {
        this.timeout = timeout;
        this.ttlSeconds = ttlSeconds;
        this.includeMetadata = includeMetadata;
    }

    /** Returns the shared defaults instance (no timeout or TTL hint, metadata not requested). */
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
     * Document TTL hint in seconds for create/update/upsert operations, or {@code null}
     * when no TTL hint was supplied. A null hint does not guarantee removal of an existing TTL.
     * The current built-in provider without {@link Capability#ROW_LEVEL_TTL} support,
     * Spanner, ignores this field. This does not satisfy FR-057's required unsupported-TTL
     * error; the fail-fast capability gate remains unimplemented follow-up work.
     */
    public Integer ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * Whether to request best-effort {@link DocumentResult#metadata()} on point reads.
     * Defaults to {@code false}; the built-in providers then return null metadata.
     * When requested, only available fields are populated; individual fields may be null,
     * and an empty metadata envelope is possible.
     * <p>
     * Lack of {@link Capability#WRITE_TIMESTAMP} support does not disable all metadata:
     * DynamoDB can expose a stored TTL expiry, while Spanner currently returns an empty
     * envelope. This hint does not require every provider to return an envelope.
     * A read of a missing document still returns a null {@link DocumentResult}.
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
         * Sets a document TTL hint for create/update/upsert operations.
         * Spanner currently ignores this hint because it lacks {@link Capability#ROW_LEVEL_TTL}.
         * The FR-057 unsupported-TTL error gate remains unimplemented; see
         * {@link OperationOptions#ttlSeconds()} for the current implementation gap.
         * Leaving this unset supplies no TTL hint; see {@link OperationOptions#ttlSeconds()}.
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
         * Requests best-effort metadata (last modified timestamp, TTL expiry, version) on
         * point reads. Defaults to {@code false}; the built-in providers then return null
         * metadata. Available fields depend on the provider, and an empty envelope is possible.
         * {@link Capability#WRITE_TIMESTAMP} does not gate all metadata fields.
         * See {@link OperationOptions#includeMetadata()} for provider examples and missing reads.
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
