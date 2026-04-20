// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosClientBuilder;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.spi.SdkUserAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests verifying that {@link CosmosProviderClient} configures the
 * Azure Cosmos SDK with the expected {@code userAgentSuffix} value derived
 * from {@link SdkUserAgent}.
 * <p>
 * This test replaces an earlier conformance test that asserted on the textual
 * content of {@link com.azure.cosmos.CosmosException} diagnostics. That
 * approach was brittle to Cosmos SDK formatting changes — it would break
 * whenever the SDK altered how diagnostics or error messages were rendered,
 * even though the user-agent functionality itself was unchanged.
 * <p>
 * Instead, this test intercepts the {@link CosmosClientBuilder} construction
 * via {@link org.mockito.Mockito#mockConstruction(Class)} and verifies that
 * {@link CosmosClientBuilder#userAgentSuffix(String)} is invoked with the
 * value produced by {@link SdkUserAgent#userAgent(MulticloudDbClientConfig)}.
 * This is a behavioral contract test against our own provider — it does not
 * depend on the Cosmos SDK's diagnostic output format.
 */
class CosmosUserAgentTest {

    private static final String COSMOS_DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static final String CUSTOMER_SUFFIX = "unit-test-app";

    @Test
    @DisplayName("CosmosClientBuilder.userAgentSuffix is called with SDK base + customer suffix")
    void userAgentSuffixIncludesBaseAndCustomerSuffix() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, "https://example.documents.azure.com:443/")
                .connection(CosmosConstants.CONFIG_KEY, COSMOS_DUMMY_KEY)
                .userAgentSuffix(CUSTOMER_SUFFIX)
                .build();

        String expected = SdkUserAgent.userAgent(config);

        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(
                CosmosClientBuilder.class,
                withSettings().defaultAnswer(invocation -> {
                    // Fluent builder: setters returning CosmosClientBuilder must
                    // return `this` (the mock) so chained calls don't NPE.
                    if (CosmosClientBuilder.class.isAssignableFrom(
                            invocation.getMethod().getReturnType())) {
                        return invocation.getMock();
                    }
                    return null;
                }))) {

            new CosmosProviderClient(config);

            List<CosmosClientBuilder> builders = mocked.constructed();
            assertEquals(1, builders.size(),
                    "Exactly one CosmosClientBuilder should be constructed");
            CosmosClientBuilder builder = builders.get(0);

            verify(builder, times(1)).userAgentSuffix(expected);
            assertTrue(expected.contains(SdkUserAgent.userAgentBase()),
                    "Computed user agent should contain SDK base");
            assertTrue(expected.contains(CUSTOMER_SUFFIX),
                    "Computed user agent should contain customer suffix");
        }
    }

    @Test
    @DisplayName("CosmosClientBuilder.userAgentSuffix is called with SDK base only when no customer suffix")
    void userAgentSuffixContainsBaseWithoutCustomerSuffix() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, "https://example.documents.azure.com:443/")
                .connection(CosmosConstants.CONFIG_KEY, COSMOS_DUMMY_KEY)
                .build();

        String expected = SdkUserAgent.userAgent(config);

        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(
                CosmosClientBuilder.class,
                withSettings().defaultAnswer(invocation -> {
                    if (CosmosClientBuilder.class.isAssignableFrom(
                            invocation.getMethod().getReturnType())) {
                        return invocation.getMock();
                    }
                    return null;
                }))) {

            new CosmosProviderClient(config);

            CosmosClientBuilder builder = mocked.constructed().get(0);
            verify(builder, times(1)).userAgentSuffix(expected);
            assertTrue(expected.contains(SdkUserAgent.userAgentBase()),
                    "Computed user agent should contain SDK base");
        }
    }
}
