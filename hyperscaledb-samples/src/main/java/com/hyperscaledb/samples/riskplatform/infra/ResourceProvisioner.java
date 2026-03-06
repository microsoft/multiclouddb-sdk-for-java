package com.hyperscaledb.samples.riskplatform.infra;

import com.hyperscaledb.api.HyperscaleDbClient;

import java.util.*;

/**
 * Pre-creates databases and containers/tables required by the Risk Platform.
 * <p>
 * Uses the portable {@link HyperscaleDbClient#ensureDatabase(String)} and
 * {@link HyperscaleDbClient#ensureContainer(ResourceAddress)} methods — no
 * provider-specific code required. The SDK handles provider-native semantics:
 * <ul>
 * <li><b>Cosmos DB</b>: creates databases + containers with
 * {@code /partitionKey}</li>
 * <li><b>DynamoDB</b>: creates tables named {@code database__collection} with
 * hash key {@code partitionKey} + range key {@code id}</li>
 * <li><b>Spanner</b>: creates tables with {@code partitionKey + id} primary key
 * columns in the configured database</li>
 * </ul>
 * <p>
 * The schema is defined as a map of database names to collection lists.
 * To add a new tenant or collection, simply update the {@link #SCHEMA} map —
 * no provider-specific provisioning code changes needed.
 */
public class ResourceProvisioner {

    /** Admin database storing the tenant registry. */
    private static final String ADMIN_DB = "riskplatform-admin";

    /** Databases to create, each with its required collections. */
    private static final Map<String, List<String>> SCHEMA;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(ADMIN_DB, List.of("tenants"));
        m.put("acme-capital-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts"));
        m.put("vanguard-partners-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts"));
        m.put("summit-wealth-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts"));
        m.put("_shared-risk-db", List.of("market_data"));
        SCHEMA = Collections.unmodifiableMap(m);
    }

    private final HyperscaleDbClient client;

    public ResourceProvisioner(HyperscaleDbClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Provision all required databases and containers/tables for any provider.
     * <p>
     * Iterates the schema and calls:
     * <ol>
     * <li>{@link HyperscaleDbClient#ensureDatabase(String)} for each database</li>
     * <li>{@link HyperscaleDbClient#ensureContainer(ResourceAddress)} for each
     * collection</li>
     * </ol>
     */
    public void provision() {
        System.out.println("  Provisioning resources for " + client.providerId().displayName() + "...");

        for (Map.Entry<String, List<String>> entry : SCHEMA.entrySet()) {
            System.out.println("    Database: " + entry.getKey());
            for (String collection : entry.getValue()) {
                System.out.println("      Container: " + collection);
            }
        }

        // Single SDK call — parallelism is handled internally by the provider
        client.provisionSchema(SCHEMA);

        System.out.println("  Resource provisioning complete.");
    }
}
