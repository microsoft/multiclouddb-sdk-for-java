// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.OperationOptions;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbResponseMetadata;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import com.multiclouddb.api.query.TranslatedQuery;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying that DynamoDB scan paths sort results by {@code sortKey}
 * ascending before returning the {@link QueryPage}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code executeScan} — unfiltered full-table Scan (query with no expression and
 *       no partitionKey)</li>
 *   <li>{@code executeScanWithFilter} — Scan with FilterExpression (query with a legacy
 *       {@code :param}-style expression and no partitionKey)</li>
 * </ul>
 *
 * <p>Uses a mock {@link DynamoDbClient} to inject items returned out of sort-key order
 * and asserts that the resulting {@link QueryPage#items()} are sorted ASC.
 */
class DynamoScanSortOrderTest {

    private DynamoDbClient mockDynamoClient;
    private DynamoProviderClient client;

    @BeforeEach
    void setUp() {
        mockDynamoClient = mock(DynamoDbClient.class);

        SdkHttpResponse httpResponse = mock(SdkHttpResponse.class);
        when(httpResponse.firstMatchingHeader(any(String.class))).thenReturn(Optional.empty());

        // Default scan response — overridden per test with specific items.
        ScanResponse scanResponse = mock(ScanResponse.class);
        when(scanResponse.items()).thenReturn(Collections.emptyList());
        when(scanResponse.lastEvaluatedKey()).thenReturn(Collections.emptyMap());
        when(scanResponse.sdkHttpResponse()).thenReturn(httpResponse);
        when(scanResponse.consumedCapacity()).thenReturn(null);
        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(scanResponse);

        // Default executeStatement response — overridden per test.
        DynamoDbResponseMetadata defaultResponseMetadata = mock(DynamoDbResponseMetadata.class);
        when(defaultResponseMetadata.requestId()).thenReturn("test-request-id");
        ExecuteStatementResponse stmtResponse = mock(ExecuteStatementResponse.class);
        when(stmtResponse.items()).thenReturn(Collections.emptyList());
        when(stmtResponse.nextToken()).thenReturn(null);
        when(stmtResponse.sdkHttpResponse()).thenReturn(httpResponse);
        when(stmtResponse.consumedCapacity()).thenReturn(null);
        when(stmtResponse.responseMetadata()).thenReturn(defaultResponseMetadata);
        when(mockDynamoClient.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(stmtResponse);

        client = new DynamoProviderClient(mockDynamoClient);
    }

    // ── executeScan ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("executeScan: items returned out of sort-key order are sorted ASC in QueryPage")
    void executeScanSortsBySortKeyAsc() {
        List<Map<String, AttributeValue>> rawItems = List.of(
                itemWith("pk-a", "charlie"),
                itemWith("pk-b", "alpha"),
                itemWith("pk-c", "bravo")
        );
        ScanResponse response = scanResponseWith(rawItems);
        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(response);

        QueryPage page = client.query(
                new ResourceAddress("testdb", "items"),
                QueryRequest.builder().build(), // no expression, no partitionKey → executeScan
                null);

        List<String> sortKeys = page.items().stream()
                .map(item -> (String) item.get(DynamoConstants.ATTR_SORT_KEY))
                .toList();
        assertEquals(List.of("alpha", "bravo", "charlie"), sortKeys,
                "executeScan items must be sorted by sortKey ASC; got: " + sortKeys);
    }

    @Test
    @DisplayName("executeScan: items with null sortKey are sorted before non-null items")
    void executeScanNullSortKeysSortedFirst() {
        List<Map<String, AttributeValue>> rawItems = List.of(
                itemWith("pk-b", "bravo"),
                itemWithoutSortKey("pk-a"),
                itemWith("pk-c", "alpha")
        );
        ScanResponse response = scanResponseWith(rawItems);
        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(response);

        QueryPage page = client.query(
                new ResourceAddress("testdb", "items"),
                QueryRequest.builder().build(),
                null);

        List<String> sortKeys = page.items().stream()
                .map(item -> (String) item.get(DynamoConstants.ATTR_SORT_KEY))
                .toList();
        // null sorts first, then alphabetically
        assertNull(sortKeys.get(0), "Item with null sortKey should sort first");
        assertEquals("alpha", sortKeys.get(1));
        assertEquals("bravo", sortKeys.get(2));
    }

    // ── executeScanWithFilter ────────────────────────────────────────────────

    @Test
    @DisplayName("executeScanWithFilter: items returned out of sort-key order are sorted ASC in QueryPage")
    void executeScanWithFilterSortsBySortKeyAsc() {
        List<Map<String, AttributeValue>> rawItems = List.of(
                itemWith("pk-z", "zebra"),
                itemWith("pk-m", "mango"),
                itemWith("pk-a", "apple")
        );
        ScanResponse response = scanResponseWith(rawItems);
        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(response);

        // Legacy :param-style expression routes to executeScanWithFilter when no partitionKey
        QueryPage page = client.query(
                new ResourceAddress("testdb", "items"),
                QueryRequest.builder()
                        .expression("category = :cat")
                        .parameters(Map.of(":cat", "fruit"))
                        .build(),
                null);

        List<String> sortKeys = page.items().stream()
                .map(item -> (String) item.get(DynamoConstants.ATTR_SORT_KEY))
                .toList();
        assertEquals(List.of("apple", "mango", "zebra"), sortKeys,
                "executeScanWithFilter items must be sorted by sortKey ASC; got: " + sortKeys);
    }

    // ── queryWithTranslation ─────────────────────────────────────────────────

    @Test
    @DisplayName("queryWithTranslation: items returned out of sort-key order are sorted ASC in QueryPage")
    void queryWithTranslationSortsBySortKeyAsc() {
        List<Map<String, AttributeValue>> rawItems = List.of(
                itemWith("pk-z", "zebra"),
                itemWith("pk-a", "apple"),
                itemWith("pk-m", "mango")
        );
        ExecuteStatementResponse response = executeStatementResponseWith(rawItems);
        when(mockDynamoClient.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(response);

        // Call queryWithTranslation() directly — this is the code path reached when
        // DefaultMulticloudDbClient routes a portable expression through the translator.
        TranslatedQuery translated = TranslatedQuery.withPositionalParameters(
                "SELECT * FROM items WHERE category = ?", "category = ?", List.of("books"));
        QueryPage page = client.queryWithTranslation(
                new ResourceAddress("testdb", "items"),
                translated,
                QueryRequest.builder().build(),
                null);

        List<String> sortKeys = page.items().stream()
                .map(item -> (String) item.get(DynamoConstants.ATTR_SORT_KEY))
                .toList();
        assertEquals(List.of("apple", "mango", "zebra"), sortKeys,
                "queryWithTranslation items must be sorted by sortKey ASC; got: " + sortKeys);
    }

    @Test
    @DisplayName("executeScan: numeric sort keys sort by value (2 < 10 < 100), not lexicographic (10 < 100 < 2)")
    void executeScanNumericSortKeysSortByValue() {
        List<Map<String, AttributeValue>> rawItems = List.of(
                itemWithNumericSortKey("pk-a", 100),
                itemWithNumericSortKey("pk-b", 2),
                itemWithNumericSortKey("pk-c", 10)
        );
        ScanResponse response = scanResponseWith(rawItems);
        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(response);

        QueryPage page = client.query(
                new ResourceAddress("testdb", "items"),
                QueryRequest.builder().build(),
                null);

        List<Object> sortKeys = page.items().stream()
                .map(item -> item.get(DynamoConstants.ATTR_SORT_KEY))
                .toList();
        // Numeric sort: 2, 10, 100 — not lexicographic "10", "100", "2"
        assertEquals(List.of(2.0, 10.0, 100.0),
                sortKeys.stream().map(k -> ((Number) k).doubleValue()).toList(),
                "Numeric sort keys must sort by value; got: " + sortKeys);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, AttributeValue> itemWith(String partitionKey, String sortKey) {
        return Map.of(
                DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(partitionKey),
                DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(sortKey)
        );
    }

    private static Map<String, AttributeValue> itemWithoutSortKey(String partitionKey) {
        return Map.of(
                DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(partitionKey)
        );
    }

    private static ScanResponse scanResponseWith(List<Map<String, AttributeValue>> items) {
        SdkHttpResponse httpResponse = mock(SdkHttpResponse.class);
        when(httpResponse.firstMatchingHeader(any(String.class))).thenReturn(Optional.empty());

        ScanResponse response = mock(ScanResponse.class);
        when(response.items()).thenReturn(items);
        when(response.lastEvaluatedKey()).thenReturn(Collections.emptyMap());
        when(response.sdkHttpResponse()).thenReturn(httpResponse);
        when(response.consumedCapacity()).thenReturn(null);
        return response;
    }

    private static ExecuteStatementResponse executeStatementResponseWith(
            List<Map<String, AttributeValue>> items) {
        SdkHttpResponse httpResponse = mock(SdkHttpResponse.class);
        when(httpResponse.firstMatchingHeader(any(String.class))).thenReturn(Optional.empty());

        DynamoDbResponseMetadata responseMetadata = mock(DynamoDbResponseMetadata.class);
        when(responseMetadata.requestId()).thenReturn("test-request-id");

        ExecuteStatementResponse response = mock(ExecuteStatementResponse.class);
        when(response.items()).thenReturn(items);
        when(response.nextToken()).thenReturn(null);
        when(response.sdkHttpResponse()).thenReturn(httpResponse);
        when(response.consumedCapacity()).thenReturn(null);
        when(response.responseMetadata()).thenReturn(responseMetadata);
        return response;
    }

    private static Map<String, AttributeValue> itemWithNumericSortKey(
            String partitionKey, double sortKey) {
        return Map.of(
                DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(partitionKey),
                DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromN(String.valueOf(sortKey))
        );
    }
}
