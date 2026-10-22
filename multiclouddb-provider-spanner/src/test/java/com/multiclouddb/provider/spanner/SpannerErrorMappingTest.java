// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpannerErrorMapper} verifying gRPC error codes map
 * to the correct portable error categories.
 */
class SpannerErrorMappingTest {

    @ParameterizedTest(name = "gRPC {0} -> {1}")
    @CsvSource({
            "INVALID_ARGUMENT, INVALID_REQUEST",
            "NOT_FOUND, NOT_FOUND",
            "ALREADY_EXISTS, CONFLICT",
            "PERMISSION_DENIED, AUTHORIZATION_FAILED",
            "RESOURCE_EXHAUSTED, THROTTLED",
            "FAILED_PRECONDITION, INVALID_REQUEST",
            "ABORTED, TRANSIENT_FAILURE",
            "UNIMPLEMENTED, UNSUPPORTED_CAPABILITY",
            "INTERNAL, PROVIDER_ERROR",
            "UNAVAILABLE, TRANSIENT_FAILURE",
            "UNAUTHENTICATED, AUTHENTICATION_FAILED"
    })
    @DisplayName("gRPC error code maps to correct category")
    void errorCodeMapsCorrectly(String errorCodeName, String expectedCategory) {
        ErrorCode errorCode = ErrorCode.valueOf(errorCodeName);
        SpannerException spannerEx = SpannerExceptionFactory.newSpannerException(
                errorCode, "Test error: " + errorCodeName);

        MulticloudDbException result = SpannerErrorMapper.map(spannerEx, "test-op");

        assertEquals(MulticloudDbErrorCategory.fromString(expectedCategory), result.error().category());
        assertEquals("spanner", result.error().provider().id());
        assertEquals("test-op", result.error().operation());
    }

    @Test
    @DisplayName("statusCode() field carries the numeric gRPC status code")
    void statusCodeFieldSet() {
        SpannerException spannerEx = SpannerExceptionFactory.newSpannerException(
                ErrorCode.NOT_FOUND, "Resource not found");

        MulticloudDbException result = SpannerErrorMapper.map(spannerEx, "get");

        // NOT_FOUND gRPC status = 5
        assertEquals(5, result.error().statusCode());
        assertFalse(result.error().providerDetails().containsKey("grpcStatusCode"),
                "numeric code must not be duplicated in providerDetails");
    }

    @Test
    @DisplayName("Provider details include gRPC status name and error message")
    void providerDetailsIncluded() {
        SpannerException spannerEx = SpannerExceptionFactory.newSpannerException(
                ErrorCode.NOT_FOUND, "Resource not found");

        MulticloudDbException result = SpannerErrorMapper.map(spannerEx, "get");

        assertEquals("NOT_FOUND", result.error().providerDetails().get("grpcStatus"),
                "human-readable gRPC status name must be in providerDetails");
        assertNotNull(result.error().providerDetails().get("errorMessage"));
    }

    @Test
    @DisplayName("Generic exception maps to PROVIDER_ERROR")
    void genericExceptionMapsToProviderError() {
        RuntimeException genericEx = new RuntimeException("Unexpected failure");

        MulticloudDbException result = SpannerErrorMapper.map(genericEx, "put");

        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, result.error().category());
        assertEquals("spanner", result.error().provider().id());
        assertFalse(result.error().retryable());
        assertSame(genericEx, result.getCause());
    }

    @Test
    @DisplayName("SpannerException passed to generic overload is handled correctly")
    void spannerExceptionViaGenericOverload() {
        SpannerException spannerEx = SpannerExceptionFactory.newSpannerException(
                ErrorCode.UNAVAILABLE, "Service unavailable");

        MulticloudDbException result = SpannerErrorMapper.map((Exception) spannerEx, "query");

        assertEquals(MulticloudDbErrorCategory.TRANSIENT_FAILURE, result.error().category());
    }

    @Test
    @DisplayName("Original exception is preserved as cause")
    void originalExceptionPreserved() {
        SpannerException spannerEx = SpannerExceptionFactory.newSpannerException(
                ErrorCode.INTERNAL, "Internal error");

        MulticloudDbException result = SpannerErrorMapper.map(spannerEx, "delete");
        assertSame(spannerEx, result.getCause());
    }

    @Test
    @DisplayName("Retryable flag reflects SpannerException.isRetryable()")
    void retryableFlagFromSpanner() {
        // UNAVAILABLE is typically retryable in Spanner
        SpannerException unavailable = SpannerExceptionFactory.newSpannerException(
                ErrorCode.UNAVAILABLE, "Service unavailable");
        MulticloudDbException result = SpannerErrorMapper.map(unavailable, "get");
        // SpannerException.isRetryable() determines this — just verify it's propagated
        assertEquals(unavailable.isRetryable(), result.error().retryable());
    }
}
