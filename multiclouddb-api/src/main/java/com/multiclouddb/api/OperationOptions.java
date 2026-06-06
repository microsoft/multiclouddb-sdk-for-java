// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.time.Duration;

/**
 * Portable operation options.
 * <p>
 * Under strict LCD the only portable option is operation timeout. Provider
 * features that are not universally supported (TTL, write metadata) have been
 * removed.
 * <p>
 * All options are hints and may be honoured on a best-effort basis by provider
 * adapters. Use {@link #builder()} for the full set of options; the static
 * factory methods {@link #defaults()} and {@link #withTimeout(Duration)} are
 * backward-compatible shortcuts.
 */
public final class OperationOptions {

    private static final OperationOptions DEFAULTS = new OperationOptions(null);

    private final Duration timeout;

    private OperationOptions(Duration timeout) {
        this.timeout = timeout;
    }

    /** Returns the shared defaults instance (no timeout). */
    public static OperationOptions defaults() {
        return DEFAULTS;
    }

    /** Backward-compatible shortcut for setting only a timeout. */
    public static OperationOptions withTimeout(Duration timeout) {
        return new OperationOptions(timeout);
    }

    /** Returns a new {@link Builder} for constructing options. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the caller-specified timeout, or {@code null} if not set (use provider defaults).
     */
    public Duration timeout() {
        return timeout;
    }

    public static final class Builder {
        private Duration timeout;

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

        public OperationOptions build() {
            return new OperationOptions(timeout);
        }
    }
}
