// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * The result of a {@link MulticloudDbClient#read} operation.
 * <p>
 * Holds the document payload returned by the provider. Provider write-metadata
 * (last modified, version) is not exposed — it is not portable across all
 * providers (DynamoDB does not surface per-item write timestamps, Spanner
 * requires schema-level configuration).
 *
 * @see MulticloudDbClient#read(ResourceAddress, MulticloudDbKey, OperationOptions)
 */
public final class DocumentResult {

    private final ObjectNode document;

    public DocumentResult(ObjectNode document) {
        this.document = Objects.requireNonNull(document, "document must not be null");
    }

    /**
     * The document payload returned by the provider.
     *
     * @return non-null document
     */
    public ObjectNode document() {
        return document;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentResult that)) return false;
        return Objects.equals(document, that.document);
    }

    @Override
    public int hashCode() {
        return Objects.hash(document);
    }

    @Override
    public String toString() {
        return "DocumentResult{document=" + document + "}";
    }
}
