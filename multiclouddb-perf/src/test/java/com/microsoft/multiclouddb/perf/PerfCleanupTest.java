package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerfCleanupTest {

    private static final String CONFIG_PROPERTY = "multiclouddb.config";
    private final String previousConfig = System.getProperty(CONFIG_PROPERTY);

    @AfterEach
    void restoreConfig() {
        if (previousConfig == null) {
            System.clearProperty(CONFIG_PROPERTY);
        } else {
            System.setProperty(CONFIG_PROPERTY, previousConfig);
        }
    }

    @Test
    void usesConfigSelectedByPerfMain() {
        System.setProperty(CONFIG_PROPERTY, "config/dynamo.live.properties");

        assertEquals("config/dynamo.live.properties", PerfCleanup.configPath());
    }

    @Test
    void retainsStandaloneDefault() {
        System.clearProperty(CONFIG_PROPERTY);

        assertEquals("cosmos.properties", PerfCleanup.configPath());
    }
}
