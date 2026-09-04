// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationOptions;

import java.util.LinkedHashSet;
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
 *   <li>every name is non-null, has non-zero length, and contains at least one
 *       non-whitespace character;</li>
 *   <li>no name equals, ignoring case, one of the reserved names
 *       ({@code id}, {@code partitionKey}, {@code sortKey}, {@code ttl},
 *       {@code ttlExpiry}, {@code data}), and no name begins with {@code _};</li>
 *   <li>no two names are equal ignoring case (for example {@code foo} and
 *       {@code Foo} collide, because Spanner resolves identifiers
 *       case-insensitively);</li>
 *   <li>{@link OperationOptions#ttlSeconds()} is null, because TTL is supported
 *       only by {@code create()} and {@code upsert()}.</li>
 * </ol>
 * Accepted names are never trimmed or rewritten. {@code " customer "} is a valid
 * literal (non-blank) name; {@code "   "} is invalid. Names exactly {@code .},
 * {@code /}, and {@code ~} are valid literal top-level names. All case
 * comparisons use {@link Locale#ROOT} so validation is stable across locales.
 */
public final class PartialUpdateValidator {

    /**
     * Reserved logical field names that partial update rejects (case-insensitive).
     * These map to provider system columns/attributes (identity, partition/sort
     * key, TTL, and the Spanner {@code FIELD_DATA} metadata column) and must never
     * be assigned through {@code update()}.
     */
    private static final Set<String> RESERVED_LOWER = Set.of(
            "id", "partitionkey", "sortkey", "ttl", "ttlexpiry", "data");

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
        if (fields == null || fields.isEmpty()) {
            throw invalid("Partial update requires a non-empty fields map; no fields were supplied.", operation);
        }

        Set<String> seenLower = new LinkedHashSet<>();
        for (String name : fields.keySet()) {
            if (name == null) {
                throw invalid("Partial update field names must be non-null.", operation);
            }
            if (name.isEmpty()) {
                throw invalid("Partial update field names must be non-empty.", operation);
            }
            if (name.isBlank()) {
                throw invalid("Partial update field name must contain at least one non-whitespace character; "
                        + "blank names are invalid.", operation);
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (RESERVED_LOWER.contains(lower)) {
                throw invalid("Partial update field name '" + name + "' is reserved (case-insensitive match "
                        + "against id/partitionKey/sortKey/ttl/ttlExpiry/data) and cannot be assigned by update().",
                        operation);
            }
            if (name.charAt(0) == '_') {
                throw invalid("Partial update field name '" + name + "' is invalid: names beginning with '_' are "
                        + "reserved for provider metadata.", operation);
            }
            if (!seenLower.add(lower)) {
                throw invalid("Partial update field names must be unique ignoring case; '" + name
                        + "' collides with another supplied name (Spanner resolves identifiers "
                        + "case-insensitively).", operation);
            }
        }

        if (options != null && options.ttlSeconds() != null) {
            throw invalid("OperationOptions.ttlSeconds is not valid for update(); TTL is supported only by "
                    + "create() and upsert(). Move a TTL-bearing change to a complete create()/upsert() document.",
                    operation);
        }
    }

    private static MulticloudDbException invalid(String message, String operation) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message,
                null,
                operation,
                false,
                Map.of()));
    }
}
