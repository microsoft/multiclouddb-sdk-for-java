// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Map;
import java.util.Objects;

/**
 * The result of a {@link MulticloudDbClient#read} operation, combining the
 * document payload with optional provider write-metadata.
 * <p>
 * Usage:
 * <pre>{@code
 * DocumentResult result = client.read(address, key);
 * Map<String, Object> doc = result.document();
 * DocumentMetadata meta = result.metadata(); // may be null if includeMetadata=false
 * }</pre>
 *
 * @see MulticloudDbClient#read(ResourceAddress, MulticloudDbKey, OperationOptions)
 */
public final class DocumentResult {

    private final Map<String, Object> document;
    private final DocumentMetadata metadata;

    /**
     * Constructs a result, retaining the supplied payload.
     * Providers supply detached mutable Maps/Lists, not views of retained native state.
     *
     * @param document non-null neutral document payload
     * @param metadata optional provider metadata
     */
    public DocumentResult(Map<String, Object> document, DocumentMetadata metadata) {
        this.document = Objects.requireNonNull(document, "document must not be null");
        this.metadata = metadata;
    }

    /** Convenience constructor for results without metadata. */
    public DocumentResult(Map<String, Object> document) {
        this(document, null);
    }

    /**
     * The mutable document payload returned by the provider, detached from
     * retained native responses. Missing keys differ from present null values.
     *
     * @return non-null document
     */
    public Map<String, Object> document() {
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
