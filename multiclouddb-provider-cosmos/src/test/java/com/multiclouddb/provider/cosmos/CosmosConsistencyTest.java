// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
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
 *   <li><b>Constructor / builder tests</b> — verify that
 *       {@link CosmosClientBuilder#consistencyLevel} is called with the configured
 *       value when a consistency level is specified, and is never called when no
 *       consistency level is configured.</li>
 *   <li><b>Operation-level invariants</b> — verify that per-request
 *       {@link CosmosItemRequestOptions} and {@link CosmosQueryRequestOptions} never
 *       carry a consistency level (reads rely on the client-level setting; writes must
 *       never carry a per-request consistency override regardless of configuration).</li>
 * </ol>
 */
class CosmosConsistencyTest {

    private static final String DUMMY_ENDPOINT = "https://example.documents.azure.com:443/";
    private static final String DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

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
            when(mock.credential(any(TokenCredential.class))).thenReturn(mock);
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
    @DisplayName("consistencyLevel=SESSION in config: CosmosClientBuilder.consistencyLevel(SESSION) is called before buildClient()")
    void consistencyConfigCallsBuilderConsistencyLevel() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "SESSION")
                .build();

        try (MockedConstruction<CosmosClientBuilder> mocked =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {

            new CosmosProviderClient(config);

            CosmosClientBuilder builder = mocked.constructed().get(0);
            org.mockito.InOrder order = inOrder(builder);
            order.verify(builder).consistencyLevel(ConsistencyLevel.SESSION);
            order.verify(builder).buildClient();
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
    @DisplayName("No key + consistencyLevel=EVENTUAL: construction succeeds and CosmosClientBuilder.consistencyLevel(EVENTUAL) is called")
    void noKeyWithConsistencyLevelUsesDefaultAzureCredentialPath() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        DefaultAzureCredential mockCredential = mock(DefaultAzureCredential.class);
        MockedConstruction.MockInitializer<DefaultAzureCredentialBuilder> credentialBuilderAnswer =
                (mock, ctx) -> when(mock.build()).thenReturn(mockCredential);

        try (MockedConstruction<CosmosClientBuilder> cosmosBuilderMocked =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer());
             MockedConstruction<DefaultAzureCredentialBuilder> credentialBuilderMocked =
                     mockConstruction(DefaultAzureCredentialBuilder.class, credentialBuilderAnswer)) {

            assertDoesNotThrow(() -> new CosmosProviderClient(config));

            List<CosmosClientBuilder> builders = cosmosBuilderMocked.constructed();
            assertEquals(1, builders.size());
            verify(builders.get(0)).buildClient();
            verify(builders.get(0)).credential(mockCredential);
            verify(builders.get(0)).consistencyLevel(ConsistencyLevel.EVENTUAL);
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

    // ── Override-constraint warning ───────────────────────────────────────────

    @Test
    @DisplayName("consistencyLevel configured: WARN logged reminding operator that override must be ≤ account default")
    void consistencyConfigLogsAccountDefaultWarning() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CosmosProviderClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            new CosmosProviderClient(config);
        } finally {
            logger.detachAppender(appender);
        }

        boolean warnLogged = appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN &&
                e.getFormattedMessage().contains("equal to or weaker"));
        assertTrue(warnLogged,
                "Construction with a consistencyLevel override must log a WARN reminding " +
                "the operator that the override must be ≤ the account's default");
    }

    @Test
    @DisplayName("no consistencyLevel configured: no override-constraint WARN is logged")
    void noConsistencyConfigDoesNotLogAccountDefaultWarning() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .build();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CosmosProviderClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            new CosmosProviderClient(config);
        } finally {
            logger.detachAppender(appender);
        }

        boolean warnLogged = appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN &&
                e.getFormattedMessage().contains("equal to or weaker"));
        assertFalse(warnLogged,
                "No override-constraint WARN should be logged when no consistencyLevel is configured");
    }

    @Test
    @DisplayName("read() with EVENTUAL override: readItem per-request options carry null consistency (client-level handles it)")
    @SuppressWarnings("unchecked")
    void readDoesNotSetPerRequestConsistencyWhenOverrideConfigured() {
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
            assertNull(captor.getValue().getConsistencyLevel(),
                    "read() must not set per-request consistency — the client-level override handles it");
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
    @DisplayName("query() with EVENTUAL override: queryItems per-request options carry null consistency (client-level handles it)")
    @SuppressWarnings("unchecked")
    void queryDoesNotSetPerRequestConsistencyWhenOverrideConfigured() {
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
            assertNull(captor.getValue().getConsistencyLevel(),
                    "query() must not set per-request consistency — the client-level override handles it");
        }
    }

    @Test
    @DisplayName("queryWithTranslation() with EVENTUAL override: queryItems per-request options carry null consistency (client-level handles it)")
    @SuppressWarnings("unchecked")
    void queryWithTranslationDoesNotSetPerRequestConsistencyWhenOverrideConfigured() {
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
            assertNull(captor.getValue().getConsistencyLevel(),
                    "queryWithTranslation() must not set per-request consistency — the client-level override handles it");
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

    // ── Write-path consistency invariant ──────────────────────────────────────
    //
    // These tests verify that write operations (create/update/upsert/delete) always
    // use per-request CosmosItemRequestOptions with no consistency level set, even
    // when a consistency override is configured at the client level. Cosmos DB ignores
    // consistency overrides on write operations at the service level, but keeping
    // per-request write options free of any consistency setting is explicit and avoids
    // any ambiguity if the SDK behaviour changes.

    @Test
    @DisplayName("create() with EVENTUAL override: createItem per-request options carry no consistency level")
    @SuppressWarnings("unchecked")
    void createDoesNotCarryConsistencyLevel() {
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
        when(mockResponse.getStatusCode()).thenReturn(201);
        when(mockContainer.createItem(any(ObjectNode.class), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class)))
                .thenReturn(mockResponse);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            MulticloudDbKey key = MulticloudDbKey.of("partition1");
            providerClient.create(address, key, Map.of("name", "alice"), null);

            ArgumentCaptor<CosmosItemRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
            verify(mockContainer).createItem(any(ObjectNode.class), any(PartitionKey.class),
                    captor.capture());
            assertNull(captor.getValue().getConsistencyLevel(),
                    "create() must not carry a per-request consistency level when a client-level override is configured");
        }
    }

    @Test
    @DisplayName("update() with EVENTUAL override: replaceItem per-request options carry no consistency level")
    @SuppressWarnings("unchecked")
    void updateDoesNotCarryConsistencyLevel() {
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
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockContainer.replaceItem(any(ObjectNode.class), anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class)))
                .thenReturn(mockResponse);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            MulticloudDbKey key = MulticloudDbKey.of("partition1");
            providerClient.update(address, key, Map.of("name", "bob"), null);

            ArgumentCaptor<CosmosItemRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
            verify(mockContainer).replaceItem(any(ObjectNode.class), anyString(), any(PartitionKey.class),
                    captor.capture());
            assertNull(captor.getValue().getConsistencyLevel(),
                    "update() must not carry a per-request consistency level when a client-level override is configured");
        }
    }

    @Test
    @DisplayName("upsert() with EVENTUAL override: upsertItem per-request options carry no consistency level")
    @SuppressWarnings("unchecked")
    void upsertDoesNotCarryConsistencyLevel() {
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
        when(mockResponse.getStatusCode()).thenReturn(200);
        when(mockContainer.upsertItem(any(ObjectNode.class), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class)))
                .thenReturn(mockResponse);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            MulticloudDbKey key = MulticloudDbKey.of("partition1");
            providerClient.upsert(address, key, Map.of("name", "carol"), null);

            ArgumentCaptor<CosmosItemRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
            verify(mockContainer).upsertItem(any(ObjectNode.class), any(PartitionKey.class),
                    captor.capture());
            assertNull(captor.getValue().getConsistencyLevel(),
                    "upsert() must not carry a per-request consistency level when a client-level override is configured");
        }
    }

    @Test
    @DisplayName("delete() with EVENTUAL override: deleteItem per-request options carry no consistency level")
    @SuppressWarnings("unchecked")
    void deleteDoesNotCarryConsistencyLevel() {
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

        CosmosItemResponse<Object> mockResponse = mock(CosmosItemResponse.class);
        when(mockResponse.getStatusCode()).thenReturn(204);
        when(mockContainer.deleteItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class)))
                .thenReturn(mockResponse);

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderAnswerWithClient(mockClient))) {

            CosmosProviderClient providerClient = new CosmosProviderClient(config);
            ResourceAddress address = new ResourceAddress("testdb", "testcol");
            MulticloudDbKey key = MulticloudDbKey.of("partition1");
            providerClient.delete(address, key, null);

            ArgumentCaptor<CosmosItemRequestOptions> captor =
                    ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
            verify(mockContainer).deleteItem(anyString(), any(PartitionKey.class),
                    captor.capture());
            assertNull(captor.getValue().getConsistencyLevel(),
                    "delete() must not carry a per-request consistency level when a client-level override is configured");
        }
    }
}
