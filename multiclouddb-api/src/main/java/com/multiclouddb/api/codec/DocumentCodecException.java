// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.codec;

import java.util.Objects;

/**
 * A conversion failure, separate from database errors. Diagnostics contain only
 * phase/reason identifiers, never customer values or raw serializer exceptions.
 */
public final class DocumentCodecException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Boundary at which conversion failed. */
    public enum Phase {
        CONSTRUCTION, ENCODE, DECODE
    }

    /** Stable reasons, including optional mapper preparation failures. */
    public enum Reason {
        INVALID_ARGUMENT,
        MAPPER_COPY_FAILED,
        INVALID_ROOT,
        INVALID_STRUCTURE,
        DUPLICATE_FIELD,
        UNSUPPORTED_OUTPUT,
        MAPPING_FAILED
    }

    private final Phase phase;
    private final Reason reason;

    public DocumentCodecException(Phase phase, Reason reason) {
        super(message(phase, reason), null, false, true);
        this.phase = phase;
        this.reason = reason;
    }

    public Phase phase() {
        return phase;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Phase phase, Reason reason) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(reason, "reason");
        return "Document codec failure: " + phase.name() + "/" + reason.name() + ".";
    }
}
