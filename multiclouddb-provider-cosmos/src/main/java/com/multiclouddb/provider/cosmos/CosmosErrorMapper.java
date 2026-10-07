// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Azure Cosmos DB exceptions to portable {@link MulticloudDbException}
 * instances. For update operations, surfaced {@code 408} and {@code 410}
 * routing statuses are retryable transient failures; the {@code 410} substatus
 * is preserved in {@code providerDetails}. Retryability permits replay of the
 * logical assignments, but does not imply fixed TTL expiry because another
 * Cosmos patch advances {@code _ts}.
 */
public final class CosmosErrorMapper {

    private static final int STATUS_ENTITY_TOO_LARGE = 413;
    private static final String RESULT_ITEM_SIZE_LIMIT_REASON =
            "cosmos_result_item_size_limit";
    private static final String MAXIMUM_RESULT_BYTES = "2097152";

    private CosmosErrorMapper() {
    }

    public static MulticloudDbException map(CosmosException e, String operation) {
        int httpStatus = e.getStatusCode();
        boolean resultItemSizeLimit = isResultItemSizeLimit(httpStatus, operation);
        boolean transientUpdateFailure = isTransientUpdateFailure(httpStatus, operation);
        MulticloudDbErrorCategory category = resultItemSizeLimit
                ? MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY
                : transientUpdateFailure
                        ? MulticloudDbErrorCategory.TRANSIENT_FAILURE
                        : mapCategory(httpStatus, e.getSubStatusCode());
        boolean retryable = !resultItemSizeLimit
                && (transientUpdateFailure || isRetryable(httpStatus));

        Map<String, String> details = new LinkedHashMap<>();
        details.put("subStatusCode", String.valueOf(e.getSubStatusCode()));
        if (e.getActivityId() != null) {
            details.put("requestId", e.getActivityId());
        }
        details.put("requestCharge", String.valueOf(e.getRequestCharge()));
        if (resultItemSizeLimit) {
            addResultItemSizeLimitDetails(details);
        }

        MulticloudDbError error = new MulticloudDbError(
                category,
                e.getMessage(),
                ProviderId.COSMOS,
                operation,
                retryable,
                httpStatus,
                details);
        return new MulticloudDbException(error, e);
    }

    private static boolean isTransientUpdateFailure(int status, String operation) {
        return OperationNames.UPDATE.equals(operation) && (status == 408 || status == 410);
    }

    private static boolean isResultItemSizeLimit(int status, String operation) {
        return status == STATUS_ENTITY_TOO_LARGE && OperationNames.UPDATE.equals(operation);
    }

    private static void addResultItemSizeLimitDetails(Map<String, String> details) {
        details.put("reason", RESULT_ITEM_SIZE_LIMIT_REASON);
        details.put("maximumResultBytes", MAXIMUM_RESULT_BYTES);
    }

    static MulticloudDbErrorCategory mapCategory(int statusCode, int subStatusCode) {
        return switch (statusCode) {
            case 400 -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case 401 -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
            case 403 -> MulticloudDbErrorCategory.AUTHORIZATION_FAILED;
            case 404 -> MulticloudDbErrorCategory.NOT_FOUND;
            case 409 -> MulticloudDbErrorCategory.CONFLICT;
            case 412 -> MulticloudDbErrorCategory.CONFLICT; // Precondition failed
            case 429 -> MulticloudDbErrorCategory.THROTTLED;
            case 449 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE; // Retry with
            case 500, 502, 503 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
            default -> MulticloudDbErrorCategory.PROVIDER_ERROR;
        };
    }

    static boolean isRetryable(int statusCode) {
        return switch (statusCode) {
            case 429, 449, 500, 502, 503 -> true;
            default -> false;
        };
    }
}
