// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.Map;

/**
 * Provider-neutral structured error with retryability and sanitized provider
 * details.
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
     * Sanitized provider-specific details (error codes, request ids) suitable for
     * logging.
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
