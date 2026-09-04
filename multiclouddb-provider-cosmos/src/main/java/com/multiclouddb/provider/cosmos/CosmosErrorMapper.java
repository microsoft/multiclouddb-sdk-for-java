// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosBatchOperationResult;
import com.azure.cosmos.models.CosmosBatchResponse;
import com.multiclouddb.api.Capability;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Azure Cosmos DB exceptions and transactional-batch failures to portable
 * {@link MulticloudDbException} instances.
 * <p>
 * Thrown exceptions, selected batch operation results, and the aggregate batch fallback all use
 * one status/substatus normalization policy ({@link #mapCategory} / {@link #isRetryable}).
 * Surfaced CRUD/update {@code 408} and {@code 410} routing statuses are retryable transient
 * failures; the {@code 410} substatus is preserved in {@code providerDetails}. Dependent
 * {@code 424} statuses are never presented as the caller-facing root cause.
 */
public final class CosmosErrorMapper {

    /** HTTP 424 Failed Dependency: a dependent batch rollback status, never a root cause. */
    static final int STATUS_FAILED_DEPENDENCY = 424;
    private static final int STATUS_ENTITY_TOO_LARGE = 413;
    private static final String RESULT_ITEM_SIZE_LIMIT_REASON =
            "cosmos_result_item_size_limit";
    private static final String MAXIMUM_RESULT_BYTES = "2097152";

    private CosmosErrorMapper() {
    }

    public static MulticloudDbException map(CosmosException e, String operation) {
        int httpStatus = e.getStatusCode();
        boolean resultItemSizeLimit = isResultItemSizeLimit(httpStatus, operation);
        MulticloudDbErrorCategory category = resultItemSizeLimit
                ? MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY
                : mapCategory(httpStatus, e.getSubStatusCode());
        boolean retryable = !resultItemSizeLimit && isRetryable(httpStatus);

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

    /**
     * Normalizes a non-success transactional-batch response to a portable error using the
     * root-cause fallback algorithm:
     * <ol>
     *   <li>the first non-success operation result whose status is not 424;</li>
     *   <li>otherwise the aggregate response status when it is not 424;</li>
     *   <li>otherwise a normalized {@code PROVIDER_ERROR} stating that no root operation status
     *       was supplied.</li>
     * </ol>
     * The returned error carries sanitized aggregate/result-count diagnostics but no field
     * values or serialized request body.
     */
    public static MulticloudDbException mapFailedBatch(CosmosBatchResponse response, String operation) {
        if (response.getResults() != null) {
            for (CosmosBatchOperationResult result : response.getResults()) {
                int status = result.getStatusCode();
                if (!result.isSuccessStatusCode() && isRootFailureStatus(status)) {
                    return mapBatchStatus(status, result.getSubStatusCode(), operation, response);
                }
            }
        }
        int aggregate = response.getStatusCode();
        if (isRootFailureStatus(aggregate)) {
            return mapBatchStatus(aggregate, response.getSubStatusCode(), operation, response);
        }
        return providerErrorNoRoot(operation, response);
    }

    private static boolean isRootFailureStatus(int status) {
        return status >= 400 && status <= 599 && status != STATUS_FAILED_DEPENDENCY;
    }

    /**
     * Maps a selected batch status/substatus to a portable error, attaching sanitized batch
     * diagnostics (aggregate status/substatus, activity id, request charge, result count) but no
     * item bodies.
     */
    static MulticloudDbException mapBatchStatus(int status, int subStatus, String operation,
            CosmosBatchResponse response) {
        boolean resultItemSizeLimit = isResultItemSizeLimit(status, operation);
        MulticloudDbErrorCategory category = resultItemSizeLimit
                ? MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY
                : mapCategory(status, subStatus);
        boolean retryable = !resultItemSizeLimit && isRetryable(status);
        Map<String, String> details = batchDiagnostics(response);
        details.put("subStatusCode", String.valueOf(subStatus));
        if (resultItemSizeLimit) {
            addResultItemSizeLimitDetails(details);
        }
        MulticloudDbError error = new MulticloudDbError(
                category,
                "Cosmos transactional batch operation failed with status " + status
                        + " (substatus " + subStatus + ") during " + operation,
                ProviderId.COSMOS, operation, retryable, status, details);
        return new MulticloudDbException(error);
    }

    private static boolean isResultItemSizeLimit(int status, String operation) {
        return status == STATUS_ENTITY_TOO_LARGE && OperationNames.UPDATE.equals(operation);
    }

    private static void addResultItemSizeLimitDetails(Map<String, String> details) {
        details.put("capability", Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
        details.put("reason", RESULT_ITEM_SIZE_LIMIT_REASON);
        details.put("maximumResultBytes", MAXIMUM_RESULT_BYTES);
    }

    private static MulticloudDbException providerErrorNoRoot(String operation, CosmosBatchResponse response) {
        Map<String, String> details = batchDiagnostics(response);
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "Cosmos batch failed but supplied no root operation status",
                ProviderId.COSMOS, operation, false, null, details);
        return new MulticloudDbException(error);
    }

    /** Sanitized batch diagnostics: aggregate status/substatus, activity id, request charge, result count. */
    private static Map<String, String> batchDiagnostics(CosmosBatchResponse response) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("aggregateStatusCode", String.valueOf(response.getStatusCode()));
        details.put("aggregateSubStatusCode", String.valueOf(response.getSubStatusCode()));
        if (response.getActivityId() != null) {
            details.put("requestId", response.getActivityId());
        }
        details.put("requestCharge", String.valueOf(response.getRequestCharge()));
        details.put("resultCount", String.valueOf(response.getResults() != null ? response.getResults().size() : 0));
        return details;
    }

    static MulticloudDbErrorCategory mapCategory(int statusCode, int subStatusCode) {
        return switch (statusCode) {
            case 400 -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case 401 -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
            case 403 -> MulticloudDbErrorCategory.AUTHORIZATION_FAILED;
            case 404 -> MulticloudDbErrorCategory.NOT_FOUND;
            case 408 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE; // request timeout
            case 409 -> MulticloudDbErrorCategory.CONFLICT;
            case 410 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE; // CRUD/update routing GONE (e.g. partition split)
            case 412 -> MulticloudDbErrorCategory.CONFLICT; // Precondition failed
            case 429 -> MulticloudDbErrorCategory.THROTTLED;
            case 449 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE; // Retry with
            case 500, 502, 503 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
            default -> MulticloudDbErrorCategory.PROVIDER_ERROR;
        };
    }

    static boolean isRetryable(int statusCode) {
        return switch (statusCode) {
            case 408, 410, 429, 449, 500, 502, 503 -> true;
            default -> false;
        };
    }
}
