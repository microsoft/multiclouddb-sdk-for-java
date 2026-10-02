// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosPatchItemRequestOptions;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.provider.cosmos.CosmosProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import java.lang.reflect.Constructor;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("cosmos")
@Tag("emulator")
@DisplayName("Cosmos partial-update timeout conformance")
public class CosmosPartialUpdateTimeoutConformanceTest
        extends PartialUpdateTimeoutConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    protected Stream<TimeoutScenario> timeoutScenarios() {
        return Stream.of(cosmosTimeout(408, 0), cosmosTimeout(410, 1002));
    }

    private static TimeoutScenario cosmosTimeout(int statusCode, int subStatusCode) {
        CosmosClient nativeClient = mock(CosmosClient.class);
        CosmosDatabase database = mock(CosmosDatabase.class);
        CosmosContainer container = mock(CosmosContainer.class);
        CosmosException failure = mock(CosmosException.class);

        when(nativeClient.getDatabase("db")).thenReturn(database);
        when(database.getContainer("items")).thenReturn(container);
        when(failure.getStatusCode()).thenReturn(statusCode);
        when(failure.getSubStatusCode()).thenReturn(subStatusCode);
        when(failure.getMessage()).thenReturn("Cosmos timeout " + statusCode);
        when(failure.getActivityId()).thenReturn("cosmos-timeout-request");
        when(failure.getRequestCharge()).thenReturn(0.0);
        when(container.patchItem(anyString(), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenThrow(failure);

        return new TimeoutScenario(
                "Cosmos HTTP " + statusCode,
                providerFrom(nativeClient),
                failure,
                () -> verify(container).patchItem(eq("item"), any(PartitionKey.class),
                        any(CosmosPatchOperations.class),
                        any(CosmosPatchItemRequestOptions.class), eq(ObjectNode.class)),
                result -> {
                    assertEquals(statusCode, result.error().statusCode());
                    assertEquals(String.valueOf(subStatusCode),
                            result.error().providerDetails().get("subStatusCode"));
                    assertEquals("cosmos-timeout-request",
                            result.error().providerDetails().get("requestId"));
                });
    }

    private static CosmosProviderClient providerFrom(CosmosClient nativeClient) {
        try {
            Constructor<CosmosProviderClient> constructor =
                    CosmosProviderClient.class.getDeclaredConstructor(CosmosClient.class);
            constructor.setAccessible(true);
            return constructor.newInstance(nativeClient);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct Cosmos provider test adapter", e);
        }
    }
}
