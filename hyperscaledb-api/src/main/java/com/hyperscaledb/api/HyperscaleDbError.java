// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable, provider-neutral structured error carrying error category,
 * human-readable message, retryability hint, raw HTTP/gRPC status code,
 * and sanitized provider-specific details.
 * <p>
 * All map accessors return <em>unmodifiable</em> views — any attempt to mutate
 * them will throw {@link UnsupportedOperationException}.
 */
public final class HyperscaleDbError {

    private final HyperscaleDbErrorCategory category;
    private final String message;
    private final ProviderId provider;
    private final String operation;
    private final boolean retryable;
    private final Integer statusCode;
    private final Map<String, String> providerDetails;

    public HyperscaleDbError(HyperscaleDbErrorCategory category, String message, ProviderId provider,
            String operation, boolean retryable, Map<String, String> providerDetails) {
        this(category, message, provider, operation, retryable, null, providerDetails);
    }

    public HyperscaleDbError(HyperscaleDbErrorCategory category, String message, ProviderId provider,
            String operation, boolean retryable, Integer statusCode, Map<String, String> providerDetails) {
        this.category = category;
        this.message = message;
        this.provider = provider;
        this.operation = operation;
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.providerDetails = providerDetails != null ? Map.copyOf(providerDetails) : Collections.emptyMap();
    }

    public HyperscaleDbErrorCategory category() {
        return category;
    }

    public String message() {
        return message;
    }

    public ProviderId provider() {
        return provider;
    }

    public String operation() {
        return operation;
    }

    public boolean retryable() {
        return retryable;
    }

    /**
     * The raw HTTP or gRPC status code returned by the provider, or {@code null}
     * if not applicable (e.g. a client-side validation error).
     * <p>
     * This is a portable field — it uses the provider's native numeric code
     * directly (HTTP status for Cosmos DB and DynamoDB, gRPC status code integer
     * for Spanner) so callers can act on it without parsing {@link #providerDetails()}.
     * <p>
     * Common values:
     * <ul>
     *   <li>{@code 404} — resource not found (HTTP providers)</li>
     *   <li>{@code 409} — conflict / duplicate key (HTTP providers)</li>
     *   <li>{@code 429} — rate limited / throttled (HTTP providers)</li>
     *   <li>{@code 5} — NOT_FOUND in gRPC (Spanner)</li>
     *   <li>{@code 6} — ALREADY_EXISTS in gRPC (Spanner)</li>
     * </ul>
     * Use {@link #category()} for provider-neutral error classification; use
     * this field only when you need the raw code for logging or advanced handling.
     */
    public Integer statusCode() {
        return statusCode;
    }

    /**
     * Sanitized provider-specific details (provider error codes, request IDs,
     * request charge, etc.) suitable for logging and diagnostics.
     * <p>
     * Keys vary by provider — use {@link #category()} and {@link #statusCode()}
     * for portable error handling; treat this map as opaque diagnostic data.
     * <p>
     * The returned map is <em>unmodifiable</em>; mutations throw
     * {@link UnsupportedOperationException}.
     */
    public Map<String, String> providerDetails() {
        return providerDetails;
    }

    @Override
    public String toString() {
        return "HyperscaleDbError{category=" + category + ", message=" + message
                + ", provider=" + (provider != null ? provider.id() : "null")
                + ", operation=" + operation + ", retryable=" + retryable
                + ", statusCode=" + statusCode + "}";
    }
}
