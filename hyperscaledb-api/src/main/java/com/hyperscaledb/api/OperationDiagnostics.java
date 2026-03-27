// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.time.Duration;

/**
 * Diagnostics metadata for an operation (provider, operation name, duration,
 * request id).
 * Does not contain secrets.
 */
public final class OperationDiagnostics {

    private final ProviderId provider;
    private final String operation;
    private final Duration duration;
    private final String requestId;

    public OperationDiagnostics(ProviderId provider, String operation, Duration duration, String requestId) {
        this.provider = provider;
        this.operation = operation;
        this.duration = duration;
        this.requestId = requestId;
    }

    public ProviderId provider() {
        return provider;
    }

    public String operation() {
        return operation;
    }

    public Duration duration() {
        return duration;
    }

    /**
     * Provider-supplied correlation/request identifier, or null if not available.
     */
    public String requestId() {
        return requestId;
    }

    @Override
    public String toString() {
        return "OperationDiagnostics{provider=" + (provider != null ? provider.id() : "null")
                + ", operation=" + operation + ", duration=" + duration
                + ", requestId=" + requestId + "}";
    }
}
