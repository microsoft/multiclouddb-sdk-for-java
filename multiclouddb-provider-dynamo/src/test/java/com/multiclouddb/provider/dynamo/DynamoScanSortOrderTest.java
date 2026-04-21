// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
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
}
