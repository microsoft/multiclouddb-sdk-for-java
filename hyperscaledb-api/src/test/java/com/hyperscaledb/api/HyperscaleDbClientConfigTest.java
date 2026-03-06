package com.hyperscaledb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HyperscaleDbClientConfigTest {

    @Test
    void minimalConfig() {
        HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .build();

        assertEquals(ProviderId.COSMOS, config.provider());
        assertTrue(config.connection().isEmpty());
        assertTrue(config.auth().isEmpty());
        assertNotNull(config.defaultOptions());
    }

    @Test
    void fullConfig() {
        HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", "http://localhost:8000")
                .connection("region", "us-east-1")
                .auth("accessKeyId", "test")
                .auth("secretAccessKey", "secret")
                .featureFlag("debug", "true")
                .build();

        assertEquals(ProviderId.DYNAMO, config.provider());
        assertEquals("http://localhost:8000", config.connection().get("endpoint"));
        assertEquals("us-east-1", config.connection().get("region"));
        assertEquals("test", config.auth().get("accessKeyId"));
        assertEquals("true", config.featureFlags().get("debug"));
    }

    @Test
    void configWithoutProviderThrows() {
        assertThrows(NullPointerException.class, () ->
                HyperscaleDbClientConfig.builder().build());
    }
}
