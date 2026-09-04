// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DynamoErrorMapper} verifying AWS error codes and
 * HTTP status codes map to the correct portable error categories.
 */
class DynamoErrorMappingTest {

    @ParameterizedTest(name = "ErrorCode {0} -> {1}")
    @CsvSource({
            "ConditionalCheckFailedException, CONFLICT",
            "ResourceNotFoundException, NOT_FOUND",
            "ValidationException, INVALID_REQUEST",
            "AccessDeniedException, AUTHORIZATION_FAILED",
            "UnrecognizedClientException, AUTHENTICATION_FAILED",
            "ProvisionedThroughputExceededException, THROTTLED",
            "ThrottlingException, THROTTLED",
            "RequestLimitExceeded, THROTTLED",
            "ItemCollectionSizeLimitExceededException, PERMANENT_FAILURE"
    })
    @DisplayName("Error code maps to correct category")
    void errorCodeMapsCorrectly(String errorCode, String expectedCategory) {
        DynamoDbException ex = mockDynamoException(400, errorCode);
        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.READ);

        assertEquals(MulticloudDbErrorCategory.fromString(expectedCategory), result.error().category());
        assertEquals("dynamo", result.error().provider().id());
        assertEquals(OperationNames.READ, result.error().operation());
    }

    @ParameterizedTest(name = "HTTP {0} (no error code) -> {1}")
    @CsvSource({
            "400, INVALID_REQUEST",
            "401, AUTHENTICATION_FAILED",
            "403, AUTHENTICATION_FAILED",
            "404, NOT_FOUND",
            "500, TRANSIENT_FAILURE",
            "502, TRANSIENT_FAILURE",
            "503, TRANSIENT_FAILURE",
            "418, PROVIDER_ERROR"
    })
    @DisplayName("Fallback to status code when no error code match")
    void statusCodeFallbackMapsCorrectly(int statusCode, String expectedCategory) {
        DynamoDbException ex = mockDynamoException(statusCode, "UnknownError");
        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.QUERY);

        assertEquals(MulticloudDbErrorCategory.fromString(expectedCategory), result.error().category());
    }

    @Test
    @DisplayName("statusCode() field carries the HTTP status code")
    void statusCodeFieldSet() {
        DynamoDbException ex = mockDynamoException(400, "ValidationException");
        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPSERT);

        assertEquals(400, result.error().statusCode());
        assertFalse(result.error().providerDetails().containsKey("statusCode"),
                "statusCode must not be duplicated in providerDetails");
    }

    @Test
    @DisplayName("Provider details include error code and request id")
    void providerDetailsIncluded() {
        DynamoDbException ex = mockDynamoException(400, "ValidationException");
        when(ex.requestId()).thenReturn("req-abc-123");

        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPSERT);

        assertEquals("ValidationException", result.error().providerDetails().get("errorCode"));
        assertEquals("req-abc-123", result.error().providerDetails().get("requestId"));
    }

    @Test
    @DisplayName("Throttling exception is retryable")
    void throttlingIsRetryable() {
        DynamoDbException ex = mockDynamoException(400, "ThrottlingException");
        when(ex.isThrottlingException()).thenReturn(true);

        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.READ);
        assertTrue(result.error().retryable());
    }

    @Test
    @DisplayName("Server errors are retryable")
    void serverErrorsRetryable() {
        DynamoDbException ex = mockDynamoException(500, "InternalServerError");
        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.READ);
        assertTrue(result.error().retryable());
    }

    @Test
    @DisplayName("Client errors are not retryable")
    void clientErrorsNotRetryable() {
        DynamoDbException ex = mockDynamoException(400, "ValidationException");
        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPSERT);
        assertFalse(result.error().retryable());
    }

    @Test
    @DisplayName("Original exception is preserved as cause")
    void originalExceptionPreserved() {
        DynamoDbException ex = mockDynamoException(500, "InternalServerError");
        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.DELETE);
        assertSame(ex, result.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Item size has exceeded the maximum allowed size",
            "Item size to update has exceeded the maximum allowed size",
            "Item size has exceeded the maximum allowed size "
                    + "(Service: DynamoDb, Status Code: 400, Request ID: req-size-1)"
    })
    @DisplayName("Update item-size ValidationException maps to the extended-payload capability")
    void updateResultItemSizeLimitMapsToUnsupportedCapability(String message) {
        DynamoDbException ex = mockDynamoException(400, "ValidationException", message);
        when(ex.requestId()).thenReturn("req-size-1");

        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPDATE);

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                result.error().category());
        assertEquals(OperationNames.UPDATE, result.error().operation());
        assertFalse(result.error().retryable());
        assertEquals(400, result.error().statusCode());
        assertEquals("partial_update_extended_payload",
                result.error().providerDetails().get("capability"));
        assertEquals("dynamodb_result_item_size_limit",
                result.error().providerDetails().get("reason"));
        assertEquals("409600",
                result.error().providerDetails().get("maximumResultBytes"));
        assertEquals("ValidationException",
                result.error().providerDetails().get("errorCode"));
        assertEquals("DynamoDb", result.error().providerDetails().get("serviceName"));
        assertEquals("req-size-1", result.error().providerDetails().get("requestId"));
        assertSame(ex, result.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "One or more parameter values were invalid: Type mismatch for key",
            "Item collection size has exceeded the maximum allowed size",
            "Expression size has exceeded the maximum allowed size"
    })
    @DisplayName("Other update ValidationException messages remain invalid requests")
    void otherUpdateValidationMessagesRemainInvalidRequest(String message) {
        DynamoDbException ex = mockDynamoException(400, "ValidationException", message);

        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPDATE);

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, result.error().category());
        assertFalse(result.error().providerDetails().containsKey("capability"));
        assertFalse(result.error().providerDetails().containsKey("maximumResultBytes"));
    }

    @Test
    @DisplayName("Item-size text with a different native code remains normally mapped")
    void resultItemSizeTextWithDifferentCodeDoesNotUseCapabilityMapping() {
        DynamoDbException ex = mockDynamoException(
                400,
                "ResourceNotFoundException",
                "Item size has exceeded the maximum allowed size");

        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPDATE);

        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, result.error().category());
        assertFalse(result.error().providerDetails().containsKey("capability"));
    }

    @Test
    @DisplayName("Item-size ValidationException outside update remains invalid request")
    void resultItemSizeValidationOutsideUpdateRemainsInvalidRequest() {
        DynamoDbException ex = mockDynamoException(
                400,
                "ValidationException",
                "Item size has exceeded the maximum allowed size");

        MulticloudDbException result = DynamoErrorMapper.map(ex, OperationNames.UPSERT);

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, result.error().category());
        assertFalse(result.error().providerDetails().containsKey("capability"));
    }

    private DynamoDbException mockDynamoException(int statusCode, String errorCode) {
        return mockDynamoException(statusCode, errorCode, "Mock DynamoDB error: " + errorCode);
    }

    private DynamoDbException mockDynamoException(
            int statusCode, String errorCode, String message) {
        DynamoDbException ex = mock(DynamoDbException.class);
        when(ex.statusCode()).thenReturn(statusCode);
        when(ex.getMessage()).thenReturn(message);
        when(ex.requestId()).thenReturn(null);
        when(ex.isThrottlingException()).thenReturn(false);

        AwsErrorDetails details = mock(AwsErrorDetails.class);
        when(details.errorCode()).thenReturn(errorCode);
        when(details.errorMessage()).thenReturn(message);
        when(details.serviceName()).thenReturn("DynamoDb");
        when(ex.awsErrorDetails()).thenReturn(details);

        return ex;
    }
}
