// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
            "413, INVALID_REQUEST",
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
        MulticloudDbException result = CosmosErrorMapper.map(cosmosEx, OperationNames.READ);

        assertEquals(MulticloudDbErrorCategory.fromString(expectedCategory), result.error().category());
        assertEquals("cosmos", result.error().provider().id());
        assertEquals(OperationNames.READ, result.error().operation());
        assertEquals(statusCode, result.error().statusCode(),
                "statusCode() must match the HTTP status returned by Cosmos");
        assertFalse(result.error().providerDetails().containsKey("statusCode"),
                "statusCode must not be duplicated in providerDetails");
    }

    @ParameterizedTest(name = "HTTP {0} retryable={1}")
    @CsvSource({
            "400, false",
            "401, false",
            "404, false",
            "409, false",
            "413, false",
            "429, true",
            "449, true",
            "500, true",
            "502, true",
            "503, true"
    })
    @DisplayName("Retryable flag set correctly")
    void retryableFlagCorrect(int statusCode, boolean expectedRetryable) {
        CosmosException cosmosEx = mockCosmosException(statusCode, 0);
        MulticloudDbException result = CosmosErrorMapper.map(cosmosEx, OperationNames.READ);

        assertEquals(expectedRetryable, result.error().retryable());
    }

    @ParameterizedTest(name = "HTTP {0} subStatus {1} -> subStatusCode in providerDetails")
    @CsvSource({
            // Sub-status 0 = no sub-status (default)
            "404, 0",
            // 1002 = write forbidden on read region
            "403, 1002",
            // 1008 = insufficient throughput / RU limit
            "429, 1008",
            // 1022 = partition migration / split in progress
            "503, 1022",
            // 5300 = AAD token not allowed on data plane (RBAC enforcement)
            "403, 5300",
    })
    @DisplayName("Sub-status code is captured in providerDetails")
    void subStatusCodeCapturedInProviderDetails(int statusCode, int subStatusCode) {
        CosmosException cosmosEx = mockCosmosException(statusCode, subStatusCode);

        MulticloudDbException result = CosmosErrorMapper.map(cosmosEx, OperationNames.READ);

        assertNotNull(result.error().providerDetails());
        assertEquals(String.valueOf(subStatusCode),
                result.error().providerDetails().get("subStatusCode"),
                "subStatusCode must be captured in providerDetails");
        assertEquals(statusCode, result.error().statusCode(),
                "statusCode() field must carry the HTTP status code");
        assertFalse(result.error().providerDetails().containsKey("statusCode"),
                "statusCode must not be duplicated in providerDetails");
    }

    @Test
    @DisplayName("Provider details include activity id and request charge")
    void providerDetailsIncluded() {
        CosmosException cosmosEx = mockCosmosException(404, 0);
        when(cosmosEx.getActivityId()).thenReturn("activity-123");
        when(cosmosEx.getRequestCharge()).thenReturn(3.5);

        MulticloudDbException result = CosmosErrorMapper.map(cosmosEx, OperationNames.READ);

        assertEquals("activity-123", result.error().providerDetails().get("requestId"));
        assertEquals("3.5", result.error().providerDetails().get("requestCharge"));
    }

    @Test
    @DisplayName("Original exception is preserved as cause")
    void originalExceptionPreserved() {
        CosmosException cosmosEx = mockCosmosException(500, 0);
        MulticloudDbException result = CosmosErrorMapper.map(cosmosEx, OperationNames.CREATE);

        assertSame(cosmosEx, result.getCause());
    }

    @ParameterizedTest(name = "HTTP 413 preserves error context for {0}")
    @ValueSource(strings = {
            OperationNames.CREATE, OperationNames.READ, OperationNames.UPDATE,
            OperationNames.UPSERT, OperationNames.DELETE, OperationNames.QUERY,
            OperationNames.ENSURE_DATABASE, OperationNames.ENSURE_CONTAINER,
            OperationNames.LIST_CURSORS, OperationNames.READ_CHANGES
    })
    @DisplayName("HTTP 413 is an invalid request with native error context preserved")
    void requestTooLargePreservesErrorContext(String operation) {
        CosmosException cosmosEx = mockCosmosException(413, 1234);
        when(cosmosEx.getActivityId()).thenReturn("size-request");
        when(cosmosEx.getRequestCharge()).thenReturn(2.5);

        MulticloudDbException result = CosmosErrorMapper.map(cosmosEx, operation);

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, result.error().category());
        assertFalse(result.error().retryable());
        assertEquals(413, result.error().statusCode());
        assertEquals(cosmosEx.getMessage(), result.getMessage());
        assertEquals(cosmosEx.getMessage(), result.error().message());
        assertSame(cosmosEx, result.getCause());
        assertEquals("cosmos", result.error().provider().id());
        assertEquals(operation, result.error().operation());
        assertEquals("1234", result.error().providerDetails().get("subStatusCode"));
        assertEquals("size-request", result.error().providerDetails().get("requestId"));
        assertEquals("2.5", result.error().providerDetails().get("requestCharge"));
        assertFalse(result.error().providerDetails().containsKey("statusCode"));
    }

    private CosmosException mockCosmosException(int statusCode, int subStatusCode) {
        CosmosException ex = mock(CosmosException.class);
        when(ex.getStatusCode()).thenReturn(statusCode);
        when(ex.getSubStatusCode()).thenReturn(subStatusCode);
        when(ex.getMessage()).thenReturn("Mock Cosmos error " + statusCode + "/" + subStatusCode);
        when(ex.getActivityId()).thenReturn(null);
        when(ex.getRequestCharge()).thenReturn(0.0);
        return ex;
    }
}
