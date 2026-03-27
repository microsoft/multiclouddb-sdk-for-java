// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.ProviderId;

import java.util.Objects;

/**
 * Conformance test configuration loader.
 * <p>
 * Reads connection details from system properties with environment variable
 * fallback.
 * Each property is read as
 * {@code System.getProperty(key, System.getenv(envKey))}.
 * <p>
 * <h3>Cosmos DB</h3>
 * <ul>
 * <li>{@code cosmos.endpoint} / {@code COSMOS_ENDPOINT} (default:
 * {@code https://localhost:8081})</li>
 * <li>{@code cosmos.key} / {@code COSMOS_KEY} (default: emulator key)</li>
 * <li>{@code cosmos.database} / {@code COSMOS_DATABASE} (default:
 * {@code todoapp})</li>
 * <li>{@code cosmos.container} / {@code COSMOS_CONTAINER} (default:
 * {@code todos})</li>
 * </ul>
 * <h3>DynamoDB</h3>
 * <ul>
 * <li>{@code dynamo.endpoint} / {@code DYNAMO_ENDPOINT} (default:
 * {@code http://localhost:8000})</li>
 * <li>{@code dynamo.region} / {@code DYNAMO_REGION} (default:
 * {@code us-east-1})</li>
 * <li>{@code dynamo.accessKeyId} / {@code DYNAMO_ACCESS_KEY_ID} (default:
 * {@code fakeMyKeyId})</li>
 * <li>{@code dynamo.secretAccessKey} / {@code DYNAMO_SECRET_ACCESS_KEY}
 * (default: {@code fakeSecretAccessKey})</li>
 * <li>{@code dynamo.table} / {@code DYNAMO_TABLE} (default: {@code todos})</li>
 * </ul>
 * <h3>Spanner</h3>
 * <ul>
 * <li>{@code spanner.emulatorHost} / {@code SPANNER_EMULATOR_HOST} (default:
 * {@code localhost:9010})</li>
 * <li>{@code spanner.projectId} / {@code SPANNER_PROJECT_ID} (default:
 * {@code test-project})</li>
 * <li>{@code spanner.instanceId} / {@code SPANNER_INSTANCE_ID} (default:
 * {@code test-instance})</li>
 * <li>{@code spanner.databaseId} / {@code SPANNER_DATABASE_ID} (default:
 * {@code testdb})</li>
 * <li>{@code spanner.table} / {@code SPANNER_TABLE} (default:
 * {@code todos})</li>
 * </ul>
 */
public final class ConformanceConfig {

    // --- Cosmos defaults (Emulator) ---
    private static final String DEFAULT_COSMOS_ENDPOINT = "https://localhost:8081";
    private static final String DEFAULT_COSMOS_KEY = "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static final String DEFAULT_COSMOS_DATABASE = "todoapp";
    private static final String DEFAULT_COSMOS_CONTAINER = "todos";

    // --- DynamoDB defaults (DynamoDB Local) ---
    private static final String DEFAULT_DYNAMO_ENDPOINT = "http://localhost:8000";
    private static final String DEFAULT_DYNAMO_REGION = "us-east-1";
    private static final String DEFAULT_DYNAMO_ACCESS_KEY = "fakeMyKeyId";
    private static final String DEFAULT_DYNAMO_SECRET_KEY = "fakeSecretAccessKey";
    private static final String DEFAULT_DYNAMO_TABLE = "todos";

    // --- Spanner defaults (Emulator) ---
    private static final String DEFAULT_SPANNER_EMULATOR_HOST = "localhost:9010";
    private static final String DEFAULT_SPANNER_PROJECT_ID = "test-project";
    private static final String DEFAULT_SPANNER_INSTANCE_ID = "test-instance";
    private static final String DEFAULT_SPANNER_DATABASE_ID = "testdb";
    private static final String DEFAULT_SPANNER_TABLE = "todos";

    private ConformanceConfig() {
    }

