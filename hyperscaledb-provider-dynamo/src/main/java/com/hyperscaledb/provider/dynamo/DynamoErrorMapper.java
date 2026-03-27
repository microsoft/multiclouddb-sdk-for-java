// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.HyperscaleDbError;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.ProviderId;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps DynamoDB exceptions to portable {@link HyperscaleDbException} instances.
 */
public final class DynamoErrorMapper {

    private DynamoErrorMapper() {
    }

    public static HyperscaleDbException map(DynamoDbException e, String operation) {
        int httpStatus = e.statusCode();
        HyperscaleDbErrorCategory category = mapCategory(e);
        boolean retryable = isRetryable(e);

        Map<String, String> details = new LinkedHashMap<>();
        if (e.awsErrorDetails() != null) {
            details.put("errorCode", e.awsErrorDetails().errorCode());
            details.put("serviceName", e.awsErrorDetails().serviceName());
        }
        if (e.requestId() != null) {
            details.put("requestId", e.requestId());
        }

        HyperscaleDbError error = new HyperscaleDbError(
                category,
                e.getMessage(),
                ProviderId.DYNAMO,
                operation,
                retryable,
                httpStatus,
                details);
        return new HyperscaleDbException(error, e);
    }

    private static HyperscaleDbErrorCategory mapCategory(DynamoDbException e) {
        int statusCode = e.statusCode();
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "";

        // Map by error code first for precision
        return switch (errorCode) {
            // ConditionalCheckFailedException has two semantics depending on which operation raised it:
            //   CREATE  → attribute_not_exists() guard failed → item already exists → HTTP 409 equivalent → CONFLICT
            //   UPDATE  → condition expression on an existing item failed → HTTP 412 equivalent → CONFLICT
            // Both map to CONFLICT today because the portable API does not expose ETag-based conditional
            // updates; if/when that is added, the UPDATE path should return a dedicated PRECONDITION_FAILED
            // category. The operation parameter is preserved here to make that split straightforward.
            case "ConditionalCheckFailedException" -> HyperscaleDbErrorCategory.CONFLICT;
            case "ResourceNotFoundException" -> HyperscaleDbErrorCategory.NOT_FOUND;
            case "ValidationException" -> HyperscaleDbErrorCategory.INVALID_REQUEST;
            case "AccessDeniedException" -> HyperscaleDbErrorCategory.AUTHORIZATION_FAILED;
            case "UnrecognizedClientException" -> HyperscaleDbErrorCategory.AUTHENTICATION_FAILED;
            case "ProvisionedThroughputExceededException",
                    "ThrottlingException",
                    "RequestLimitExceeded" ->
                HyperscaleDbErrorCategory.THROTTLED;
            case "ItemCollectionSizeLimitExceededException" -> HyperscaleDbErrorCategory.PERMANENT_FAILURE;
            default -> switch (statusCode) {
                case 400 -> HyperscaleDbErrorCategory.INVALID_REQUEST;
                case 401, 403 -> HyperscaleDbErrorCategory.AUTHENTICATION_FAILED;
                case 404 -> HyperscaleDbErrorCategory.NOT_FOUND;
                case 500, 502, 503 -> HyperscaleDbErrorCategory.TRANSIENT_FAILURE;
                default -> HyperscaleDbErrorCategory.PROVIDER_ERROR;
            };
        };
    }

    private static boolean isRetryable(DynamoDbException e) {
        if (e.isThrottlingException()) {
            return true;
        }
        int statusCode = e.statusCode();
        return statusCode >= 500 && statusCode < 600;
    }
}
