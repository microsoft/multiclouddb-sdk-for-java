// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable, provider-neutral structured error carrying error category,
 * human-readable message, retryability hint, and sanitized provider-specific
 * details.
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
    private final Map<String, String> providerDetails;

    public HyperscaleDbError(HyperscaleDbErrorCategory category, String message, ProviderId provider,
            String operation, boolean retryable, Map<String, String> providerDetails) {
        this.category = category;
        this.message = message;
        this.provider = provider;
        this.operation = operation;
        this.retryable = retryable;
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
     * Sanitized provider-specific details (error codes, request IDs) suitable for
     * logging.
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
                + ", operation=" + operation + ", retryable=" + retryable + "}";
    }
}
