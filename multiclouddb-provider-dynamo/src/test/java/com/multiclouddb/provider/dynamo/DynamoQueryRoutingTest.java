// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.SortDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests that verify DynamoDB API routing inside {@link DynamoProviderClient#query}.
 *
 * <p>Specifically asserts:
 * <ul>
 *   <li>A query with a {@code partitionKey} must invoke {@code DynamoDbClient.query()}
 *       with a {@code KeyConditionExpression} — never {@code scan()}.</li>
 *   <li>A query without a {@code partitionKey} must invoke {@code DynamoDbClient.scan()}
 *       — never {@code query()}.</li>
 *   <li>A query with both a {@code partitionKey} and a filter expression invokes
 *       {@code query()} with both {@code keyConditionExpression} and
 *       {@code filterExpression} set.</li>
 * </ul>
 *
 * <p>Uses a mock {@link DynamoDbClient} injected via the package-private constructor
 * to isolate routing logic from network I/O.
 */
class DynamoQueryRoutingTest {

    private DynamoDbClient mockDynamoClient;
    private DynamoProviderClient client;

    @Test
    void explicitOrderingRemainsUnsupportedIncludingSortKey() {
        for (String field : new String[] {"sortKey", "createdAt"}) {
            for (SortDirection direction : SortDirection.values()) {
                QueryRequest request = QueryRequest.builder()
                        .partitionKey("pk-001")
                        .orderBy(field, direction)
                        .build();
                MulticloudDbException error = assertThrows(MulticloudDbException.class,
                        () -> client.query(new ResourceAddress("testdb", "users"), request, null));
                assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                        error.error().category());
            }
        }
        verifyNoInteractions(mockDynamoClient);
    }

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

        ScanResponse scanResponse = mock(ScanResponse.class);
        when(scanResponse.items()).thenReturn(Collections.emptyList());
        when(scanResponse.lastEvaluatedKey()).thenReturn(Collections.emptyMap());
        when(scanResponse.sdkHttpResponse()).thenReturn(httpResponse);
        when(scanResponse.consumedCapacity()).thenReturn(null);
        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(scanResponse);

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
    @DisplayName("query() with partitionKey and filter sets both KeyConditionExpression and FilterExpression")
    void queryWithPartitionKeyAndExpressionSetsKeyConditionAndFilter() {
        ResourceAddress address = new ResourceAddress("testdb", "orders");
        QueryRequest request = QueryRequest.builder()
                .partitionKey("pk-002")
                .expression("status = :s")
                .parameters(Map.of(":s", "active"))
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
    @DisplayName("query() without partitionKey routes to DynamoDB Scan, not Query API")
    void queryWithoutPartitionKeyUsesScan() {
        ResourceAddress address = new ResourceAddress("testdb", "users");
        QueryRequest request = QueryRequest.builder().build();

        QueryPage page = client.query(address, request, null);

        assertNotNull(page);
        verify(mockDynamoClient).scan(any(ScanRequest.class));
        verify(mockDynamoClient, never()).query(any(
                software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }

    @Test
    @DisplayName("query() without partitionKey but with expression routes to Scan with FilterExpression")
    void queryWithExpressionButNoPartitionKeyUsesScanFilter() {
        ResourceAddress address = new ResourceAddress("testdb", "users");
        QueryRequest request = QueryRequest.builder()
                .expression("age > :a")
                .parameters(Map.of(":a", 30))
                .build();

        client.query(address, request, null);

        verify(mockDynamoClient).scan(any(ScanRequest.class));
        verify(mockDynamoClient, never()).query(any(
                software.amazon.awssdk.services.dynamodb.model.QueryRequest.class));
    }
}
