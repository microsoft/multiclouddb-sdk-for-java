package com.hyperscaledb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CosmosErrorMapper} verifying HTTP status codes map
 * to the correct portable error categories.
 */
class CosmosErrorMappingTest {

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource({
            "400, INVALID_REQUEST",
            "401, AUTHENTICATION_FAILED",
            "403, AUTHORIZATION_FAILED",
            "404, NOT_FOUND",
            "409, CONFLICT",
            "412, CONFLICT",
            "429, THROTTLED",
            "449, TRANSIENT_FAILURE",
            "500, TRANSIENT_FAILURE",
            "502, TRANSIENT_FAILURE",
            "503, TRANSIENT_FAILURE",
            "418, PROVIDER_ERROR"
    })
    @DisplayName("Status code maps to correct category")
    void statusCodeMapsCorrectly(int statusCode, String expectedCategory) {
        CosmosException cosmosEx = mockCosmosException(statusCode, 0);
        HyperscaleDbException result = CosmosErrorMapper.map(cosmosEx, "test-op");

        assertEquals(HyperscaleDbErrorCategory.valueOf(expectedCategory), result.error().category());
        assertEquals("cosmos", result.error().provider().id());
        assertEquals("test-op", result.error().operation());
        assertNotNull(result.error().providerDetails());
        assertEquals(String.valueOf(statusCode), result.error().providerDetails().get("statusCode"));
    }

    @ParameterizedTest(name = "HTTP {0} retryable={1}")
    @CsvSource({
            "400, false",
            "401, false",
            "404, false",
            "409, false",
            "429, true",
            "449, true",
            "500, true",
            "502, true",
            "503, true"
    })
    @DisplayName("Retryable flag set correctly")
    void retryableFlagCorrect(int statusCode, boolean expectedRetryable) {
        CosmosException cosmosEx = mockCosmosException(statusCode, 0);
        HyperscaleDbException result = CosmosErrorMapper.map(cosmosEx, "get");

        assertEquals(expectedRetryable, result.error().retryable());
    }

    @Test
    @DisplayName("Provider details include activity id and request charge")
    void providerDetailsIncluded() {
        CosmosException cosmosEx = mockCosmosException(404, 0);
        when(cosmosEx.getActivityId()).thenReturn("activity-123");
        when(cosmosEx.getRequestCharge()).thenReturn(3.5);

        HyperscaleDbException result = CosmosErrorMapper.map(cosmosEx, "get");

        assertEquals("activity-123", result.error().providerDetails().get("requestId"));
        assertEquals("3.5", result.error().providerDetails().get("requestCharge"));
    }

    @Test
    @DisplayName("Original exception is preserved as cause")
    void originalExceptionPreserved() {
        CosmosException cosmosEx = mockCosmosException(500, 0);
        HyperscaleDbException result = CosmosErrorMapper.map(cosmosEx, "put");

        assertSame(cosmosEx, result.getCause());
    }

    private CosmosException mockCosmosException(int statusCode, int subStatusCode) {
        CosmosException ex = mock(CosmosException.class);
        when(ex.getStatusCode()).thenReturn(statusCode);
        when(ex.getSubStatusCode()).thenReturn(subStatusCode);
        when(ex.getMessage()).thenReturn("Mock Cosmos error " + statusCode);
        when(ex.getActivityId()).thenReturn(null);
        when(ex.getRequestCharge()).thenReturn(0.0);
        return ex;
    }
}
