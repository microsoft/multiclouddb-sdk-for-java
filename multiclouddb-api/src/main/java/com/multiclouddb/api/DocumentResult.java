// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * The result of a {@link MulticloudDbClient#read} operation, combining the
 * document payload with optional provider write-metadata.
 * <p>
 * Usage:
 * <pre>{@code
 * DocumentResult result = client.read(address, key);
 * ObjectNode doc = result.document();
 * DocumentMetadata meta = result.metadata(); // may be null if includeMetadata=false
 * }</pre>
 *
 * @see MulticloudDbClient#read(ResourceAddress, Key, OperationOptions)
 */
public final class DocumentResult {

    private final ObjectNode document;
    private final DocumentMetadata metadata;
    private final OperationDiagnostics diagnostics;

    public DocumentResult(ObjectNode document, DocumentMetadata metadata, OperationDiagnostics diagnostics) {
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.metadata = metadata;
        this.diagnostics = diagnostics;
    }

    /** Convenience constructor for results without metadata. */
    public DocumentResult(ObjectNode document) {
        this(document, null, null);
    }

    /** Convenience constructor for results without diagnostics. */
    public DocumentResult(ObjectNode document, DocumentMetadata metadata) {
        this(document, metadata, null);
    }

    /**
     * The document payload returned by the provider.
     *
     * @return non-null document
     */
    public ObjectNode document() {
        return document;
    }

    /**
     * Provider write-metadata, or {@code null} if
     * {@link OperationOptions#includeMetadata()} was {@code false} (the default).
     */
    public DocumentMetadata metadata() {
        return metadata;
    }

    /** Read-operation diagnostics, or {@code null} if unavailable. */
    public OperationDiagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentResult that)) return false;
        return Objects.equals(document, that.document)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(document, metadata);
    }

    @Override
    public String toString() {
        return "DocumentResult{document=" + document
                + ", metadata=" + metadata
                + ", diagnostics=" + diagnostics + "}";
    }
}
