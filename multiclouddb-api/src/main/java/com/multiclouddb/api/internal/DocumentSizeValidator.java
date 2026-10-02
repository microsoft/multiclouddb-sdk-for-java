// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates portable write inputs against the serialized and structural limits
 * defined by FR-060 and enforced according to FR-061.
 *
 * <p>Both limits are 390 KiB, leaving headroom below DynamoDB's native 400 KiB
 * item limit for provider-injected key/TTL attributes. Structural validation
 * also enforces the provider-neutral nesting boundary and rejects non-portable
 * binary values before any provider receives a write.</p>
 */
public final class DocumentSizeValidator {

    /** Portable 390 KiB serialized payload and structural-footprint limit. */
    public static final int MAX_BYTES = WriteLimits.MAX_SERIALIZED_INPUT_BYTES;

    /** Maximum Unicode characters in a complete document top-level field name. */
    public static final int MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS =
            WriteLimits.MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS;

    private static final String RESERVED_DOCUMENT_FIELD_REASON =
            "reserved_document_field";
    static final String TOP_LEVEL_FIELD_NAME_LIMIT_REASON =
            "document_top_level_field_name_length_limit";
    static final String CASE_INSENSITIVE_FIELD_COLLISION_REASON =
            "document_case_insensitive_field_name_collision";

    private DocumentSizeValidator() {
    }

    /** Validates a complete JSON document for create/upsert. */
    public static void validate(JsonNode document, String operation) {
        validateDocument(document, operation);
    }

    /** Validates a complete map document for create/upsert. */
    public static void validate(Map<String, Object> document, String operation) {
        validateDocument(document, operation);
    }

    /** Validates and snapshots a complete map document for provider delegation. */
    static Map<String, Object> validateAndSnapshotDocument(
            Map<String, Object> document, String operation) {
        return validateDocument(document, operation);
    }

    /** Validates serialized size and structural rules for partial-update fields. */
    public static void validatePartialUpdate(
            Map<String, Object> fields, String operation) {
        validateAndSnapshotPartialUpdate(fields, operation);
    }

    /** Validates and snapshots partial-update fields for provider delegation. */
    static Map<String, Object> validateAndSnapshotPartialUpdate(
            Map<String, Object> fields, String operation) {
        PartialUpdateValidator.validate(fields, null, operation);
        Map<String, Object> snapshot =
                PartialUpdateStructureValidator.validateAndSnapshotDocument(fields, operation);
        PartialUpdateValidator.validate(snapshot, null, operation);
        return snapshot;
    }

    private static Map<String, Object> validateDocument(Object document, String operation) {
        if (document == null) {
            throw invalidRequest(
                    "Document is required for " + operation + "().",
                    operation,
                    Map.of("reason", "document_required"),
                    null);
        }

        Map<String, Object> snapshot =
                PartialUpdateStructureValidator.validateAndSnapshotDocument(document, operation);
        validateReservedTopLevelFields(snapshot, operation);
        validatePortableTopLevelFieldNames(snapshot, operation);
        return snapshot;
    }

    private static void validateReservedTopLevelFields(
            Map<String, Object> document, String operation) {
        for (String field : document.keySet()) {
            if (PartialUpdateValidator.isReservedProviderField(field)) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", RESERVED_DOCUMENT_FIELD_REASON);
                details.put("field", field);
                throw invalidRequest(
                        "Complete write field name '" + field
                                + "' is reserved for provider identity, TTL, or metadata.",
                        operation, details, null);
            }
        }
    }

    private static void validatePortableTopLevelFieldNames(
            Map<String, Object> document, String operation) {
        Set<String> foldedNames = new HashSet<>();
        for (String field : document.keySet()) {
            int characters = field.codePointCount(0, field.length());
            if (characters > MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", TOP_LEVEL_FIELD_NAME_LIMIT_REASON);
                details.put("actualFieldNameCharacters", String.valueOf(characters));
                details.put("maximumFieldNameCharacters", String.valueOf(
                        MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS));
                throw invalidRequest(
                        "Complete write top-level field name is " + characters
                                + " characters; the portable maximum is "
                                + MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS + ".",
                        operation, details, null);
            }

            String folded = field.toLowerCase(Locale.ROOT);
            if (!foldedNames.add(folded)) {
                throw invalidRequest(
                        "Complete write contains top-level field names that differ only by case; "
                                + "portable complete documents require case-insensitive uniqueness.",
                        operation,
                        Map.of("reason", CASE_INSENSITIVE_FIELD_COLLISION_REASON),
                        null);
            }
        }
    }

    private static MulticloudDbException invalidRequest(
            String message, String operation, Map<String, String> details, Throwable cause) {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message,
                null,
                operation,
                false,
                details);
        return cause == null
                ? new MulticloudDbException(error)
                : new MulticloudDbException(error, cause);
    }
}