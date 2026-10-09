// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.document.Document;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;

/**
 * Validates document payload sizes against the uniform maximum defined by FR-061.
 * <p>
 * The limit is 400 KB (409_600 bytes) — the most restrictive of the three providers:
 * <ul>
 *   <li>Amazon DynamoDB: 400 KB per item (hard limit)</li>
 *   <li>Cosmos DB: 2 MB per document</li>
 *   <li>Cloud Spanner: no per-row limit</li>
 * </ul>
 * By enforcing the lowest common denominator at the SDK layer, documents remain
 * portable across all providers without surprise failures on write.
 * <p>
 * A 1 KB safety margin is subtracted from the raw DynamoDB limit to account for
 * system fields injected by providers before writing ({@code partitionKey},
 * {@code sortKey}, {@code id}, {@code ttlExpiry}, etc.).  DynamoDB's 400 KB cap
 * is measured against its internal wire format, which can be slightly larger than
 * the raw JSON.  The effective validated limit is therefore 399 KB.
 */
public final class DocumentSizeValidator {

    /** Maximum document size in bytes — DynamoDB hard limit minus 1 KB safety margin. */
    public static final int MAX_BYTES = 400 * 1024 - 1024; // 399 KB

    private DocumentSizeValidator() {
    }

    /**
     * Validates that the serialized size of {@code document} does not exceed
     * {@link #MAX_BYTES}.
     *
     * @param document  the document to validate
     * @param operation the operation name used for error reporting
     * @throws MulticloudDbException with category {@link MulticloudDbErrorCategory#INVALID_REQUEST}
     *                               if the document exceeds the size limit
     */
    public static void validate(Document document, String operation) {
        if (document == null) {
            return;
        }
        try {
            DocumentJsonSize.measure(document, MAX_BYTES);
        } catch (DocumentJsonSize.SizeExceeded failure) {
            throw invalid("Document logical JSON size is at least " + failure.observedBytes
                    + " bytes (measurement stopped at the limit; not the full size)."
                    + " Maximum logical JSON size is " + MAX_BYTES + " bytes (399 KB).", operation);
        }
    }

    private static MulticloudDbException invalid(String message, String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST, message, null, operation, false, null));
    }
}
