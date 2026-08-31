// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.Http2ConnectionConfig;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosmosTransportConfigTest {

    @Test
    void gatewayPoolSettingsAreAppliedWithFixedHttp2() {
        MulticloudDbClientConfig config = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_MAX_CONNECTION_POOL_SIZE, "64")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MIN_CONNECTION_POOL_SIZE, "2")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MAX_CONNECTION_POOL_SIZE, "8")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MAX_CONCURRENT_STREAMS, "32")
                .build();

        GatewayConnectionConfig gateway = CosmosProviderClient.gatewayConnectionConfig(config);
        Http2ConnectionConfig http2 = gateway.getHttp2ConnectionConfig();

        assertEquals(64, gateway.getMaxConnectionPoolSize());
        assertEquals(true, http2.isEnabled());
        assertEquals(2, http2.getMinConnectionPoolSize());
        assertEquals(8, http2.getMaxConnectionPoolSize());
        assertEquals(32, http2.getMaxConcurrentStreams());
    }

    @Test
    void invalidPoolValuesFailFast() {
        MulticloudDbClientConfig badPool = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_MAX_CONNECTION_POOL_SIZE, "0")
                .build();
        MulticloudDbClientConfig badNumber = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MAX_CONNECTION_POOL_SIZE, "many")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> CosmosProviderClient.gatewayConnectionConfig(badPool));
        assertThrows(IllegalArgumentException.class,
                () -> CosmosProviderClient.gatewayConnectionConfig(badNumber));
    }

    @Test
    void contentResponseOnWriteDefaultsToEnabledAndIsConfigurable() {
        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(CosmosClientBuilder.class,
                (mock, ctx) -> {
                    when(mock.endpoint(anyString())).thenReturn(mock);
                    when(mock.key(anyString())).thenReturn(mock);
                    when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
                    when(mock.gatewayMode(any(GatewayConnectionConfig.class))).thenReturn(mock);
                    when(mock.userAgentSuffix(anyString())).thenReturn(mock);
                    when(mock.buildClient()).thenReturn(mock(CosmosClient.class));
                })) {
            new CosmosProviderClient(writeResponseConfig(null)).close();
            verify(mocked.constructed().get(0)).contentResponseOnWriteEnabled(true);

            new CosmosProviderClient(writeResponseConfig("false")).close();
            verify(mocked.constructed().get(1)).contentResponseOnWriteEnabled(false);

            new CosmosProviderClient(writeResponseConfig("true")).close();
            verify(mocked.constructed().get(2)).contentResponseOnWriteEnabled(true);
        }
    }

    @Test
    void invalidContentResponseOnWriteFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new CosmosProviderClient(writeResponseConfig("sometimes")));
    }

    private static MulticloudDbClientConfig writeResponseConfig(String contentResponseOnWrite) {
        MulticloudDbClientConfig.Builder builder = baseConfig()
                .connection(CosmosConstants.CONFIG_KEY,
                        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
        if (contentResponseOnWrite != null) {
            builder.connection(CosmosConstants.CONFIG_CONTENT_RESPONSE_ON_WRITE_ENABLED, contentResponseOnWrite);
        }
        return builder.build();
    }

    private static MulticloudDbClientConfig.Builder baseConfig() {
        return MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT,
                        "https://example.documents.azure.com:443/");
    }
}
