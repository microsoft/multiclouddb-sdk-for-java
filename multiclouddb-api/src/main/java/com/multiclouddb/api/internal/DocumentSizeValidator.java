// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;

import java.util.Map;

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
 * the raw JSON.  The effective validated limit is therefore exactly 408,576 bytes (399 KiB).
 */
public final class DocumentSizeValidator {

    /** Maximum document size in bytes — DynamoDB hard limit minus 1 KB safety margin. */
    public static final int MAX_BYTES = 400 * 1024 - 1024; // 408,576 bytes (399 KiB)

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    public static void validate(JsonNode document, String operation) {
        if (document == null) {
            return;
        }
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(document);
            if (bytes.length > MAX_BYTES) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Document size " + bytes.length + " bytes exceeds the maximum of "
                                + MAX_BYTES + " bytes (399 KiB). Reduce the document size to "
                                + "maintain portability across all providers.",
                        null,
                        operation,
                        false,
                        null));
            }
        } catch (JsonProcessingException e) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Document could not be serialised for size check: " + e.getMessage(),
                    null,
                    operation,
                    false,
                    null));
        }
    }

    /** Overload accepting {@code Map<String, Object>} documents. */
    public static void validate(Map<String, Object> document, String operation) {
        if (document == null) {
            return;
        }
        validate((JsonNode) MAPPER.valueToTree(document), operation);
    }
}
