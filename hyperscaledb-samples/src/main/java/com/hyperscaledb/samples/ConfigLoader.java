package com.hyperscaledb.samples;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.ProviderId;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Unified configuration loader for all sample applications.
 * <p>
 * Reads a single properties file specified by (in priority order):
 * <ol>
 * <li>System property {@code hyperscaledb.config} (e.g.,
 * {@code -Dhyperscaledb.config=my-cosmos.properties})</li>
 * <li>The {@code defaultConfigFile} parameter passed to
 * {@link #load(String)}</li>
 * </ol>
 * <p>
 * The properties file uses a <b>unified, provider-neutral format</b>:
 * 
 * <pre>
 *   hyperscaledb.provider=cosmos          # which provider to use: cosmos | dynamo | spanner
 *   hyperscaledb.connection.endpoint=...  # connection settings (provider-specific keys)
 *   hyperscaledb.connection.key=...
 *   hyperscaledb.auth.accessKeyId=...     # auth settings (when needed)
 *   hyperscaledb.feature.someFlag=true    # optional feature flags
 * </pre>
 * <p>
 * To switch providers, simply point to a different config file at startup.
 * <b>No code changes required</b> — the SDK reads connection and auth keys
 * generically and routes them to the correct provider.
 */
public final class ConfigLoader {

    /** Default config file when none is specified. */
    private static final String DEFAULT_CONFIG = "todo-app-cosmos.properties";

    private ConfigLoader() {
    }

    /**
     * Holds both the SDK client configuration and any application-level properties
     * (e.g., {@code hyperscaledb.database}, {@code hyperscaledb.collection}).
     */
    public record AppConfig(HyperscaleDbClientConfig sdk, Properties properties) {

        /** Read an application property with a default value. */
        public String property(String key, String defaultValue) {
            return properties.getProperty(key, defaultValue);
        }

        /** Read an application property (null if missing). */
        public String property(String key) {
            return properties.getProperty(key);
        }
    }

    /**
     * Load configuration from the file specified by {@code -Dhyperscaledb.config},
     * falling back to the given default.
     *
     * @param defaultConfigFile classpath resource name to use if no system property
     *                          is set
     * @return {@link AppConfig} containing SDK config and application properties
     */
    public static AppConfig load(String defaultConfigFile) {
        String configFile = System.getProperty("hyperscaledb.config",
                defaultConfigFile != null ? defaultConfigFile : DEFAULT_CONFIG);
        Properties props = loadProperties(configFile);

        // System properties override file properties
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("hyperscaledb.")) {
                props.setProperty(key, System.getProperty(key));
            }
        }

        return new AppConfig(buildConfig(props), props);
    }

    /**
     * Convenience overload — loads from {@code -Dhyperscaledb.config} or the default
     * config.
     */
    public static AppConfig load() {
        return load(DEFAULT_CONFIG);
    }

    // ── Build config from unified properties ────────────────────────────────

    private static HyperscaleDbClientConfig buildConfig(Properties props) {
        String providerName = props.getProperty("hyperscaledb.provider", "cosmos");
        ProviderId provider = ProviderId.fromId(providerName);

        HyperscaleDbClientConfig.Builder builder = HyperscaleDbClientConfig.builder()
                .provider(provider);

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
            if (key.startsWith("hyperscaledb.feature.")) {
                builder.featureFlag(
                        key.substring("hyperscaledb.feature.".length()),
                        props.getProperty(key));
            }
        }

        return builder.build();
    }

    // ── Properties file loading ─────────────────────────────────────────────

    private static Properties loadProperties(String filename) {
        Properties props = new Properties();
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(filename)) {
            if (is != null) {
                props.load(is);
                System.out.println("  Loaded config: " + filename);
            } else {
                System.out.println("  Config file not found: " + filename
                        + " — using system properties only");
            }
        } catch (IOException ignored) {
            // Fall through — properties file is optional
        }
        return props;
    }
}
