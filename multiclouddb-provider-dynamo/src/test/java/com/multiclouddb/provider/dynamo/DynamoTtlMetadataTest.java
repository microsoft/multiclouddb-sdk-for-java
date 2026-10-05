// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoTtlMetadataTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");
    private static final DynamoDbResponseMetadata RESPONSE_METADATA = mock(DynamoDbResponseMetadata.class);

    @Test
    void allWritePathsInjectEpochExpiryOnlyWhenRequested() {
        DynamoDbClient sdk = mock(DynamoDbClient.class);
        PutItemResponse response = mock(PutItemResponse.class);
        when(response.responseMetadata()).thenReturn(RESPONSE_METADATA);
        when(sdk.putItem(any(PutItemRequest.class))).thenReturn(response);
        try (DynamoProviderClient client = new DynamoProviderClient(sdk)) {
            OperationOptions ttl = OperationOptions.builder().ttlSeconds(3600).build();
            long before = Instant.now().getEpochSecond();
            client.create(ADDRESS, KEY, Map.of("value", 1), ttl);
            client.update(ADDRESS, KEY, Map.of("value", 2), ttl);
            client.upsert(ADDRESS, KEY, Map.of("value", 3), ttl);
            client.upsert(ADDRESS, KEY, Map.of("value", 4), OperationOptions.defaults());
            long after = Instant.now().getEpochSecond();
            ArgumentCaptor<PutItemRequest> writes = ArgumentCaptor.forClass(PutItemRequest.class);
            verify(sdk, times(4)).putItem(writes.capture());
            for (PutItemRequest write : writes.getAllValues().subList(0, 3)) {
                long expiry = Long.parseLong(write.item().get(DynamoConstants.ATTR_TTL_EXPIRY).n());
                assertTrue(expiry >= before + 3600 && expiry <= after + 3600);
                assertEquals("pk", write.item().get("partitionKey").s());
                assertEquals("sk", write.item().get("sortKey").s());
            }
            assertFalse(writes.getAllValues().get(3).item().containsKey(DynamoConstants.ATTR_TTL_EXPIRY));
            assertEquals("attribute_not_exists(partitionKey)",
                    writes.getAllValues().get(0).conditionExpression());
            assertEquals("attribute_exists(partitionKey)",
                    writes.getAllValues().get(1).conditionExpression());
            assertNull(writes.getAllValues().get(2).conditionExpression());
            assertNull(writes.getAllValues().get(3).conditionExpression());
        }
    }

    @Test
    void readsExposeStoredExpiryOnlyOnMetadataOptIn() {
        DynamoDbClient sdk = mock(DynamoDbClient.class);
        GetItemResponse withExpiry = readResponse(
                Map.of("partitionKey", AttributeValue.fromS("pk"),
                        "sortKey", AttributeValue.fromS("sk"),
                        DynamoConstants.ATTR_TTL_EXPIRY, AttributeValue.fromN("1800000000")));
        GetItemResponse withoutExpiry = readResponse(Map.of("sortKey", AttributeValue.fromS("sk")));
        GetItemResponse missing = readResponse(Map.of());
        when(sdk.getItem(any(GetItemRequest.class))).thenReturn(withExpiry);
        try (DynamoProviderClient client = new DynamoProviderClient(sdk)) {
            assertNull(client.read(ADDRESS, KEY, null).metadata());
            DocumentResult result = client.read(ADDRESS, KEY,
                    OperationOptions.builder().includeMetadata(true).build());
            assertEquals(Instant.ofEpochSecond(1800000000L), result.metadata().ttlExpiry());
            assertNull(result.metadata().lastModified());
            assertNull(result.metadata().version());
            assertFalse(client.capabilities().isSupported(Capability.WRITE_TIMESTAMP));
            when(sdk.getItem(any(GetItemRequest.class))).thenReturn(withoutExpiry);
            assertNull(client.read(ADDRESS, KEY,
                    OperationOptions.builder().includeMetadata(true).build()).metadata().ttlExpiry());
            when(sdk.getItem(any(GetItemRequest.class))).thenReturn(missing);
            assertNull(client.read(ADDRESS, KEY, OperationOptions.builder().includeMetadata(true).build()));
        }
    }

    private static GetItemResponse readResponse(Map<String, AttributeValue> item) {
        GetItemResponse response = mock(GetItemResponse.class);
        when(response.responseMetadata()).thenReturn(RESPONSE_METADATA);
        when(response.item()).thenReturn(item);
        when(response.hasItem()).thenReturn(!item.isEmpty());
        return response;
    }
}
