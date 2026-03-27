// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.time.Duration;

/**
 * Per-operation controls applied to a single CRUD or query call.
 * <p>
 * <h3>Timeout contract</h3>
 * When a timeout is set it is passed to the underlying provider SDK's
 * request-level timeout mechanism. Provider implementations that support
 * timeout enforcement will throw {@link HyperscaleDbException} with category
 * {@link HyperscaleDbErrorCategory#TRANSIENT_FAILURE} if the provider does not
 * respond within the specified duration.
 * <p>
 * <b>Note:</b> timeout enforcement is provider-dependent and may not be
 * available in all providers in the current release. When no timeout is set
 * ({@link #defaults()}) the provider SDK's own default timeout applies.
 */
public final class OperationOptions {

    private static final OperationOptions DEFAULTS = new OperationOptions(null);

    private final Duration timeout;

    private OperationOptions(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Returns the shared default instance with no explicit timeout set.
     * The provider SDK's own default timeout applies.
     */
    public static OperationOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns an {@code OperationOptions} instance with the given hard timeout.
     * The call will fail with {@link HyperscaleDbException} if the provider does
     * not respond within this duration.
     *
     * @param timeout the maximum time to wait; must be positive and non-null
     * @throws IllegalArgumentException if {@code timeout} is null or not positive
     */
    public static OperationOptions withTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be a positive duration");
        }
        return new OperationOptions(timeout);
    }

    /**
     * Returns the caller-specified timeout, or {@code null} if not set
     * (provider default applies).
     */
    public Duration timeout() {
        return timeout;
    }
}
