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
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient ignoredClient = new CosmosProviderClient(config(Map.of()))) {
            // Construction emits the transport snapshot after the native client is built.
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        long transportConfigurationLogCount = appender.list.stream().filter(
                event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().equals(
                                "Cosmos transport configured: Gateway mode, HTTP/2 enabled. "
                                        + "Gateway V1/V2 routing is selected automatically from "
                                        + "account configuration by Azure Cosmos DB and its SDK."))
                .count();
        assertEquals(1L, transportConfigurationLogCount);
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("removedTransportSettings")
    void rejectsRemovedTransportSettingBeforeBuilderConstruction(
            String property, String value, String expectedMessage) {
        try (MockedConstruction<CosmosClientBuilder> mocked = mockBuilderConstruction()) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CosmosProviderClient(config(property, value)));

            assertEquals(expectedMessage, error.getMessage());
            assertEquals(0, mocked.constructed().size());
        }
    }

    private static Stream<Arguments> removedTransportSettings() {
        String connectionModeMessage =
                "Cosmos connection property 'connectionMode' is no longer supported; "
                        + "Gateway mode is always used";
        String http2Message =
                "Cosmos connection property 'gatewayHttp2Enabled' is not supported; "
                        + "Gateway HTTP/2 is always enabled";
        String automaticRoutingSuffix =
                "' is not supported; Gateway V1/V2 routing is selected automatically "
                        + "from account configuration by Azure Cosmos DB and its SDK";

        return Stream.of(
                Arguments.of("connectionMode", "direct", connectionModeMessage),
                Arguments.of("connectionMode", "gateway", connectionModeMessage),
                Arguments.of("gatewayHttp2Enabled", "false", http2Message),
                Arguments.of("gatewayHttp2Enabled", "true", http2Message),
                Arguments.of(
                        "gatewayV2Enable",
                        "false",
                        "Cosmos connection property 'gatewayV2Enable" + automaticRoutingSuffix),
                Arguments.of(
                        "gatewayV2Enable",
                        "true",
                        "Cosmos connection property 'gatewayV2Enable" + automaticRoutingSuffix),
                Arguments.of(
                        "thinClientEnabled",
                        "false",
                        "Cosmos connection property 'thinClientEnabled" + automaticRoutingSuffix),
                Arguments.of(
                        "thinClientEnabled",
                        "true",
                        "Cosmos connection property 'thinClientEnabled" + automaticRoutingSuffix));
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
        return mockConstruction(
                CosmosClientBuilder.class,
                withSettings().defaultAnswer(invocation -> {
                    if (CosmosClientBuilder.class.isAssignableFrom(
                            invocation.getMethod().getReturnType())) {
                        return invocation.getMock();
                    }
                    return null;
                }),
                (builder, context) -> when(builder.buildClient()).thenReturn(client));
    }
}
