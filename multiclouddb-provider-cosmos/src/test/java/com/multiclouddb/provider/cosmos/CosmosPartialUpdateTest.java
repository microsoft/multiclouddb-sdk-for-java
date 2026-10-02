// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosPatchItemRequestOptions;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CosmosPartialUpdateTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "item");

    private CosmosClient cosmosClient;
    private CosmosContainer container;
    private CosmosProviderClient provider;

    @BeforeEach
    void setUp() {
        cosmosClient = mock(CosmosClient.class);
        CosmosDatabase database = mock(CosmosDatabase.class);
        container = mock(CosmosContainer.class);
        when(cosmosClient.getDatabase("db")).thenReturn(database);
        when(database.getContainer("items")).thenReturn(container);
        provider = new CosmosProviderClient(cosmosClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void directUpdateUsesOnePatchAndNoReadOrReplace() {
        CosmosItemResponse<ObjectNode> response = mock(CosmosItemResponse.class);
        when(response.getStatusCode()).thenReturn(200);
        when(container.patchItem(anyString(), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenReturn(response);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "SHIPPED");
        fields.put("profile", Map.of("name", "Ada"));
        fields.put("tags", List.of("priority"));
        fields.put("closedAt", null);

        provider.update(ADDRESS, KEY, fields, OperationOptions.defaults());

        ArgumentCaptor<CosmosPatchItemRequestOptions> options =
                ArgumentCaptor.forClass(CosmosPatchItemRequestOptions.class);
        verify(container).patchItem(eq("item"), any(PartitionKey.class),
                any(CosmosPatchOperations.class), options.capture(), eq(ObjectNode.class));
        assertNull(options.getValue().getConsistencyLevel());
        verifyNoMoreInteractions(container);
    }

    @Test
    void moreThanTenFieldsAreRejectedWithoutCosmosCall() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> provider.update(ADDRESS, KEY, fields(11), OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertEquals("partial_update_field_count_limit",
                ex.error().providerDetails().get("reason"));
        verifyNoInteractions(cosmosClient);
    }

    @Test
    void directNotFoundIsNormalized() {
        CosmosException failure = mock(CosmosException.class);
        when(failure.getStatusCode()).thenReturn(404);
        when(failure.getMessage()).thenReturn("missing");
        when(container.patchItem(anyString(), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenThrow(failure);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> provider.update(ADDRESS, KEY, Map.of("status", "SHIPPED"),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, ex.error().category());
        assertFalse(ex.error().retryable());
        assertSame(failure, ex.getCause());
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {408, 410})
    void nativeTimeoutMapsThroughProviderUpdate(int statusCode) {
        CosmosException failure = mock(CosmosException.class);
        when(failure.getStatusCode()).thenReturn(statusCode);
        when(failure.getSubStatusCode()).thenReturn(1002);
        when(failure.getMessage()).thenReturn("timeout");
        when(failure.getActivityId()).thenReturn("timeout-request");
        when(failure.getRequestCharge()).thenReturn(0.0);
        when(container.patchItem(anyString(), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenThrow(failure);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> provider.update(ADDRESS, KEY, Map.of("status", "SHIPPED"),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.TRANSIENT_FAILURE, ex.error().category());
        assertTrue(ex.error().retryable());
        assertEquals(statusCode, ex.error().statusCode());
        assertEquals("1002", ex.error().providerDetails().get("subStatusCode"));
        assertEquals("timeout-request", ex.error().providerDetails().get("requestId"));
        assertSame(failure, ex.getCause());
        verify(container).patchItem(eq("item"), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class));
        verifyNoMoreInteractions(container);
    }

    private static Map<String, Object> fields(int count) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            fields.put("field" + i, i);
        }
        return fields;
    }
}
