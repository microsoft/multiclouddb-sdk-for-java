// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.time.Duration;

/**
 * Portable operation options: timeout and cancellation controls.
 * All options are hints and may be honored on a best-effort basis by provider
 * adapters.
 */
public final class OperationOptions {

    private static final OperationOptions DEFAULTS = new OperationOptions(null);

    private final Duration timeout;

    private OperationOptions(Duration timeout) {
        this.timeout = timeout;
    }

    public static OperationOptions defaults() {
        return DEFAULTS;
    }

    public static OperationOptions withTimeout(Duration timeout) {
        return new OperationOptions(timeout);
    }

    /**
     * Returns the caller-specified timeout, or null if not set (use provider
     * defaults).
     */
    public Duration timeout() {
        return timeout;
    }
}
