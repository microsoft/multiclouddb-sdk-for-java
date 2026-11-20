// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.ResourceConsumption;
import com.multiclouddb.api.StatusCodeDomain;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.awscore.DefaultAwsResponseMetadata;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;

import java.util.Collections;
import java.util.List;
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

    @Test
    void everyQueryRoutePreservesObservedCapacityAndNativeMetadata() {
        var metadata = DefaultAwsResponseMetadata.create(Map.of("AWS_REQUEST_ID", "metadata-id"));
        var http = SdkHttpResponse.builder().statusCode(200)
                .putHeader(DynamoConstants.HEADER_REQUEST_ID, "header-id").build();
        ResourceAddress address = new ResourceAddress("testdb", "users");
        for (ConsumedCapacity capacity : java.util.Arrays.asList(
                ConsumedCapacity.builder().capacityUnits(2.5).build(),
                ConsumedCapacity.builder().capacityUnits(0d).build(),
                ConsumedCapacity.builder().build(), null)) {
            var scan = ScanResponse.builder().consumedCapacity(capacity);
            scan.responseMetadata(metadata);
            scan.sdkHttpResponse(http);
            when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(scan.build());
            var keyQuery = QueryResponse.builder().consumedCapacity(capacity);
            keyQuery.responseMetadata(metadata);
            keyQuery.sdkHttpResponse(http);
            when(mockDynamoClient.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                    .thenReturn(keyQuery.build());
            var statement = ExecuteStatementResponse.builder().consumedCapacity(capacity).nextToken("next-page");
            statement.responseMetadata(metadata);
            statement.sdkHttpResponse(http);
            when(mockDynamoClient.executeStatement(any(ExecuteStatementRequest.class)))
                    .thenReturn(statement.build());
            for (QueryRequest query : List.of(QueryRequest.builder().build(),
                    QueryRequest.builder().partitionKey("pk").build(),
                    QueryRequest.builder().expression("age > :a").parameters(Map.of("a", 1)).build(),
                    QueryRequest.builder().partitionKey("pk").expression("age > :a")
                            .parameters(Map.of("a", 1)).build(),
                    QueryRequest.builder().nativeExpression("SELECT * FROM \"testdb__users\"").build())) {
                assertQueryDiagnostics(client.query(address, query, null), capacity);
            }
            var translated = TranslatedQuery.withPositionalParameters(
                    "SELECT * FROM \"users\" WHERE age > ?", "age > ?", List.of(1));
            var page = client.queryWithTranslation(address, translated, QueryRequest.builder().build(), null);
            assertQueryDiagnostics(page, capacity);
            assertEquals("next-page", page.continuationToken());
        }
        ArgumentCaptor<ExecuteStatementRequest> captor = ArgumentCaptor.forClass(ExecuteStatementRequest.class);
        verify(mockDynamoClient, times(8)).executeStatement(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(request -> request.returnConsumedCapacity() == ReturnConsumedCapacity.TOTAL));
    }

    @Test
    void queryFallsBackToHttpRequestIdAndLeavesMissingMetadataUnavailable() {
        ResourceAddress address = new ResourceAddress("testdb", "users");
        var http = SdkHttpResponse.builder().statusCode(200)
                .putHeader(DynamoConstants.HEADER_REQUEST_ID, "header-id").build();
        var response = ScanResponse.builder();
        response.sdkHttpResponse(http);
        when(mockDynamoClient.scan(any(ScanRequest.class)))
                .thenReturn(response.build())
                .thenReturn(ScanResponse.builder().build());
        assertEquals("header-id", client.query(address, QueryRequest.builder().build(), null)
                .diagnostics().requestId());
        var absent = client.query(address, QueryRequest.builder().build(), null).diagnostics();
        assertNull(absent.requestId(), "AWS UNKNOWN sentinel is not an observed ID");
        assertNull(absent.statusCode());
        assertNull(absent.statusCodeDomain());
        assertNull(absent.consumption());
    }

    private static void assertQueryDiagnostics(QueryPage page, ConsumedCapacity capacity) {
        var diag = page.diagnostics();
        assertEquals("metadata-id", diag.requestId());
        assertEquals(200, diag.statusCode());
        assertEquals(StatusCodeDomain.HTTP, diag.statusCodeDomain());
        assertEquals(page.items().size(), diag.itemCount());
        if (capacity == null || capacity.capacityUnits() == null) {
            assertNull(diag.consumption());
            assertEquals(0, diag.requestCharge());
        } else {
            assertEquals(capacity.capacityUnits(), diag.consumption().amount());
            assertEquals(capacity.capacityUnits(), diag.requestCharge());
            assertEquals(ResourceConsumption.Unit.DYNAMODB_CAPACITY_UNIT, diag.consumption().unit());
            assertEquals(ResourceConsumption.Coverage.UNKNOWN, diag.consumption().coverage());
        }
    }
}
