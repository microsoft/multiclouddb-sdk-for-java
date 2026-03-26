// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.hyperscaledb.api.HyperscaleDbError;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.ProviderId;

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
        int httpStatus = e.getStatusCode();
        HyperscaleDbErrorCategory category = mapCategory(httpStatus, e.getSubStatusCode());
        boolean retryable = isRetryable(httpStatus);

        Map<String, String> details = new LinkedHashMap<>();
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
                httpStatus,
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
