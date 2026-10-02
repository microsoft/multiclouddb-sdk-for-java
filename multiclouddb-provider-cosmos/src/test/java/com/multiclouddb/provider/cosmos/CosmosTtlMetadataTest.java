// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CosmosTtlMetadataTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private CosmosClient sdk;
    private CosmosContainer container;
    private CosmosItemResponse<ObjectNode> response;
    private ObjectNode raw;
    private MockedConstruction<CosmosClientBuilder> builders;
    private CosmosProviderClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sdk = mock(CosmosClient.class);
        CosmosDatabase db = mock(CosmosDatabase.class);
        container = mock(CosmosContainer.class);
        when(sdk.getDatabase(anyString())).thenReturn(db);
        when(db.getContainer(anyString())).thenReturn(container);
        response = mock(CosmosItemResponse.class);
        raw = JsonNodeFactory.instance.objectNode().put("id", "sk")
                .put("_ts", 1700000000L).put("_etag", "etag").put("value", 1);
        when(response.getItem()).thenReturn(raw);
        when(response.getETag()).thenReturn("etag");
        when(container.createItem(any(ObjectNode.class), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(response);
        when(container.replaceItem(any(ObjectNode.class), anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(response);
        when(container.upsertItem(any(ObjectNode.class), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(response);
        when(container.readItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class))).thenReturn(response);
        builders = mockConstruction(
                CosmosClientBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.buildClient()).thenReturn(sdk));
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS).connection("endpoint", "https://example.invalid")
                .connection("key", "unused-mocked-key").build();
        client = new CosmosProviderClient(config);
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) {
                client.close();
                verify(sdk).close();
            }
        } finally {
            if (builders != null) {
                builders.close();
            }
        }
    }

    @Test
    void ttlWritesPreserveDocumentIdentityAndRequestRouting() {
        OperationOptions ttl = OperationOptions.builder().ttlSeconds(3600).build();
        client.create(ADDRESS, KEY, Map.of("value", 1), ttl);
        client.update(ADDRESS, KEY, Map.of("value", 2), ttl);
        client.upsert(ADDRESS, KEY, Map.of("value", 3), ttl);
        ArgumentCaptor<ObjectNode> written = ArgumentCaptor.forClass(ObjectNode.class);
        PartitionKey expectedPartition = new PartitionKey("pk");
        verify(container).createItem(written.capture(), eq(expectedPartition),
                any(CosmosItemRequestOptions.class));
        verify(container).replaceItem(written.capture(), eq("sk"), eq(expectedPartition),
                any(CosmosItemRequestOptions.class));
        verify(container).upsertItem(written.capture(), eq(expectedPartition),
                any(CosmosItemRequestOptions.class));
        for (ObjectNode doc : written.getAllValues()) {
            assertEquals(3600, doc.get(CosmosConstants.FIELD_TTL).intValue());
            assertEquals("pk", doc.get("partitionKey").textValue());
            assertEquals("sk", doc.get("id").textValue());
        }
    }

    @Test
    void metadataIsOptInAndExposesTimestampAndEtag() {
        assertNull(client.read(ADDRESS, KEY, null).metadata());
        DocumentResult result = client.read(ADDRESS, KEY,
                OperationOptions.builder().includeMetadata(true).build());
        assertEquals(Instant.ofEpochSecond(1700000000L), result.metadata().lastModified());
        assertEquals("etag", result.metadata().version());
        assertNull(result.metadata().ttlExpiry());
        when(response.getItem()).thenReturn(null);
        assertNull(client.read(ADDRESS, KEY, OperationOptions.builder().ttlSeconds(3600).build()));
    }

    @Test
    void systemFieldsAreStrippedWithoutMutatingResponse() {
        ObjectNode original = raw.deepCopy();
        DocumentResult result = client.read(ADDRESS, KEY,
                OperationOptions.builder().includeMetadata(true).build());
        assertFalse(result.document().has("_ts"));
        assertFalse(result.document().has("_etag"));
        assertTrue(raw.has("_ts"));
        assertEquals(original, raw);
    }
}
