// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.core.http.HttpClient;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CosmosAzureDependenciesTest {

    @Test
    void constructsNativeHttpAndIdentityClientsWithoutRequestingTokens() {
        HttpClient httpClient = HttpClient.createDefault();
        assertNotNull(httpClient);

        DefaultAzureCredential credential = new DefaultAzureCredentialBuilder()
                .httpClient(httpClient)
                .build();
        assertNotNull(credential);
    }
}
