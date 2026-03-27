// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.HyperscaleDbError;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.ProviderId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Google Cloud Spanner exceptions to portable {@link HyperscaleDbException}
 * instances.
 */
public final class SpannerErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerErrorMapper.class);

    private SpannerErrorMapper() {
    }

    public static HyperscaleDbException map(SpannerException e, String operation) {
        ErrorCode errorCode = e.getErrorCode();
        HyperscaleDbErrorCategory category = mapCategory(errorCode);
        boolean retryable = e.isRetryable();

        Map<String, String> details = new LinkedHashMap<>();
        details.put("grpcStatus", errorCode.name());   // human-readable name, e.g. "NOT_FOUND"
        details.put("errorMessage", e.getMessage());

        HyperscaleDbError error = new HyperscaleDbError(
                category,
                e.getMessage(),
                ProviderId.SPANNER,
                operation,
                retryable,
                grpcCode(errorCode),            // numeric gRPC status code
                details);
        return new HyperscaleDbException(error, e);
    }

    public static HyperscaleDbException map(Exception e, String operation) {
        if (e instanceof SpannerException se) {
            return map(se, operation);
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("errorMessage", e.getMessage());

        HyperscaleDbError error = new HyperscaleDbError(
                HyperscaleDbErrorCategory.PROVIDER_ERROR,
                e.getMessage(),
                ProviderId.SPANNER,
                operation,
                false,
                details);
        return new HyperscaleDbException(error, e);
    }

    private static HyperscaleDbErrorCategory mapCategory(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_ARGUMENT -> HyperscaleDbErrorCategory.INVALID_REQUEST;
            case NOT_FOUND -> HyperscaleDbErrorCategory.NOT_FOUND;
            case ALREADY_EXISTS -> HyperscaleDbErrorCategory.CONFLICT;
            case PERMISSION_DENIED -> HyperscaleDbErrorCategory.AUTHORIZATION_FAILED;
            case RESOURCE_EXHAUSTED -> HyperscaleDbErrorCategory.THROTTLED;
            case FAILED_PRECONDITION -> HyperscaleDbErrorCategory.INVALID_REQUEST;
            case ABORTED -> HyperscaleDbErrorCategory.CONFLICT;
            case UNIMPLEMENTED -> HyperscaleDbErrorCategory.UNSUPPORTED_CAPABILITY;
            case INTERNAL -> HyperscaleDbErrorCategory.PROVIDER_ERROR;
            case UNAVAILABLE -> HyperscaleDbErrorCategory.TRANSIENT_FAILURE;
            case UNAUTHENTICATED -> HyperscaleDbErrorCategory.AUTHENTICATION_FAILED;
            default -> {
                LOG.warn("Unmapped Spanner ErrorCode for category mapping: {}", errorCode);
                yield HyperscaleDbErrorCategory.PROVIDER_ERROR;
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
