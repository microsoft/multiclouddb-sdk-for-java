// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SdkUserAgentTest {

    @Test
    void baseFormatStartsWithSdkPrefix() {
        String base = SdkUserAgent.userAgentBase();
        assertTrue(base.startsWith("azsdk-java-multiclouddb/"),
                "Expected base UA to start with 'azsdk-java-multiclouddb/', got: " + base);
    }

    @Test
    void sdkVersionIsNotNull() {
        assertNotNull(SdkUserAgent.sdkVersion());
        assertFalse(SdkUserAgent.sdkVersion().isBlank());
    }

    @Test
    void userAgentWithoutSuffix() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .build();

        String ua = SdkUserAgent.userAgent(config);
        assertEquals(SdkUserAgent.userAgentBase(), ua);
    }

    @Test
    void userAgentWithCustomSuffix() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .userAgentSuffix("my-app/2.0")
                .build();

        String ua = SdkUserAgent.userAgent(config);
        assertTrue(ua.startsWith("azsdk-java-multiclouddb/"));
        assertTrue(ua.endsWith(" my-app/2.0"));
    }

    @Test
    void userAgentWithBlankSuffixIgnored() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .userAgentSuffix("   ")
                .build();

        String ua = SdkUserAgent.userAgent(config);
        assertEquals(SdkUserAgent.userAgentBase(), ua);
    }

    @Test
    void userAgentWithNullConfig() {
        String ua = SdkUserAgent.userAgent(null);
        assertEquals(SdkUserAgent.userAgentBase(), ua);
    }
}
