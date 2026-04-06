// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.hyperscaledb.e2e;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.ProviderId;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads provider configuration from a properties file.
 *
 * <p>Priority order (highest first):
 * <ol>
 *   <li>System property {@code hyperscaledb.config} pointing to a file path or
 *       classpath resource</li>
 *   <li>The {@code defaultConfigFile} argument passed to {@link #load(String)}</li>
 * </ol>
 *
 * <p>To switch providers, run with a different config file — no code changes needed:
 * <pre>
 *   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=cosmos.properties
 *   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=dynamo.properties
 *   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=spanner.properties
 * </pre>
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Holds the SDK client configuration and any application-level properties
     * (e.g., {@code hyperscaledb.database}, {@code hyperscaledb.collection}).
     */
    public record AppConfig(HyperscaleDbClientConfig sdk, Properties raw) {
        /** Returns the property value, or {@code defaultValue} if absent. */
        public String get(String key, String defaultValue) {
            return raw.getProperty(key, defaultValue);
        }
    }

    /** Load config using the given default file name (classpath resource). */
    public static AppConfig load(String defaultConfigFile) {
        String configFile = System.getProperty("hyperscaledb.config",
                defaultConfigFile != null ? defaultConfigFile : "cosmos.properties");

        Properties props = loadProperties(configFile);

        // System properties prefixed with hyperscaledb. override file values.
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("hyperscaledb.")) {
                props.setProperty(key, System.getProperty(key));
            }
        }

        return new AppConfig(buildSdkConfig(props), props);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static HyperscaleDbClientConfig buildSdkConfig(Properties props) {
        String providerName = props.getProperty("hyperscaledb.provider", "cosmos");
        HyperscaleDbClientConfig.Builder builder = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.fromId(providerName));

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("hyperscaledb.connection.")) {
                builder.connection(
                        key.substring("hyperscaledb.connection.".length()),
                        props.getProperty(key));
            }
            if (key.startsWith("hyperscaledb.auth.")) {
                builder.auth(
                        key.substring("hyperscaledb.auth.".length()),
                        props.getProperty(key));
            }
        }

        return builder.build();
    }

    /**
     * Loads a properties file. Tries absolute/relative file path first,
     * then falls back to classpath resource.
     */
    private static Properties loadProperties(String name) {
        Properties props = new Properties();
        Path filePath = Path.of(name);
        try {
            if (Files.exists(filePath)) {
                try (InputStream is = new FileInputStream(filePath.toFile())) {
                    props.load(is);
                    System.out.println("[config] Loaded from file: " + filePath.toAbsolutePath());
                }
            } else {
                try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(name)) {
                    if (is != null) {
                        props.load(is);
                        System.out.println("[config] Loaded from classpath: " + name);
                    } else {
                        System.out.println("[config] File not found: " + name
                                + " — relying on system properties only");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[config] Failed to read " + name + ": " + e.getMessage());
        }
        return props;
    }
}
