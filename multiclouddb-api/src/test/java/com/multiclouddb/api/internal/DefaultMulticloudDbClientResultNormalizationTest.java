// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentMetadata;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMulticloudDbClientResultNormalizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "collection");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("partition", "sort");

    @Test
    void readRemovesProviderOwnedTopLevelFieldsWithoutMutatingProviderResult() {
        ObjectNode nested = MAPPER.createObjectNode()
                .put("id", "nested-id")
                .put("_etag", "nested-etag");
        ObjectNode providerDocument = MAPPER.createObjectNode()
                .put("ID", "provider-id")
                .put("PartitionKEY", "partition")
                .put("sortkey", "sort")
                .put("TTL", 60)
                .put("ttlEXPIRY", "later")
                .put("DATA", "metadata")
                .put("_etag", "etag")
                .put("name", "Ada");
        providerDocument.set("nested", nested);
        DocumentMetadata metadata = DocumentMetadata.builder()
                .lastModified(Instant.EPOCH)
                .version("v1")
                .build();
        RecordingProvider provider = new RecordingProvider(
                new DocumentResult(providerDocument, metadata), null);

        DocumentResult result = client(provider).read(ADDRESS, KEY);

        assertNotSame(providerDocument, result.document());
        assertEquals(2, result.document().size());
        assertEquals("Ada", result.document().path("name").textValue());
        assertEquals("nested-id", result.document().path("nested").path("id").textValue());
        assertEquals("nested-etag", result.document().path("nested").path("_etag").textValue());
        assertSame(nested, result.document().get("nested"));
        assertSame(metadata, result.metadata());
        assertTrue(providerDocument.has("PartitionKEY"));
        assertTrue(providerDocument.has("_etag"));

        result.document().put("name", "Grace");
        assertEquals("Ada", providerDocument.path("name").textValue());
    }

    @Test
    void missingReadResultRemainsMissing() {
        RecordingProvider provider = new RecordingProvider(null, null);

        assertNull(client(provider).read(ADDRESS, KEY));
    }

    @Test
    void queryRemovesProviderOwnedFieldsAndPreservesPageMetadata() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("sortKey", "nested-sort");
        nested.put("_ts", 123L);
        Map<String, Object> providerItem = new LinkedHashMap<>();
        providerItem.put("partitionkey", "partition");
        providerItem.put("SortKey", "sort");
        providerItem.put("Id", "id");
        providerItem.put("ttl", 60);
        providerItem.put("TTLExpiry", "later");
        providerItem.put("Data", "metadata");
        providerItem.put("_rid", "rid");
        providerItem.put("nullable", null);
        providerItem.put("nested", nested);
        OperationDiagnostics diagnostics = new OperationDiagnostics(
                ProviderId.SPANNER, "query", Duration.ofMillis(3), "request");
        QueryPage providerPage = new QueryPage(List.of(providerItem), "next", diagnostics);
        RecordingProvider provider = new RecordingProvider(null, providerPage);

        QueryPage result = client(provider).query(ADDRESS, QueryRequest.builder().build());

        assertEquals(List.of("nullable", "nested"), List.copyOf(result.items().get(0).keySet()));
        assertTrue(result.items().get(0).containsKey("nullable"));
        assertSame(nested, result.items().get(0).get("nested"));
        assertEquals("next", result.continuationToken());
        assertSame(diagnostics, result.diagnostics());
        assertTrue(providerItem.containsKey("partitionkey"));
        assertTrue(providerItem.containsKey("_rid"));
        assertTrue(providerPage.items().get(0).containsKey("SortKey"));
        assertFalse(result.items().get(0).containsKey("SortKey"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.items().get(0).put("name", "Grace"));
    }

    @Test
    void queryPreservesItemsBeforeTheFirstFilteredItem() {
        Map<String, Object> portableItem = new LinkedHashMap<>();
        portableItem.put("name", "Ada");
        Map<String, Object> filteredItem = new LinkedHashMap<>();
        filteredItem.put("_etag", "provider-etag");
        filteredItem.put("name", "Grace");
        QueryPage providerPage = new QueryPage(
                List.of(portableItem, filteredItem), null);
        RecordingProvider provider = new RecordingProvider(null, providerPage);

        QueryPage result = client(provider).query(ADDRESS, QueryRequest.builder().build());

        assertEquals(2, result.items().size());
        assertEquals("Ada", result.items().get(0).get("name"));
        assertEquals(Map.of("name", "Grace"), result.items().get(1));
        assertTrue(providerPage.items().get(1).containsKey("_etag"));
    }

    @Test
    void queryReusesPageWhenNoProviderOwnedFieldsNeedFiltering() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "Seattle");
        Map<String, Object> providerItem = new LinkedHashMap<>();
        providerItem.put("name", "Ada");
        providerItem.put("nested", nested);
        QueryPage providerPage = new QueryPage(List.of(providerItem), null);
        RecordingProvider provider = new RecordingProvider(null, providerPage);

        QueryPage result = client(provider).query(ADDRESS, QueryRequest.builder().build());

        assertSame(providerPage, result);
        assertSame(nested, result.items().get(0).get("nested"));
    }

    private static DefaultMulticloudDbClient client(RecordingProvider provider) {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(provider.providerId())
                .build();
        return new DefaultMulticloudDbClient(provider, config);
    }

    private static final class RecordingProvider implements MulticloudDbProviderClient {
        private final ProviderId providerId = ProviderId.fromId("result-normalization-test");
        private final DocumentResult readResult;
        private final QueryPage queryPage;

        private RecordingProvider(DocumentResult readResult, QueryPage queryPage) {
            this.readResult = readResult;
            this.queryPage = queryPage;
        }

        @Override public ProviderId providerId() { return providerId; }
        @Override public CapabilitySet capabilities() { return new CapabilitySet(List.of()); }
        @Override public DocumentResult read(ResourceAddress address, MulticloudDbKey key,
                OperationOptions options) { return readResult; }
        @Override public QueryPage query(ResourceAddress address, QueryRequest query,
                OperationOptions options) { return queryPage; }
        @Override public void create(ResourceAddress address, MulticloudDbKey key,
                Map<String, Object> document, OperationOptions options) {
            throw new UnsupportedOperationException();
        }
        @Override public void update(ResourceAddress address, MulticloudDbKey key,
                Map<String, Object> fields, OperationOptions options) {
            throw new UnsupportedOperationException();
        }
        @Override public void upsert(ResourceAddress address, MulticloudDbKey key,
                Map<String, Object> document, OperationOptions options) {
            throw new UnsupportedOperationException();
        }
        @Override public void delete(ResourceAddress address, MulticloudDbKey key,
                OperationOptions options) {
            throw new UnsupportedOperationException();
        }
        @Override public void close() { }
    }
}