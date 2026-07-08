// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the post-close contract for {@link CosmosProviderClient}: every
 * public entry point gated by {@code checkOpen()} must throw a typed
 * {@link MulticloudDbException} with category
 * {@link MulticloudDbErrorCategory#CLIENT_CLOSED}, rather than leaking the
 * raw {@code IllegalStateException} that {@code azure-cosmos} surfaces from
 * its own internal client once {@code CosmosClient.close()} is invoked.
 *
 * <p>The Cosmos SDK is mocked at construction time via
 * {@link MockedConstruction} of {@link CosmosClientBuilder} so the test never
 * contacts a real Cosmos DB account. Each test calls {@link CosmosProviderClient#close()}
 * once on a fresh client (the underlying {@link CosmosClient} is a mock, so
 * {@code close()} is a fast no-op) and asserts the entry point under test
 * throws {@code CLIENT_CLOSED} attributed to the caller's operation name.
 *
 * <p>This complements the conformance-level assertion in
 * {@code CrudConformanceTests.postCloseOperationsThrowClientClosed()} which
 * exercises real provider integrations.
 */
class CosmosPostCloseTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "container");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private static MulticloudDbClientConfig config() {
        return MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(Map.of(
                        CosmosConstants.CONFIG_ENDPOINT, "https://example.documents.azure.com:443/",
                        CosmosConstants.CONFIG_KEY, "dGVzdC1rZXk="))
                .build();
    }

    private static MockedConstruction.MockInitializer<CosmosClientBuilder> builderMockInit() {
        return (mock, ctx) -> {
            when(mock.endpoint(anyString())).thenReturn(mock);
            when(mock.key(anyString())).thenReturn(mock);
            when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
            when(mock.gatewayMode()).thenReturn(mock);
            when(mock.directMode()).thenReturn(mock);
            when(mock.userAgentSuffix(anyString())).thenReturn(mock);
            when(mock.consistencyLevel(any())).thenReturn(mock);
            when(mock.credential(any(TokenCredential.class))).thenReturn(mock);
            CosmosClient noOpClient = mock(CosmosClient.class);
            when(mock.buildClient()).thenReturn(noOpClient);
        };
    }

    private static void assertClientClosed(MulticloudDbException e, String expectedOperation) {
        assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, e.error().category(),
                "every post-close entry point must surface CLIENT_CLOSED, not "
                        + e.error().category());
        assertEquals(ProviderId.COSMOS, e.error().provider());
        // The operation field must carry the caller's actual operation, not the
        // generic "checkOpen" literal — telemetry/diagnostics consumers branch
        // on this to attribute post-close failures to the failing call.
        assertEquals(expectedOperation, e.error().operation(),
                "post-close error must attribute operation to the caller's op, not 'checkOpen'");
    }

    private static CosmosProviderClient closedClient() {
        CosmosProviderClient client = new CosmosProviderClient(config());
        client.close();
        return client;
    }

    @Test
    @DisplayName("create() after close() throws CLIENT_CLOSED")
    void createAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.create(ADDR, KEY, Map.of("k", "v"), null)),
                    OperationNames.CREATE);
        }
    }

    @Test
    @DisplayName("read() after close() throws CLIENT_CLOSED")
    void readAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.read(ADDR, KEY, null)),
                    OperationNames.READ);
        }
    }

    @Test
    @DisplayName("update() after close() throws CLIENT_CLOSED")
    void updateAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.update(ADDR, KEY, Map.of("k", "v"), null)),
                    OperationNames.UPDATE);
        }
    }

    @Test
    @DisplayName("upsert() after close() throws CLIENT_CLOSED")
    void upsertAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.upsert(ADDR, KEY, Map.of("k", "v"), null)),
                    OperationNames.UPSERT);
        }
    }

    @Test
    @DisplayName("delete() after close() throws CLIENT_CLOSED")
    void deleteAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.delete(ADDR, KEY, null)),
                    OperationNames.DELETE);
        }
    }

    @Test
    @DisplayName("query() after close() throws CLIENT_CLOSED")
    void queryAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            QueryRequest q = QueryRequest.builder().build();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.query(ADDR, q, null)),
                    OperationNames.QUERY);
        }
    }

    @Test
    @DisplayName("queryWithTranslation() after close() throws CLIENT_CLOSED")
    void queryWithTranslationAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            QueryRequest q = QueryRequest.builder().build();
            TranslatedQuery translated = TranslatedQuery.withNamedParameters(
                    "SELECT * FROM c", "1=1", Map.of());
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.queryWithTranslation(ADDR, translated, q, null)),
                    OperationNames.QUERY_WITH_TRANSLATION);
        }
    }

    @Test
    @DisplayName("ensureDatabase() after close() throws CLIENT_CLOSED")
    void ensureDatabaseAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.ensureDatabase("test-db")),
                    OperationNames.ENSURE_DATABASE);
        }
    }

    @Test
    @DisplayName("ensureContainer() after close() throws CLIENT_CLOSED")
    void ensureContainerAfterClose() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderMockInit())) {
            CosmosProviderClient client = closedClient();
            assertClientClosed(assertThrows(MulticloudDbException.class,
                    () -> client.ensureContainer(ADDR)),
                    OperationNames.ENSURE_CONTAINER);
        }
    }

    @Test
    @DisplayName("close() is idempotent — underlying CosmosClient.close() invoked exactly once")
    void closeIsIdempotent() {
        // Capture the underlying CosmosClient mock that the builder hands back
        // so we can verify the DCL guard in CosmosProviderClient.close() really
        // short-circuits the second invocation. The behavioural assertion
        // "calling close() twice does not throw" is necessary but not
        // sufficient: azure-cosmos's CosmosClient.close() is itself idempotent
        // at the SDK level, so a regression that drops the `if (closed) return;`
        // guard from CosmosProviderClient.close() would still let this test
        // pass without verify(times(1)).
        AtomicReference<CosmosClient> capturedCosmosClient = new AtomicReference<>();
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, (mock, ctx) -> {
                         when(mock.endpoint(anyString())).thenReturn(mock);
                         when(mock.key(anyString())).thenReturn(mock);
                         when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
                         when(mock.gatewayMode()).thenReturn(mock);
                         when(mock.directMode()).thenReturn(mock);
                         when(mock.userAgentSuffix(anyString())).thenReturn(mock);
                         when(mock.consistencyLevel(any())).thenReturn(mock);
                         when(mock.credential(any(TokenCredential.class))).thenReturn(mock);
                         CosmosClient noOpClient = mock(CosmosClient.class);
                         capturedCosmosClient.set(noOpClient);
                         when(mock.buildClient()).thenReturn(noOpClient);
                     })) {
            CosmosProviderClient client = new CosmosProviderClient(config());
            client.close();
            // Second close must be a no-op — the underlying cosmosClient.close()
            // must NOT be invoked a second time (would otherwise risk NPE /
            // IllegalStateException from a doubly-closed Azure SDK client and,
            // more importantly, break the cross-provider idempotency contract
            // exercised by CrudConformanceTests.closeIsIdempotent).
            client.close();
        }
        verify(capturedCosmosClient.get(), times(1)).close();
    }
}
