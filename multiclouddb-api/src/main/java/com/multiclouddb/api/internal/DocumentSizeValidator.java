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
 * The effective limit is 399 KiB (408,576 bytes) of logical JSON, counted
 * structurally without allocating serialized bytes. It retains the legacy byte
 * encoder profile, including numeric normalization and surrogate escaping.
 * <p>
 * The historical 1 KiB margin below DynamoDB's 400 KiB native item limit is
 * retained, but logical JSON is not DynamoDB item accounting or physical storage
 * size. Injected keys/TTL, metadata and native encoding are outside this count.
 * Passing validation does not guarantee native fit or the final size after a
 * provider's existing partial-update operation.
 */
public final class DocumentSizeValidator {

    /** Maximum logical JSON input size in bytes, retaining the historical margin. */
    public static final int MAX_BYTES = 400 * 1024 - 1024; // 399 KiB

    private DocumentSizeValidator() {
    }

    /**
     * Validates that the structural logical JSON size of {@code document} does not exceed
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
