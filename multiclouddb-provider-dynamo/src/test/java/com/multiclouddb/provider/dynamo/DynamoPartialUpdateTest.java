// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbResponseMetadata;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoPartialUpdateTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private DynamoDbClient dynamo;
    private DynamoProviderClient provider;

    @BeforeEach
    void setUp() {
        dynamo = mock(DynamoDbClient.class);
        provider = new DynamoProviderClient(dynamo);
    }

    @Test
    void updateUsesOneConditionalUpdateItemAndNoPutOrRead() {
        UpdateItemResponse response = mock(UpdateItemResponse.class);
        DynamoDbResponseMetadata metadata = mock(DynamoDbResponseMetadata.class);
        when(response.responseMetadata()).thenReturn(metadata);
        when(metadata.requestId()).thenReturn("request-1");
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(response);

        provider.update(ADDRESS, KEY, Map.of("status", "SHIPPED"), OperationOptions.defaults());

        ArgumentCaptor<UpdateItemRequest> request = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(request.capture());
        UpdateItemRequest actual = request.getValue();
        assertEquals("db__items", actual.tableName());
        assertEquals("SET #f0 = :v0", actual.updateExpression());
        assertEquals("attribute_exists(#pk)", actual.conditionExpression());
        assertEquals("status", actual.expressionAttributeNames().get("#f0"));
        assertEquals(DynamoConstants.ATTR_PARTITION_KEY,
                actual.expressionAttributeNames().get("#pk"));
        assertFalse(actual.expressionAttributeNames().containsValue(DynamoConstants.ATTR_TTL_EXPIRY));
        assertEquals(ReturnConsumedCapacity.TOTAL, actual.returnConsumedCapacity());
        assertEquals("pk", actual.key().get(DynamoConstants.ATTR_PARTITION_KEY).s());
        assertEquals("sk", actual.key().get(DynamoConstants.ATTR_SORT_KEY).s());
        verify(dynamo, never()).putItem(any(software.amazon.awssdk.services.dynamodb.model.PutItemRequest.class));
        verify(dynamo, never()).getItem(any(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.class));
    }

    @Test
    void conditionalFailureMapsToNotFound() {
        ConditionalCheckFailedException failure = ConditionalCheckFailedException.builder()
                .message("missing")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("ConditionalCheckFailedException")
                        .build())
                .build();
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> provider.update(ADDRESS, KEY, Map.of("status", "SHIPPED"),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, ex.error().category());
        assertFalse(ex.error().retryable());
        assertEquals("ConditionalCheckFailedException",
                ex.error().providerDetails().get("errorCode"));
        assertSame(failure, ex.getCause());
        verify(dynamo).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void nativeEnvelopeFailurePerformsNoDynamoCall() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> provider.update(ADDRESS, KEY, fields(288), OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        verify(dynamo, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void resultItemSizeFailureMapsAfterOneUpdateAttemptAndPreservesCause() {
        DynamoDbException failure = mock(DynamoDbException.class);
        AwsErrorDetails errorDetails = mock(AwsErrorDetails.class);
        when(failure.getMessage()).thenReturn(
                "Item size has exceeded the maximum allowed size");
        when(failure.statusCode()).thenReturn(400);
        when(failure.requestId()).thenReturn("request-size");
        when(failure.awsErrorDetails()).thenReturn(errorDetails);
        when(errorDetails.errorCode()).thenReturn("ValidationException");
        when(errorDetails.errorMessage()).thenReturn(
                "Item size has exceeded the maximum allowed size");
        when(errorDetails.serviceName()).thenReturn("DynamoDb");
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(failure);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> provider.update(ADDRESS, KEY, Map.of("status", "SHIPPED"),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                ex.error().category());
        assertFalse(ex.error().retryable());
        assertEquals("partial_update_extended_payload",
                ex.error().providerDetails().get("capability"));
        assertEquals("dynamodb_result_item_size_limit",
                ex.error().providerDetails().get("reason"));
        assertEquals("409600",
                ex.error().providerDetails().get("maximumResultBytes"));
        assertEquals("ValidationException",
                ex.error().providerDetails().get("errorCode"));
        assertEquals("request-size", ex.error().providerDetails().get("requestId"));
        assertSame(failure, ex.getCause());
        verify(dynamo).updateItem(any(UpdateItemRequest.class));
    }

    private static Map<String, Object> fields(int count) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            fields.put("field" + i, i);
        }
        return fields;
    }
}
