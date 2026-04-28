// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlQuerySpec;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Cosmos DB read-consistency configuration in
 * {@link CosmosProviderClient}.
 * <p>
 * Two groups of tests:
 * <ol>
 *   <li><b>{@code buildReadOptions} helper</b> — pure-function tests that verify
 *       the correct {@link CosmosItemRequestOptions} is produced for each
 *       combination of configured / unconfigured consistency level.</li>
 *   <li><b>Constructor / builder tests</b> — verify that
 *       {@link CosmosClientBuilder#consistencyLevel} is <em>never</em> called,
 *       regardless of configuration, because consistency overrides are applied
 *       per read-request rather than at client level.</li>
 * </ol>
 */
class CosmosConsistencyTest {

    private static final String DUMMY_ENDPOINT = "https://example.documents.azure.com:443/";
    private static final String DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    // ── buildReadOptions helper ───────────────────────────────────────────────

    @Test
    @DisplayName("buildReadOptions(null): options carry no consistency override")
    void buildReadOptionsNullConsistency() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(null);
        assertNotNull(opts);
        assertNull(opts.getConsistencyLevel(),
                "No consistency should be set when override is null");
    }

    @Test
    @DisplayName("buildReadOptions(SESSION): options carry SESSION override")
    void buildReadOptionsSession() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(ConsistencyLevel.SESSION);
        assertEquals(ConsistencyLevel.SESSION, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(EVENTUAL): options carry EVENTUAL override")
    void buildReadOptionsEventual() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(ConsistencyLevel.EVENTUAL);
        assertEquals(ConsistencyLevel.EVENTUAL, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(STRONG): options carry STRONG override")
    void buildReadOptionsStrong() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(ConsistencyLevel.STRONG);
        assertEquals(ConsistencyLevel.STRONG, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(BOUNDED_STALENESS): options carry BOUNDED_STALENESS override")
    void buildReadOptionsBoundedStaleness() {
        CosmosItemRequestOptions opts =
                CosmosProviderClient.buildReadOptions(ConsistencyLevel.BOUNDED_STALENESS);
        assertEquals(ConsistencyLevel.BOUNDED_STALENESS, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(CONSISTENT_PREFIX): options carry CONSISTENT_PREFIX override")
    void buildReadOptionsConsistentPrefix() {
        CosmosItemRequestOptions opts =
                CosmosProviderClient.buildReadOptions(ConsistencyLevel.CONSISTENT_PREFIX);
        assertEquals(ConsistencyLevel.CONSISTENT_PREFIX, opts.getConsistencyLevel());
    }

    // ── Constructor / CosmosClientBuilder verification ────────────────────────

    /**
     * Shared builder-mock setup. Returns the default answer that makes fluent
     * builder methods return {@code this} (the mock), avoiding NPEs on chained calls.
     */
    private MockedConstruction.MockInitializer<CosmosClientBuilder> builderDefaultAnswer() {
        return (mock, ctx) -> {
            when(mock.endpoint(anyString())).thenReturn(mock);
            when(mock.key(anyString())).thenReturn(mock);
            when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
            when(mock.gatewayMode()).thenReturn(mock);
            when(mock.directMode()).thenReturn(mock);
            when(mock.userAgentSuffix(anyString())).thenReturn(mock);
            when(mock.consistencyLevel(any())).thenReturn(mock);
            // Explicit no-op client stub: construction-only tests don't invoke operations,
            // but stubbing explicitly prevents silent NullPointerException if a future test
            // accidentally calls an operation after construction.
            CosmosClient noOpClient = mock(CosmosClient.class);
            when(mock.buildClient()).thenReturn(noOpClient);
        };
    }

    /**
     * Builder mock setup that makes {@code buildClient()} return the supplied client mock.
     * Composes from {@link #builderDefaultAnswer()} to avoid duplicating the 7 shared stubs —
     * if a new builder call is added to the constructor, only {@code builderDefaultAnswer} needs updating.
     */
    private MockedConstruction.MockInitializer<CosmosClientBuilder> builderAnswerWithClient(CosmosClient client) {
        return (mock, ctx) -> {
            builderDefaultAnswer().prepare(mock, ctx);
            when(mock.buildClient()).thenReturn(client);
        };
    }

    @Test
    @DisplayName("No consistencyLevel in config: CosmosClientBuilder.consistencyLevel() is never called")
    void noConsistencyConfigDoesNotCallBuilderConsistencyLevel() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .build();

        try (MockedConstruction<CosmosClientBuilder> mocked =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {

            new CosmosProviderClient(config);

            List<CosmosClientBuilder> builders = mocked.constructed();
            assertEquals(1, builders.size());
            verify(builders.get(0), never()).consistencyLevel(any());
        }
    }

    @Test
    @DisplayName("consistencyLevel=SESSION in config: CosmosClientBuilder.consistencyLevel() is still never called (per-request override, not client-level)")
    void consistencyConfigDoesNotSetClientLevelConsistency() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "SESSION")
                .build();

        try (MockedConstruction<CosmosClientBuilder> mocked =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {

            new CosmosProviderClient(config);

            verify(mocked.constructed().get(0), never()).consistencyLevel(any());
        }
    }

    @Test
    @DisplayName("consistencyLevel=EVENTUAL in config: construction succeeds")
    void validConsistencyConfigEventualSucceeds() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            assertDoesNotThrow(() -> new CosmosProviderClient(config));
        }
    }

    @Test
    @DisplayName("consistencyLevel=invalid in config: construction throws IllegalArgumentException with informative message")
    void invalidConsistencyConfigThrowsOnConstruction() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "LINEARIZABLE")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CosmosProviderClient(config));
            assertTrue(ex.getMessage().contains("LINEARIZABLE"),
                    "Error message should include the bad value; got: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("consistencyLevel config is case-insensitive (lowercase 'eventual' accepted)")
    void consistencyConfigCaseInsensitive() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "eventual")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            assertDoesNotThrow(() -> new CosmosProviderClient(config));
        }
    }

    // ── Blank config validation ───────────────────────────────────────────────

    @Test
    @DisplayName("blank consistencyLevel in config: construction throws IllegalArgumentException (fail-fast)")
    void blankConsistencyLevelThrowsOnConstruction() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "   ")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CosmosProviderClient(config),
                    "Blank consistencyLevel should throw at construction, not silently use account default");
        }
    }

    // ── Operation-level consistency propagation ───────────────────────────────

    @Test
    @DisplayName("read() with EVENTUAL override: readItem is called with CosmosItemRequestOptions carrying EVENTUAL")
    @SuppressWarnings("unchecked")
    void readPassesConsistencyLevelToReadItem() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        CosmosContainer mockContainer = mock(CosmosContainer.class);
        CosmosDatabase mockDatabase = mock(CosmosDatabase.class);
        CosmosClient mockClient = mock(CosmosClient.class);
        when(mockClient.getDatabase(anyString())).thenReturn(mockDatabase);
        when(mockDatabase.getContainer(anyString())).thenReturn(mockContainer);

        CosmosItemResponse<ObjectNode> mockResponse = mock(CosmosItemResponse.class);
        when(mockResponse.getItem()).thenReturn(null);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockContainer.readItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class)))
                .thenReturn(mockResponse);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            MulticloudDbKey key = MulticloudDbKey.of("partition1");
            providerClient.read(address, key, null);

            ArgumentCaptor<CosmosItemRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
            verify(mockContainer).readItem(anyString(), any(PartitionKey.class),
                    captor.capture(), eq(ObjectNode.class));
            assertEquals(ConsistencyLevel.EVENTUAL, captor.getValue().getConsistencyLevel(),
                    "read() must pass EVENTUAL consistency override to readItem");
        }
    }

    @Test
    @DisplayName("read() with no override: readItem is called with CosmosItemRequestOptions carrying null consistency (account default)")
    @SuppressWarnings("unchecked")
    void readWithNoOverrideUsesNullConsistencyInOptions() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .build();

        CosmosContainer mockContainer = mock(CosmosContainer.class);
        CosmosDatabase mockDatabase = mock(CosmosDatabase.class);
        CosmosClient mockClient = mock(CosmosClient.class);
        when(mockClient.getDatabase(anyString())).thenReturn(mockDatabase);
        when(mockDatabase.getContainer(anyString())).thenReturn(mockContainer);

        CosmosItemResponse<ObjectNode> mockResponse = mock(CosmosItemResponse.class);
        when(mockResponse.getItem()).thenReturn(null);
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockContainer.readItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class)))
                .thenReturn(mockResponse);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            MulticloudDbKey key = MulticloudDbKey.of("partition1");
            providerClient.read(address, key, null);

            // 4-arg overload is always used; when no override is configured, consistency is null (account default)
            ArgumentCaptor<CosmosItemRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
            verify(mockContainer).readItem(anyString(), any(PartitionKey.class),
                    captor.capture(), eq(ObjectNode.class));
            assertNull(captor.getValue().getConsistencyLevel(),
                    "read() must not set a consistency level when no override is configured");
        }
    }

    @Test
    @DisplayName("query() with EVENTUAL override: queryItems is called with CosmosQueryRequestOptions carrying EVENTUAL")
    @SuppressWarnings("unchecked")
    void queryAppliesConsistencyLevelToQueryOptions() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        CosmosContainer mockContainer = mock(CosmosContainer.class);
        CosmosDatabase mockDatabase = mock(CosmosDatabase.class);
        CosmosClient mockClient = mock(CosmosClient.class);
        when(mockClient.getDatabase(anyString())).thenReturn(mockDatabase);
        when(mockDatabase.getContainer(anyString())).thenReturn(mockContainer);

        CosmosPagedIterable<com.fasterxml.jackson.databind.JsonNode> mockPagedIterable =
                mock(CosmosPagedIterable.class);
        when(mockPagedIterable.iterableByPage(anyInt())).thenReturn(Collections.emptyList());
        when(mockPagedIterable.iterableByPage(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(mockContainer.queryItems(any(SqlQuerySpec.class),
                any(CosmosQueryRequestOptions.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(mockPagedIterable);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            QueryRequest query = QueryRequest.builder().expression("c.id = '1'").build();
            providerClient.query(address, query, null);

            ArgumentCaptor<CosmosQueryRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosQueryRequestOptions.class);
            verify(mockContainer).queryItems(any(SqlQuerySpec.class),
                    captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
            assertEquals(ConsistencyLevel.EVENTUAL, captor.getValue().getConsistencyLevel(),
                    "query() must set EVENTUAL consistency on CosmosQueryRequestOptions");
        }
    }

    @Test
    @DisplayName("queryWithTranslation() with EVENTUAL override: queryItems is called with CosmosQueryRequestOptions carrying EVENTUAL")
    @SuppressWarnings("unchecked")
    void queryWithTranslationAppliesConsistencyLevelToQueryOptions() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        CosmosContainer mockContainer = mock(CosmosContainer.class);
        CosmosDatabase mockDatabase = mock(CosmosDatabase.class);
        CosmosClient mockClient = mock(CosmosClient.class);
        when(mockClient.getDatabase(anyString())).thenReturn(mockDatabase);
        when(mockDatabase.getContainer(anyString())).thenReturn(mockContainer);

        CosmosPagedIterable<com.fasterxml.jackson.databind.JsonNode> mockPagedIterable =
                mock(CosmosPagedIterable.class);
        when(mockPagedIterable.iterableByPage(anyInt())).thenReturn(Collections.emptyList());
        when(mockPagedIterable.iterableByPage(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(mockContainer.queryItems(any(SqlQuerySpec.class),
                any(CosmosQueryRequestOptions.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(mockPagedIterable);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");

            com.multiclouddb.api.query.TranslatedQuery translated =
                    com.multiclouddb.api.query.TranslatedQuery.withNamedParameters(
                            "SELECT * FROM c WHERE c.id = @id ORDER BY c.id ASC",
                            "c.id = @id",
                            Map.of("@id", "1"));
            QueryRequest query = QueryRequest.builder().build();
            providerClient.queryWithTranslation(address, translated, query, null);

            ArgumentCaptor<CosmosQueryRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosQueryRequestOptions.class);
            verify(mockContainer).queryItems(any(SqlQuerySpec.class),
                    captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
            assertEquals(ConsistencyLevel.EVENTUAL, captor.getValue().getConsistencyLevel(),
                    "queryWithTranslation() must set EVENTUAL consistency on CosmosQueryRequestOptions");
        }
    }

    @Test
    @DisplayName("query() with no override: CosmosQueryRequestOptions.getConsistencyLevel() returns null")
    @SuppressWarnings("unchecked")
    void queryWithNoOverrideDoesNotSetConsistencyLevel() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .build();

        CosmosContainer mockContainer = mock(CosmosContainer.class);
        CosmosDatabase mockDatabase = mock(CosmosDatabase.class);
        CosmosClient mockClient = mock(CosmosClient.class);
        when(mockClient.getDatabase(anyString())).thenReturn(mockDatabase);
        when(mockDatabase.getContainer(anyString())).thenReturn(mockContainer);

        CosmosPagedIterable<com.fasterxml.jackson.databind.JsonNode> mockPagedIterable =
                mock(CosmosPagedIterable.class);
        when(mockPagedIterable.iterableByPage(anyInt())).thenReturn(Collections.emptyList());
        when(mockPagedIterable.iterableByPage(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(mockContainer.queryItems(any(SqlQuerySpec.class),
                any(CosmosQueryRequestOptions.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(mockPagedIterable);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            QueryRequest query = QueryRequest.builder().expression("c.id = '1'").build();
            providerClient.query(address, query, null);

            ArgumentCaptor<CosmosQueryRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosQueryRequestOptions.class);
            verify(mockContainer).queryItems(any(SqlQuerySpec.class),
                    captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
            assertNull(captor.getValue().getConsistencyLevel(),
                    "query() must not set a consistency level when no override is configured");
        }
    }

    @Test
    @DisplayName("queryWithTranslation() with no override: CosmosQueryRequestOptions.getConsistencyLevel() returns null")
    @SuppressWarnings("unchecked")
    void queryWithTranslationWithNoOverrideDoesNotSetConsistencyLevel() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .build();

        CosmosContainer mockContainer = mock(CosmosContainer.class);
        CosmosDatabase mockDatabase = mock(CosmosDatabase.class);
        CosmosClient mockClient = mock(CosmosClient.class);
        when(mockClient.getDatabase(anyString())).thenReturn(mockDatabase);
        when(mockDatabase.getContainer(anyString())).thenReturn(mockContainer);

        CosmosPagedIterable<com.fasterxml.jackson.databind.JsonNode> mockPagedIterable =
                mock(CosmosPagedIterable.class);
        when(mockPagedIterable.iterableByPage(anyInt())).thenReturn(Collections.emptyList());
        when(mockPagedIterable.iterableByPage(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(mockContainer.queryItems(any(SqlQuerySpec.class),
                any(CosmosQueryRequestOptions.class),
                eq(com.fasterxml.jackson.databind.JsonNode.class)))
                .thenReturn(mockPagedIterable);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");

            com.multiclouddb.api.query.TranslatedQuery translated =
                    com.multiclouddb.api.query.TranslatedQuery.withNamedParameters(
                            "SELECT * FROM c WHERE c.id = @id ORDER BY c.id ASC",
                            "c.id = @id",
                            Map.of("@id", "1"));
            QueryRequest query = QueryRequest.builder().build();
            providerClient.queryWithTranslation(address, translated, query, null);

            ArgumentCaptor<CosmosQueryRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosQueryRequestOptions.class);
            verify(mockContainer).queryItems(any(SqlQuerySpec.class),
                    captor.capture(), eq(com.fasterxml.jackson.databind.JsonNode.class));
            assertNull(captor.getValue().getConsistencyLevel(),
                    "queryWithTranslation() must not set a consistency level when no override is configured");
        }
    }
}
