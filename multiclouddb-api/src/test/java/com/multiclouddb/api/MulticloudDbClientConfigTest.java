// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MulticloudDbClientConfigTest {

    @Test
    void minimalConfig() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .build();

        assertEquals(ProviderId.COSMOS, config.provider());
        assertTrue(config.connection().isEmpty());
        assertTrue(config.auth().isEmpty());
        assertNotNull(config.defaultOptions());
    }

    @Test
    void fullConfig() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", "http://localhost:8000")
                .connection("region", "us-east-1")
                .auth("accessKeyId", "test")
                .auth("secretAccessKey", "secret")
                .build();

        assertEquals(ProviderId.DYNAMO, config.provider());
        assertEquals("http://localhost:8000", config.connection().get("endpoint"));
        assertEquals("us-east-1", config.connection().get("region"));
        assertEquals("test", config.auth().get("accessKeyId"));
    }

    @Test
    void configWithoutProviderThrows() {
        assertThrows(NullPointerException.class, () ->
                MulticloudDbClientConfig.builder().build());
    }

    @Test
    void userAgentSuffixDefaultsToNull() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .build();

        assertNull(config.userAgentSuffix());
    }

    @Test
    void userAgentSuffixRoundTrip() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .userAgentSuffix("my-app/1.0")
                .build();

        assertEquals("my-app/1.0", config.userAgentSuffix());
    }

    @Test
    void userAgentSuffixAcceptsNullToClear() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .userAgentSuffix("first")
                .userAgentSuffix(null)
                .build();

        assertNull(config.userAgentSuffix());
    }

    @Test
    void userAgentSuffixAllowsPrintableAsciiAndTab() {
        String suffix = "app/1.0\t(extra info; build=abc)";
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .userAgentSuffix(suffix)
                .build();

        assertEquals(suffix, config.userAgentSuffix());
    }

    @Test
    void userAgentSuffixRejectsCarriageReturn() {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder.userAgentSuffix("evil\rInjected: header"));
        assertTrue(ex.getMessage().contains("invalid character"));
    }

    @Test
    void userAgentSuffixRejectsLineFeed() {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS);

        assertThrows(IllegalArgumentException.class,
                () -> builder.userAgentSuffix("evil\nInjected: header"));
    }

    @Test
    void userAgentSuffixRejectsNullByte() {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS);

        assertThrows(IllegalArgumentException.class,
                () -> builder.userAgentSuffix("evil\u0000bytes"));
    }

    @Test
    void userAgentSuffixRejectsNonAscii() {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS);

        assertThrows(IllegalArgumentException.class,
                () -> builder.userAgentSuffix("café"));
    }

    @Test
    void userAgentSuffixRejectsOversizedInput() {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS);
        String tooLong = "a".repeat(257);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder.userAgentSuffix(tooLong));
        assertTrue(ex.getMessage().contains("length"));
    }

    @Test
    void userAgentSuffixAcceptsMaxLength() {
        String maxLen = "a".repeat(256);
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .userAgentSuffix(maxLen)
                .build();

        assertEquals(maxLen, config.userAgentSuffix());
    }
}
