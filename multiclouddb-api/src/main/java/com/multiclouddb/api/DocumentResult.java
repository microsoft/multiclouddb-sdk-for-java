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
 * @see MulticloudDbClient#read(ResourceAddress, MulticloudDbKey, OperationOptions)
 */
public final class DocumentResult {

    private final ObjectNode document;
    private final DocumentMetadata metadata;

    public DocumentResult(ObjectNode document, DocumentMetadata metadata) {
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.metadata = metadata;
    }

    /** Convenience constructor for results without metadata. */
    public DocumentResult(ObjectNode document) {
        this(document, null);
    }

    /**
     * The portable document payload exposed by {@link MulticloudDbClient}. The default
     * client removes adapter-injected identity, TTL, and system-metadata fields after
     * provider mapping; request metadata is exposed separately through {@link #metadata()}
     * when available.
     *
     * @return non-null portable document
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
        return "DocumentResult{document=" + document + ", metadata=" + metadata + "}";
    }
}
