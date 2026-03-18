// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

/**
 * Exception carrying a structured {@link HyperscaleDbError} with optional
 * diagnostics.
 */
public class HyperscaleDbException extends RuntimeException {

    private final HyperscaleDbError error;
    private OperationDiagnostics diagnostics;

    public HyperscaleDbException(HyperscaleDbError error) {
        super(error.message());
        this.error = error;
    }

    public HyperscaleDbException(HyperscaleDbError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public HyperscaleDbError error() {
        return error;
    }

    public OperationDiagnostics diagnostics() {
        return diagnostics;
    }

    public HyperscaleDbException withDiagnostics(OperationDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        return this;
    }
}
