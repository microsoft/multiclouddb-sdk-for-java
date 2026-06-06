// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests that verify DynamoDB API routing inside {@link DynamoProviderClient#query}.
 *
 * <p>Under the strict-LCD contract every {@link QueryRequest} requires a
 * {@code partitionKey}, so the provider always routes to the DynamoDB Query API
 * (never Scan). These tests assert:
 * <ul>
 *   <li>A query with a {@code partitionKey} invokes {@code DynamoDbClient.query()}
 *       with a {@code KeyConditionExpression} — never {@code scan()}.</li>
 *   <li>A query with both a {@code partitionKey} and a portable filter expression
 *       invokes {@code query()} with both {@code keyConditionExpression} and
 *       {@code filterExpression} set.</li>
 *   <li>{@code orderBy(sortKey, DESC)} sets {@code scanIndexForward(false)} on
 *       the underlying request.</li>
 * </ul>
 */
class DynamoQueryRoutingTest {

    private DynamoDbClient mockDynamoClient;
    private DynamoProviderClient client;

    @BeforeEach
    void setUp() {
        mockDynamoClient = mock(DynamoDbClient.class);

        SdkHttpResponse httpResponse = mock(SdkHttpResponse.class);
        when(httpResponse.firstMatchingHeader(any(String.class))).thenReturn(Optional.empty());

        QueryResponse queryResponse = mock(QueryResponse.class);
        when(queryResponse.items()).thenReturn(Collections.emptyList());
        when(queryResponse.lastEvaluatedKey()).thenReturn(Collections.emptyMap());
        when(queryResponse.sdkHttpResponse()).thenReturn(httpResponse);
        when(queryResponse.consumedCapacity()).thenReturn(null);
        when(mockDynamoClient.query(any(
                software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                .thenReturn(queryResponse);

        client = new DynamoProviderClient(mockDynamoClient);
    }

    @Test
    @DisplayName("query() with partitionKey routes to DynamoDB Query API with KeyConditionExpression")
    void queryWithPartitionKeyUsesQueryApi() {
        ResourceAddress address = new ResourceAddress("testdb", "users");
        QueryRequest request = QueryRequest.builder()
                .partitionKey("pk-001")
                .build();

        QueryPage page = client.query(address, request, null);

        assertNotNull(page);
        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(
                        software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(mockDynamoClient).query(captor.capture());
        assertNotNull(captor.getValue().keyConditionExpression(),
                "KeyConditionExpression must be set for partition-key queries");
        assertTrue(captor.getValue().keyConditionExpression()
                        .contains(DynamoConstants.ATTR_PARTITION_KEY),
                "KeyConditionExpression must reference the partition key attribute");
        verify(mockDynamoClient, never()).scan(any(ScanRequest.class));
    }

    @Test
    @DisplayName("query() with partitionKey and portable expression sets both KeyConditionExpression and FilterExpression")
    void queryWithPartitionKeyAndExpressionSetsKeyConditionAndFilter() {
        ResourceAddress address = new ResourceAddress("testdb", "orders");
        QueryRequest request = QueryRequest.builder()
                .partitionKey("pk-002")
                .expression("status = @s")
                .parameters(Map.of("s", "active"))
                .build();

        client.query(address, request, null);

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(
                        software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(mockDynamoClient).query(captor.capture());
        assertNotNull(captor.getValue().keyConditionExpression(),
                "KeyConditionExpression must be set");
        assertNotNull(captor.getValue().filterExpression(),
                "FilterExpression must be set when expression is provided");
        verify(mockDynamoClient, never()).scan(any(ScanRequest.class));
    }

    @Test
    @DisplayName("query() with orderBy(sortKey, DESC) sets scanIndexForward(false)")
    void queryWithDescSortReversesScanDirection() {
        ResourceAddress address = new ResourceAddress("testdb", "events");
        QueryRequest request = QueryRequest.builder()
                .partitionKey("pk-003")
                .orderBy("sortKey", com.multiclouddb.api.SortDirection.DESC)
                .build();

        client.query(address, request, null);

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(
                        software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(mockDynamoClient).query(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().scanIndexForward(),
                "orderBy(sortKey, DESC) must set scanIndexForward(false)");
    }

    @Test
    @DisplayName("query() with default sort (no orderBy) sets scanIndexForward(true)")
    void queryWithDefaultSortIsAscending() {
        ResourceAddress address = new ResourceAddress("testdb", "events");
        QueryRequest request = QueryRequest.builder()
                .partitionKey("pk-004")
                .build();

        client.query(address, request, null);

        ArgumentCaptor<software.amazon.awssdk.services.dynamodb.model.QueryRequest> captor =
                ArgumentCaptor.forClass(
                        software.amazon.awssdk.services.dynamodb.model.QueryRequest.class);
        verify(mockDynamoClient).query(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().scanIndexForward(),
                "No orderBy implies ascending scanIndexForward(true)");
    }
}
