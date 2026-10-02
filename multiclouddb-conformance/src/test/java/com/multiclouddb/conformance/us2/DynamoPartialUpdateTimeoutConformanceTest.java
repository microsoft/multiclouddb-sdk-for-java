// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.provider.dynamo.DynamoProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.lang.reflect.Constructor;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dynamo")
@Tag("emulator")
@DisplayName("DynamoDB partial-update timeout conformance")
public class DynamoPartialUpdateTimeoutConformanceTest
        extends PartialUpdateTimeoutConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.DYNAMO;
    }

    @Override
    protected Stream<TimeoutScenario> timeoutScenarios() {
        return Stream.of(
                serviceTimeout("RequestTimeout"),
                serviceTimeout("RequestTimeoutException"),
                sdkTimeout(mock(ApiCallTimeoutException.class), "API call"),
                sdkTimeout(mock(ApiCallAttemptTimeoutException.class), "API call attempt"));
    }

    private static TimeoutScenario serviceTimeout(String errorCode) {
        DynamoDbClient nativeClient = mock(DynamoDbClient.class);
        DynamoDbException failure = mock(DynamoDbException.class);
        AwsErrorDetails details = mock(AwsErrorDetails.class);

        when(failure.getMessage()).thenReturn("DynamoDB request timed out");
        when(failure.statusCode()).thenReturn(400);
        when(failure.requestId()).thenReturn("dynamo-timeout-request");
        when(failure.awsErrorDetails()).thenReturn(details);
        when(details.errorCode()).thenReturn(errorCode);
        when(details.serviceName()).thenReturn("DynamoDb");
        when(nativeClient.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);

        return new TimeoutScenario(
                "DynamoDB " + errorCode,
                providerFrom(nativeClient),
                failure,
                () -> verify(nativeClient).updateItem(any(UpdateItemRequest.class)),
                result -> {
                    assertEquals(400, result.error().statusCode());
                    assertEquals(errorCode,
                            result.error().providerDetails().get("errorCode"));
                    assertEquals("dynamo-timeout-request",
                            result.error().providerDetails().get("requestId"));
                });
    }

    private static TimeoutScenario sdkTimeout(SdkClientException failure, String label) {
        DynamoDbClient nativeClient = mock(DynamoDbClient.class);
        when(failure.getMessage()).thenReturn("DynamoDB " + label + " timed out");
        when(nativeClient.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);

        return new TimeoutScenario(
                "DynamoDB SDK " + label + " timeout",
                providerFrom(nativeClient),
                failure,
                () -> verify(nativeClient).updateItem(any(UpdateItemRequest.class)),
                result -> assertEquals("dynamodb_request_timeout",
                        result.error().providerDetails().get("reason")));
    }

    private static DynamoProviderClient providerFrom(DynamoDbClient nativeClient) {
        try {
            Constructor<DynamoProviderClient> constructor =
                    DynamoProviderClient.class.getDeclaredConstructor(DynamoDbClient.class);
            constructor.setAccessible(true);
            return constructor.newInstance(nativeClient);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct DynamoDB provider test adapter", e);
        }
    }
}
