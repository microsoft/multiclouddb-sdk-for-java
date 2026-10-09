// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CosmosGatewayDefaultsTest {

    private static final String DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static final String STANDARD_ENDPOINT = "https://example.documents.azure.com:443/";

    @Test
    void alwaysUsesGatewayWithHttp2Enabled() {
        try (MockedConstruction<CosmosClientBuilder> mocked = mockBuilderConstruction();
             CosmosProviderClient ignored = new CosmosProviderClient(config(null, null))) {

            CosmosClientBuilder builder = mocked.constructed().get(0);
            ArgumentCaptor<GatewayConnectionConfig> configCaptor =
                    ArgumentCaptor.forClass(GatewayConnectionConfig.class);
            verify(builder).gatewayMode(configCaptor.capture());
            verify(builder, never()).gatewayMode();
            verify(builder, never()).directMode();

            GatewayConnectionConfig gatewayConfig = configCaptor.getValue();
            assertNotNull(gatewayConfig.getHttp2ConnectionConfig());
            assertEquals(Boolean.TRUE, gatewayConfig.getHttp2ConnectionConfig().isEnabled());
        }
    }

    @Test
    void logsFixedTransportAndAutomaticGatewaySelection() {
        Logger logger = (Logger) LoggerFactory.getLogger(CosmosProviderClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction(invocation -> {
                 assertTrue(transportLogs(appender).isEmpty(),
                         "Transport policy must not be logged before native construction succeeds");
                 return mock(CosmosClient.class);
             });
             CosmosProviderClient ignoredClient = new CosmosProviderClient(config(Map.of()))) {
            List<ILoggingEvent> events = transportLogs(appender);
            assertEquals(1, events.size(), "Emit one transport policy snapshot per client");
            ILoggingEvent event = events.get(0);
            assertEquals(Level.INFO, event.getLevel());
            String message = event.getFormattedMessage();
            assertAll(
                    () -> assertTrue(message.contains("configured"), message),
                    () -> assertTrue(message.contains("Gateway mode"), message),
                    () -> assertTrue(message.contains("HTTP/2 enabled"), message),
                    () -> assertTrue(message.contains("Gateway V1/V2"), message),
                    () -> assertTrue(message.contains("selected automatically"), message),
                    () -> assertTrue(message.contains("account configuration"), message),
                    () -> assertTrue(message.contains("Azure Cosmos DB"), message),
                    () -> assertTrue(message.contains("SDK"), message),
                    () -> assertFalse(message.matches(
                            "(?is).*\\b(?:using|uses|negotiated)\\s+Gateway V[12]\\b.*"),
                            "The configuration snapshot must not claim a negotiated route: " + message));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void doesNotLogTransportWhenNativeConstructionFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(CosmosProviderClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        IllegalStateException failure = new IllegalStateException("Native client construction failed");

        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction(invocation -> {
                 throw failure;
             })) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> new CosmosProviderClient(config(Map.of()))));
            assertTrue(transportLogs(appender).isEmpty(),
                    "Failed native construction must not emit a transport policy snapshot");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static List<ILoggingEvent> transportLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("Cosmos transport"))
                .toList();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("removedTransportSettings")
    void rejectsRemovedTransportSettingBeforeBuilderConstruction(
            String property, String value, List<String> expectedGuidance) {
        try (MockedConstruction<CosmosClientBuilder> mocked = mockBuilderConstruction()) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CosmosProviderClient(config(property, value)));

            String message = error.getMessage();
            assertTrue(message.contains("'" + property + "'"),
                    "Identify the exact rejected key: " + message);
            assertTrue(message.contains("not supported") || message.contains("no longer supported"),
                    "State that the setting is unsupported: " + message);
            assertAll(expectedGuidance.stream().map(fragment ->
                    () -> assertTrue(message.contains(fragment),
                            "Missing migration guidance '" + fragment + "': " + message)));
            assertEquals(0, mocked.constructed().size());
        }
    }

    private static Stream<Arguments> removedTransportSettings() {
        List<String> fixedGateway = List.of("Gateway mode", "always used");
        List<String> fixedHttp2 = List.of("Gateway HTTP/2", "always enabled");
        List<String> automaticRouting = List.of(
                "Gateway V1/V2", "selected automatically", "account configuration", "Azure Cosmos DB", "SDK");

        return Stream.of(
                Arguments.of("connectionMode", "direct", fixedGateway),
                Arguments.of("connectionMode", "gateway", fixedGateway),
                Arguments.of("gatewayHttp2Enabled", "false", fixedHttp2),
                Arguments.of("gatewayHttp2Enabled", "true", fixedHttp2),
                Arguments.of("gatewayV2Enable", "false", automaticRouting),
                Arguments.of("gatewayV2Enable", "true", automaticRouting),
                Arguments.of("thinClientEnabled", "false", automaticRouting),
                Arguments.of("thinClientEnabled", "true", automaticRouting));
    }

    private static MulticloudDbClientConfig config(String property, String value) {
        if (property == null) {
            return config(Map.of());
        }
        return config(Map.of(property, value));
    }

    private static MulticloudDbClientConfig config(Map<String, String> properties) {
        return configForEndpoint(STANDARD_ENDPOINT, properties);
    }

    private static MulticloudDbClientConfig configForEndpoint(
            String endpoint, Map<String, String> properties) {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, endpoint)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY);
        properties.forEach(builder::connection);
        return builder.build();
    }

    private static MockedConstruction<CosmosClientBuilder> mockBuilderConstruction() {
        CosmosClient client = mock(CosmosClient.class);
        return mockBuilderConstruction(invocation -> client);
    }

    private static MockedConstruction<CosmosClientBuilder> mockBuilderConstruction(
            Answer<CosmosClient> buildAnswer) {
        return mockConstruction(
                CosmosClientBuilder.class,
                withSettings().defaultAnswer(invocation -> {
                    if (CosmosClientBuilder.class.isAssignableFrom(
                            invocation.getMethod().getReturnType())) {
                        return invocation.getMock();
                    }
                    return null;
                }),
                (builder, context) -> when(builder.buildClient()).thenAnswer(buildAnswer));
    }
}
