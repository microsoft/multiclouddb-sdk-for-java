// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Objects;

/**
 * A portable operation result and its diagnostics. Database failures are thrown,
 * not returned in this carrier. A read miss has a null result and a
 * {@link OperationOutcome#NOT_FOUND} outcome; writes have no result body.
 *
 * @param <T> result type; the carrier does not change the result's ownership
 */
public final class OperationResponse<T> {
    private final T result;
    private final OperationDiagnostics diagnostics;

    public OperationResponse(T result, OperationDiagnostics diagnostics) {
        this.result = result;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public T result() { return result; }

    public OperationDiagnostics diagnostics() { return diagnostics; }
}
