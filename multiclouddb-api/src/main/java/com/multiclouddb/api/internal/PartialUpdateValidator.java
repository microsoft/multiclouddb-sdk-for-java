// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationOptions;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared, provider-neutral preflight for the portable partial-update operation
 * ({@code MulticloudDbClient.update(..., fields, ...)}).
 * <p>
 * This validator runs inside {@code DefaultMulticloudDbClient.update()} after the
 * closed-client guard and <em>before</em> {@link DocumentSizeValidator}, the
 * internal {@code PARTIAL_UPDATE} capability gate, and any provider planning or
 * I/O. Every failure below is a non-retryable
 * {@link MulticloudDbErrorCategory#INVALID_REQUEST} that delegates zero provider
 * operations, so all three providers observe identical categories and identical
 * zero-I/O behaviour.
 * <p>
 * Rules enforced, in order, for the {@code fields} map:
 * <ol>
 *   <li>the map is non-null and contains at least one entry;</li>
 *   <li>the map contains at most {@link #MAX_FIELDS} entries;</li>
 *   <li>every name is non-null, has non-zero length, and contains at least one
 *       non-whitespace character and is at most 50,000 UTF-8 bytes;</li>
 *   <li>no name equals, ignoring case, one of the reserved names
 *       ({@code id}, {@code partitionKey}, {@code sortKey}, {@code ttl},
 *       {@code ttlExpiry}, {@code data}), and no name begins with {@code _};</li>
 *   <li>{@link OperationOptions#ttlSeconds()} is null, because TTL is supported
 *       only by {@code create()} and {@code upsert()}.</li>
 * </ol>
 * Accepted names are never trimmed or rewritten. {@code " customer "} is a valid
 * literal (non-blank) name; {@code "   "} is invalid. Names exactly {@code .},
 * {@code /}, and {@code ~} are valid literal top-level names. All case
 * comparisons use {@link Locale#ROOT} so validation is stable across locales.
 */
public final class PartialUpdateValidator {

    /** Portable upper bound that keeps every update to one native write operation. */
    public static final int MAX_FIELDS = WriteLimits.MAX_PARTIAL_UPDATE_FIELDS;

    /** Portable byte bound compatible with the AWS SDK's 50,000-character read limit. */
    public static final int MAX_FIELD_NAME_BYTES = WriteLimits.MAX_FIELD_NAME_UTF8_BYTES;

    public static final String FIELD_COUNT_LIMIT_REASON = "partial_update_field_count_limit";

    public static final String FIELD_NAME_SIZE_LIMIT_REASON =
            "partial_update_field_name_size_limit";

    /**
     * Reserved logical field names that partial update rejects (case-insensitive).
     * These map to provider system columns/attributes (identity, partition/sort
     * key, TTL, and the Spanner {@code FIELD_DATA} metadata column) and must never
     * be assigned through {@code update()}.
     */
    private static final Set<String> RESERVED_LOWER = Set.of(
            "id", "partitionkey", "sortkey", "ttl", "ttlexpiry", "data");

    static boolean isReservedProviderField(String name) {
        return RESERVED_LOWER.contains(name.toLowerCase(Locale.ROOT))
                || !name.isEmpty() && name.charAt(0) == '_';
    }

    private PartialUpdateValidator() {
    }

    /**
     * Validates the {@code fields} map and {@code options} for a partial update.
     *
     * @param fields    the caller-supplied literal top-level fields to set/replace
     * @param options   the per-call options; {@code ttlSeconds} must be null
     * @param operation the operation name used in the error envelope
     * @throws MulticloudDbException category {@link MulticloudDbErrorCategory#INVALID_REQUEST}
     *                               (non-retryable) for any violation
     */
    public static void validate(Map<String, Object> fields, OperationOptions options, String operation) {
        if (fields == null) {
            throw invalid("Partial update requires a non-empty fields map; no fields were supplied.", operation);
        }

        int fieldCount = 0;
        try {
            Iterator<? extends Map.Entry<?, ?>> entries =
                    ((Map<?, ?>) fields).entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                fieldCount++;
                if (fieldCount > MAX_FIELDS) {
                    throw invalid("Partial update accepts at most " + MAX_FIELDS
                            + " fields per call; received at least " + fieldCount + ".",
                            operation, Map.of(
                                    "reason", FIELD_COUNT_LIMIT_REASON,
                                    "maximumFields", String.valueOf(MAX_FIELDS),
                                    "observedFields", String.valueOf(fieldCount)));
                }

                Object rawName = entry.getKey();
                if (rawName == null) {
                    throw invalid("Partial update field names must be non-null.", operation);
                }
                if (!(rawName instanceof String name)) {
                    throw invalid("Partial update field names must be strings.", operation);
                }
                if (name.isEmpty()) {
                    throw invalid("Partial update field names must be non-empty.", operation);
                }
                if (name.isBlank()) {
                    throw invalid("Partial update field name must contain at least one "
                            + "non-whitespace character; blank names are invalid.", operation);
                }
                int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
                if (nameBytes > MAX_FIELD_NAME_BYTES) {
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("reason", FIELD_NAME_SIZE_LIMIT_REASON);
                    details.put("actualFieldNameBytes", String.valueOf(nameBytes));
                    details.put("maximumFieldNameBytes",
                            String.valueOf(MAX_FIELD_NAME_BYTES));
                    throw invalid("Partial update field name is " + nameBytes
                            + " UTF-8 bytes; the portable maximum is "
                            + MAX_FIELD_NAME_BYTES + ".", operation, details);
                }
                if (isReservedProviderField(name)) {
                    throw invalid("Partial update field name '" + name
                            + "' is reserved (case-insensitive identity/TTL/data name or "
                            + "underscore-prefixed provider metadata) and cannot be assigned "
                            + "by update().", operation);
                }
            }
        } catch (MulticloudDbException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid("Partial update fields could not be inspected safely.",
                    operation, Map.of("reason", "portable_value_snapshot_failed"), e);
        }
        if (fieldCount == 0) {
            throw invalid("Partial update requires a non-empty fields map; no fields were supplied.", operation);
        }

        if (options != null && options.ttlSeconds() != null) {
            throw invalid("OperationOptions.ttlSeconds is not valid for update(); TTL is supported only by "
                    + "create() and upsert(). Move a TTL-bearing change to a complete create()/upsert() document.",
                    operation);
        }
    }

    private static MulticloudDbException invalid(String message, String operation) {
        return invalid(message, operation, Map.of());
    }

    private static MulticloudDbException invalid(String message, String operation,
            Map<String, String> details) {
        return invalid(message, operation, details, null);
    }

    private static MulticloudDbException invalid(String message, String operation,
            Map<String, String> details, Throwable cause) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message,
                null,
                operation,
                false,
                details), cause);
    }
}
