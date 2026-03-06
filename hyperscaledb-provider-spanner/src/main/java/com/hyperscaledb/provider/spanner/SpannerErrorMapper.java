package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.*;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Google Cloud Spanner exceptions to portable {@link HyperscaleDbException}
 * instances.
 */
public final class SpannerErrorMapper {

    private SpannerErrorMapper() {
    }

    public static HyperscaleDbException map(SpannerException e, String operation) {
        HyperscaleDbErrorCategory category = mapCategory(e.getErrorCode());
        boolean retryable = e.isRetryable();

        Map<String, String> details = new LinkedHashMap<>();
        details.put("grpcStatusCode", String.valueOf(e.getErrorCode()));
        details.put("errorMessage", e.getMessage());

        HyperscaleDbError error = new HyperscaleDbError(
                category,
                e.getMessage(),
                ProviderId.SPANNER,
                operation,
                retryable,
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
            default -> HyperscaleDbErrorCategory.PROVIDER_ERROR;
        };
    }
}
