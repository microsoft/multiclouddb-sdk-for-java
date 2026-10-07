// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps DynamoDB exceptions to portable {@link MulticloudDbException} instances.
 */
public final class DynamoErrorMapper {

    private static final Pattern RESULT_ITEM_SIZE_SUBJECT = Pattern.compile(
            "\\b(?:item\\s+size(?:\\s+to\\s+update)?|maximum\\s+item\\s+size"
                    + "|item\\s+(?:is\\s+)?too\\s+large|item\\s+exceeds?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULT_ITEM_SIZE_LIMIT_SIGNAL = Pattern.compile(
            "\\b(?:exceed(?:ed|s|ing)?|maximum|too\\s+large|limit"
                    + "|400\\s*(?:kb|kib))\\b",
            Pattern.CASE_INSENSITIVE);

    private DynamoErrorMapper() {
    }

    public static MulticloudDbException map(DynamoDbException e, String operation) {
        int httpStatus = e.statusCode();
        boolean resultItemSizeLimit = isResultItemSizeLimit(e, operation);
        boolean requestTimeout = isUpdateRequestTimeout(e, operation);
        MulticloudDbErrorCategory category = resultItemSizeLimit
                ? MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY
                : requestTimeout
                        ? MulticloudDbErrorCategory.TRANSIENT_FAILURE
                        : mapCategory(e);
        boolean retryable = !resultItemSizeLimit && (requestTimeout || isRetryable(e));

        Map<String, String> details = new LinkedHashMap<>();
        if (e.awsErrorDetails() != null) {
            if (e.awsErrorDetails().errorCode() != null) {
                details.put("errorCode", e.awsErrorDetails().errorCode());
            }
            if (e.awsErrorDetails().serviceName() != null) {
                details.put("serviceName", e.awsErrorDetails().serviceName());
            }
        }
        if (e.requestId() != null) {
            details.put("requestId", e.requestId());
        }
        if (resultItemSizeLimit) {
            details.put("reason", "dynamodb_result_item_size_limit");
            details.put("maximumResultBytes", "409600");
        }

        MulticloudDbError error = new MulticloudDbError(
                category,
                e.getMessage(),
                ProviderId.DYNAMO,
                operation,
                retryable,
                httpStatus,
                details);
        return new MulticloudDbException(error, e);
    }

    static MulticloudDbException mapTimeout(SdkClientException e, String operation) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", "dynamodb_request_timeout");
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.TRANSIENT_FAILURE,
                e.getMessage(),
                ProviderId.DYNAMO,
                operation,
                true,
                details);
        return new MulticloudDbException(error, e);
    }

    private static boolean isUpdateRequestTimeout(DynamoDbException e, String operation) {
        if (!OperationNames.UPDATE.equals(operation) || e.awsErrorDetails() == null) {
            return false;
        }
        String errorCode = e.awsErrorDetails().errorCode();
        return "RequestTimeout".equals(errorCode)
                || "RequestTimeoutException".equals(errorCode);
    }

    private static boolean isResultItemSizeLimit(DynamoDbException e, String operation) {
        if (!OperationNames.UPDATE.equals(operation) || e.awsErrorDetails() == null
                || !"ValidationException".equals(e.awsErrorDetails().errorCode())) {
            return false;
        }
        return matchesResultItemSizeMessage(e.getMessage())
                || matchesResultItemSizeMessage(e.awsErrorDetails().errorMessage());
    }

    private static boolean matchesResultItemSizeMessage(String message) {
        return message != null
                && RESULT_ITEM_SIZE_SUBJECT.matcher(message).find()
                && RESULT_ITEM_SIZE_LIMIT_SIGNAL.matcher(message).find();
    }

    private static MulticloudDbErrorCategory mapCategory(DynamoDbException e) {
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
            case "ConditionalCheckFailedException" -> MulticloudDbErrorCategory.CONFLICT;
            case "ResourceNotFoundException" -> MulticloudDbErrorCategory.NOT_FOUND;
            case "ValidationException" -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case "AccessDeniedException" -> MulticloudDbErrorCategory.AUTHORIZATION_FAILED;
            case "UnrecognizedClientException" -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
            case "ProvisionedThroughputExceededException",
                    "ThrottlingException",
                    "RequestLimitExceeded" ->
                MulticloudDbErrorCategory.THROTTLED;
            case "ItemCollectionSizeLimitExceededException" -> MulticloudDbErrorCategory.PERMANENT_FAILURE;
            default -> switch (statusCode) {
                case 400 -> MulticloudDbErrorCategory.INVALID_REQUEST;
                case 401, 403 -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
                case 404 -> MulticloudDbErrorCategory.NOT_FOUND;
                case 500, 502, 503 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
                default -> MulticloudDbErrorCategory.PROVIDER_ERROR;
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