    /**
     * Build a {@link HyperscaleDbClientConfig} for the given provider using system
     * properties
     * with environment variable fallback.
     *
     * @param provider the target provider
     * @return fully-populated configuration
     * @throws IllegalArgumentException if provider is null or unsupported
     */
    public static HyperscaleDbClientConfig forProvider(ProviderId provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return switch (provider.id()) {
            case "cosmos" -> cosmosConfig();
            case "dynamo" -> dynamoConfig();
            case "spanner" -> spannerConfig();
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider.id());
        };
    }

    /**
     * Get the default table/collection name for the given provider.
     */
    public static String tableFor(ProviderId provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return switch (provider.id()) {
            case "cosmos" -> resolve("cosmos.container", "COSMOS_CONTAINER", DEFAULT_COSMOS_CONTAINER);
            case "dynamo" -> resolve("dynamo.table", "DYNAMO_TABLE", DEFAULT_DYNAMO_TABLE);
            case "spanner" -> resolve("spanner.table", "SPANNER_TABLE", DEFAULT_SPANNER_TABLE);
            default -> "todos";
        };
    }

    /**
     * Get the default database name for the given provider.
     */
    public static String databaseFor(ProviderId provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return switch (provider.id()) {
            case "cosmos" -> resolve("cosmos.database", "COSMOS_DATABASE", DEFAULT_COSMOS_DATABASE);
            case "dynamo" -> "local";
            case "spanner" -> resolve("spanner.databaseId", "SPANNER_DATABASE_ID", DEFAULT_SPANNER_DATABASE_ID);
            default -> "default";
        };
    }

    // --- Private builders ---

    private static HyperscaleDbClientConfig cosmosConfig() {
        return HyperscaleDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", resolve("cosmos.endpoint", "COSMOS_ENDPOINT", DEFAULT_COSMOS_ENDPOINT))
                .connection("key", resolve("cosmos.key", "COSMOS_KEY", DEFAULT_COSMOS_KEY))
                .connection("connectionMode", "gateway")
                .build();
    }

    private static HyperscaleDbClientConfig dynamoConfig() {
        return HyperscaleDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", resolve("dynamo.endpoint", "DYNAMO_ENDPOINT", DEFAULT_DYNAMO_ENDPOINT))
                .connection("region", resolve("dynamo.region", "DYNAMO_REGION", DEFAULT_DYNAMO_REGION))
                .auth("accessKeyId", resolve("dynamo.accessKeyId", "DYNAMO_ACCESS_KEY_ID", DEFAULT_DYNAMO_ACCESS_KEY))
                .auth("secretAccessKey",
                        resolve("dynamo.secretAccessKey", "DYNAMO_SECRET_ACCESS_KEY", DEFAULT_DYNAMO_SECRET_KEY))
                .build();
    }

    private static HyperscaleDbClientConfig spannerConfig() {
        return HyperscaleDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", resolve("spanner.projectId", "SPANNER_PROJECT_ID", DEFAULT_SPANNER_PROJECT_ID))
                .connection("instanceId",
                        resolve("spanner.instanceId", "SPANNER_INSTANCE_ID", DEFAULT_SPANNER_INSTANCE_ID))
                .connection("databaseId",
                        resolve("spanner.databaseId", "SPANNER_DATABASE_ID", DEFAULT_SPANNER_DATABASE_ID))
                .connection("emulatorHost",
                        resolve("spanner.emulatorHost", "SPANNER_EMULATOR_HOST", DEFAULT_SPANNER_EMULATOR_HOST))
                .build();
    }

    /**
     * Resolve a value from system property, then environment variable, then
     * default.
     */
    private static String resolve(String sysProp, String envVar, String defaultVal) {
        String val = System.getProperty(sysProp);
        if (val != null && !val.isBlank()) {
            return val;
        }
        val = System.getenv(envVar);
        if (val != null && !val.isBlank()) {
            return val;
        }
        return defaultVal;
    }
}
