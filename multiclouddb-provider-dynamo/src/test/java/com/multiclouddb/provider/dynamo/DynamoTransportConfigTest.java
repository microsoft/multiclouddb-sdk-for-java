// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoTransportConfigTest {

    @Test
    void maxConnectionsIsAppliedToTheSyncHttpClient() {
        DynamoDbClientBuilder builder = mock(DynamoDbClientBuilder.class, RETURNS_SELF);
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(builder.build()).thenReturn(client);
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection(DynamoConstants.CONFIG_REGION, "us-west-2")
                .connection(DynamoConstants.CONFIG_MAX_CONNECTIONS, "64")
                .build();

        try (MockedStatic<DynamoDbClient> dynamo = mockStatic(DynamoDbClient.class)) {
            dynamo.when(DynamoDbClient::builder).thenReturn(builder);

            new DynamoProviderClient(config).close();

            verify(builder).httpClientBuilder(any(SdkHttpClient.Builder.class));
        }
    }

    @Test
    void maxConnectionsParserValidatesPositiveIntegers() {
        assertNull(DynamoProviderClient.positiveConnectionInt(null,
                DynamoConstants.CONFIG_MAX_CONNECTIONS));
        assertEquals(64, DynamoProviderClient.positiveConnectionInt("64",
                DynamoConstants.CONFIG_MAX_CONNECTIONS));
        assertThrows(IllegalArgumentException.class,
                () -> DynamoProviderClient.positiveConnectionInt("0",
                        DynamoConstants.CONFIG_MAX_CONNECTIONS));
        assertThrows(IllegalArgumentException.class,
                () -> DynamoProviderClient.positiveConnectionInt("many",
                        DynamoConstants.CONFIG_MAX_CONNECTIONS));
    }
}
