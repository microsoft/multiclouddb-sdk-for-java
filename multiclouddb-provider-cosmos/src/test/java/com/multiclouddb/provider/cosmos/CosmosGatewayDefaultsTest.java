// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CosmosGatewayDefaultsTest {

    private static final String DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    private String originalThinClientProperty;

    @BeforeEach
    void saveAndClearThinClientProperty() {
        originalThinClientProperty =
                System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY);
        System.clearProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY);
    }

    @AfterEach
    void restoreThinClientProperty() {
        if (originalThinClientProperty == null) {
            System.clearProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY);
        } else {
            System.setProperty(
                    CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY, originalThinClientProperty);
        }
    }

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
    void absentThinClientConfigLeavesSdkProbeDefaultUnchanged() {
        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient client = new CosmosProviderClient(config(null, null))) {
            assertNull(System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY));
        }
    }

    @Test
    void thinClientFalseOptsOutThroughSdkProperty() {
        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient client = new CosmosProviderClient(
                     config(CosmosConstants.CONFIG_THIN_CLIENT_ENABLED, "false"))) {
            assertEquals(
                    "false", System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY));
        }
    }

    @Test
    void thinClientTrueForcesSdkOptIn() {
        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient client = new CosmosProviderClient(
                     config(CosmosConstants.CONFIG_THIN_CLIENT_ENABLED, "TRUE"))) {
            assertEquals(
                    "true", System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY));
        }
    }

    @Test
    void operatorSdkPropertyTakesPrecedence() {
        System.setProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY, "false");

        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient client = new CosmosProviderClient(
                     config(CosmosConstants.CONFIG_THIN_CLIENT_ENABLED, "true"))) {
            assertEquals(
                    "false", System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY));
        }
    }

    @Test
    void rejectsMalformedThinClientValue() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(
                        config(CosmosConstants.CONFIG_THIN_CLIENT_ENABLED, "yes")));

        assertEquals(
                "Cosmos connection property 'thinClientEnabled' must be 'true' or 'false'",
                error.getMessage());
    }

    @Test
    void rejectsRemovedConnectionModeOption() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(config("connectionMode", "direct")));

        assertEquals(
                "Cosmos connection property 'connectionMode' is no longer supported; "
                        + "Gateway mode is always used",
                error.getMessage());
    }

    @Test
    void rejectsGatewayHttp2Toggle() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(config("gatewayHttp2Enabled", "false")));

        assertEquals(
                "Cosmos connection property 'gatewayHttp2Enabled' is not supported; "
                        + "Gateway HTTP/2 is always enabled",
                error.getMessage());
    }

    private static MulticloudDbClientConfig config(String property, String value) {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(
                        CosmosConstants.CONFIG_ENDPOINT,
                        "https://example.documents.azure.com:443/")
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY);
        if (property != null) {
            builder.connection(property, value);
        }
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
