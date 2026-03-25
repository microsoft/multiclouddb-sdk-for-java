package com.hyperscaledb.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyperscaledb.api.HyperscaleDbError;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.OperationNames;

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
 */
public final class DocumentSizeValidator {

    /** Maximum document size in bytes — DynamoDB hard limit (400 KB). */
    public static final int MAX_BYTES = 400 * 1024; // 400 KB

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocumentSizeValidator() {
    }

    /**
     * Validates that the serialized size of {@code document} does not exceed
     * {@link #MAX_BYTES}.
     *
     * @param document  the document to validate
     * @param operation the operation name used for error reporting
     * @throws HyperscaleDbException with category {@link HyperscaleDbErrorCategory#INVALID_REQUEST}
     *                               if the document exceeds the size limit
     */
    public static void validate(JsonNode document, String operation) {
        if (document == null) {
            return;
        }
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(document);
            if (bytes.length > MAX_BYTES) {
                throw new HyperscaleDbException(new HyperscaleDbError(
                        HyperscaleDbErrorCategory.INVALID_REQUEST,
                        "Document size " + bytes.length + " bytes exceeds the maximum of "
                                + MAX_BYTES + " bytes (400 KB). Reduce the document size to "
                                + "maintain portability across all providers.",
                        null,
                        operation,
                        false,
                        null));
            }
        } catch (JsonProcessingException e) {
            throw new HyperscaleDbException(new HyperscaleDbError(
                    HyperscaleDbErrorCategory.INVALID_REQUEST,
                    "Document could not be serialised for size check: " + e.getMessage(),
                    null,
                    operation,
                    false,
                    null));
        }
    }
}
