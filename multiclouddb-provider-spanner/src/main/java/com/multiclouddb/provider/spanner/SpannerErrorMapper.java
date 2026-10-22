// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Google Cloud Spanner exceptions to portable {@link MulticloudDbException}
 * instances.
 */
public final class SpannerErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerErrorMapper.class);

    private SpannerErrorMapper() {
    }

    public static MulticloudDbException map(SpannerException e, String operation) {
        ErrorCode errorCode = e.getErrorCode();
        MulticloudDbErrorCategory category = mapCategory(errorCode);
        boolean retryable = e.isRetryable();

        Map<String, String> details = new LinkedHashMap<>();
        details.put("grpcStatus", errorCode.name());   // human-readable name, e.g. "NOT_FOUND"
        details.put("errorMessage", e.getMessage());

        MulticloudDbError error = new MulticloudDbError(
                category,
                e.getMessage(),
                ProviderId.SPANNER,
                operation,
                retryable,
                grpcCode(errorCode),            // numeric gRPC status code
                details);
        return new MulticloudDbException(error, e);
    }

    public static MulticloudDbException map(Exception e, String operation) {
        if (e instanceof SpannerException se) {
            return map(se, operation);
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("errorMessage", e.getMessage());

        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                e.getMessage(),
                ProviderId.SPANNER,
                operation,
                false,
                details);
        return new MulticloudDbException(error, e);
    }

    private static MulticloudDbErrorCategory mapCategory(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case NOT_FOUND -> MulticloudDbErrorCategory.NOT_FOUND;
            case ALREADY_EXISTS -> MulticloudDbErrorCategory.CONFLICT;
            case PERMISSION_DENIED -> MulticloudDbErrorCategory.AUTHORIZATION_FAILED;
            case RESOURCE_EXHAUSTED -> MulticloudDbErrorCategory.THROTTLED;
            case FAILED_PRECONDITION -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case ABORTED -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
            case UNIMPLEMENTED -> MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY;
            case INTERNAL -> MulticloudDbErrorCategory.PROVIDER_ERROR;
            case UNAVAILABLE -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
            case UNAUTHENTICATED -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
            default -> {
                LOG.warn("Unmapped Spanner ErrorCode for category mapping: {}", errorCode);
                yield MulticloudDbErrorCategory.PROVIDER_ERROR;
            }
        };
    }

    /**
     * Maps a Spanner {@link ErrorCode} to its canonical gRPC status code integer.
     * The mapping is fixed by the gRPC specification and will not change.
     * gRPC status codes: https://grpc.github.io/grpc/core/md_doc_statuscodes.html
     */
    private static int grpcCode(ErrorCode errorCode) {
        return switch (errorCode) {
            case CANCELLED -> 1;
            case UNKNOWN -> 2;
            case INVALID_ARGUMENT -> 3;
            case DEADLINE_EXCEEDED -> 4;
            case NOT_FOUND -> 5;
            case ALREADY_EXISTS -> 6;
            case PERMISSION_DENIED -> 7;
            case RESOURCE_EXHAUSTED -> 8;
            case FAILED_PRECONDITION -> 9;
            case ABORTED -> 10;
            case OUT_OF_RANGE -> 11;
            case UNIMPLEMENTED -> 12;
            case INTERNAL -> 13;
            case UNAVAILABLE -> 14;
            case DATA_LOSS -> 15;
            case UNAUTHENTICATED -> 16;
            default -> {
                LOG.warn("Unmapped gRPC ErrorCode: {}; defaulting to UNKNOWN (2)", errorCode);
                yield 2; // UNKNOWN
            }
        };
    }
}
