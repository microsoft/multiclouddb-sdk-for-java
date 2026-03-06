package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        HyperscaleDbException result = DynamoErrorMapper.map(ex, "test-op");

        assertEquals(HyperscaleDbErrorCategory.valueOf(expectedCategory), result.error().category());
        assertEquals("dynamo", result.error().provider().id());
        assertEquals("test-op", result.error().operation());
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
        HyperscaleDbException result = DynamoErrorMapper.map(ex, "query");

        assertEquals(HyperscaleDbErrorCategory.valueOf(expectedCategory), result.error().category());
    }

    @Test
    @DisplayName("Provider details include error code and request id")
    void providerDetailsIncluded() {
        DynamoDbException ex = mockDynamoException(400, "ValidationException");
        when(ex.requestId()).thenReturn("req-abc-123");

        HyperscaleDbException result = DynamoErrorMapper.map(ex, "put");

        assertEquals("ValidationException", result.error().providerDetails().get("errorCode"));
        assertEquals("req-abc-123", result.error().providerDetails().get("requestId"));
        assertEquals("400", result.error().providerDetails().get("statusCode"));
    }

    @Test
    @DisplayName("Throttling exception is retryable")
    void throttlingIsRetryable() {
        DynamoDbException ex = mockDynamoException(400, "ThrottlingException");
        when(ex.isThrottlingException()).thenReturn(true);

        HyperscaleDbException result = DynamoErrorMapper.map(ex, "get");
        assertTrue(result.error().retryable());
    }

    @Test
    @DisplayName("Server errors are retryable")
    void serverErrorsRetryable() {
        DynamoDbException ex = mockDynamoException(500, "InternalServerError");
        HyperscaleDbException result = DynamoErrorMapper.map(ex, "get");
        assertTrue(result.error().retryable());
    }

    @Test
    @DisplayName("Client errors are not retryable")
    void clientErrorsNotRetryable() {
        DynamoDbException ex = mockDynamoException(400, "ValidationException");
        HyperscaleDbException result = DynamoErrorMapper.map(ex, "put");
        assertFalse(result.error().retryable());
    }

    @Test
    @DisplayName("Original exception is preserved as cause")
    void originalExceptionPreserved() {
        DynamoDbException ex = mockDynamoException(500, "InternalServerError");
        HyperscaleDbException result = DynamoErrorMapper.map(ex, "delete");
        assertSame(ex, result.getCause());
    }

    private DynamoDbException mockDynamoException(int statusCode, String errorCode) {
        DynamoDbException ex = mock(DynamoDbException.class);
        when(ex.statusCode()).thenReturn(statusCode);
        when(ex.getMessage()).thenReturn("Mock DynamoDB error: " + errorCode);
        when(ex.requestId()).thenReturn(null);
        when(ex.isThrottlingException()).thenReturn(false);

        AwsErrorDetails details = mock(AwsErrorDetails.class);
        when(details.errorCode()).thenReturn(errorCode);
        when(details.serviceName()).thenReturn("DynamoDb");
        when(ex.awsErrorDetails()).thenReturn(details);

        return ex;
    }
}
