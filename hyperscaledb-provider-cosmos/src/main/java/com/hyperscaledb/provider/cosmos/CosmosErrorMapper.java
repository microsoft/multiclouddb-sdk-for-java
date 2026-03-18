// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.hyperscaledb.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Azure Cosmos DB exceptions to portable {@link HyperscaleDbException}
 * instances.
 */
public final class CosmosErrorMapper {

    private CosmosErrorMapper() {
    }

    public static HyperscaleDbException map(CosmosException e, String operation) {
        HyperscaleDbErrorCategory category = mapCategory(e.getStatusCode(), e.getSubStatusCode());
        boolean retryable = isRetryable(e.getStatusCode());

        Map<String, String> details = new LinkedHashMap<>();
        details.put("statusCode", String.valueOf(e.getStatusCode()));
        details.put("subStatusCode", String.valueOf(e.getSubStatusCode()));
        if (e.getActivityId() != null) {
            details.put("requestId", e.getActivityId());
        }
        details.put("requestCharge", String.valueOf(e.getRequestCharge()));

        HyperscaleDbError error = new HyperscaleDbError(
                category,
                e.getMessage(),
                ProviderId.COSMOS,
                operation,
                retryable,
                details);
        return new HyperscaleDbException(error, e);
    }

    private static HyperscaleDbErrorCategory mapCategory(int statusCode, int subStatusCode) {
        return switch (statusCode) {
            case 400 -> HyperscaleDbErrorCategory.INVALID_REQUEST;
            case 401 -> HyperscaleDbErrorCategory.AUTHENTICATION_FAILED;
            case 403 -> HyperscaleDbErrorCategory.AUTHORIZATION_FAILED;
            case 404 -> HyperscaleDbErrorCategory.NOT_FOUND;
            case 409 -> HyperscaleDbErrorCategory.CONFLICT;
            case 412 -> HyperscaleDbErrorCategory.CONFLICT; // Precondition failed
            case 429 -> HyperscaleDbErrorCategory.THROTTLED;
            case 449 -> HyperscaleDbErrorCategory.TRANSIENT_FAILURE; // Retry with
            case 500, 502, 503 -> HyperscaleDbErrorCategory.TRANSIENT_FAILURE;
            default -> HyperscaleDbErrorCategory.PROVIDER_ERROR;
        };
    }

    private static boolean isRetryable(int statusCode) {
        return switch (statusCode) {
            case 429, 449, 500, 502, 503 -> true;
            default -> false;
        };
    }
}
