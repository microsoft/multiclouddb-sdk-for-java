// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples.riskplatform.infra;

import com.hyperscaledb.api.HyperscaleDbClient;

import java.util.*;

/**
 * Pre-creates all databases and containers/tables required by the Risk Platform.
 * <p>
 * Uses {@link HyperscaleDbClient#provisionSchema} — a single portable call that
 * works across Cosmos DB, DynamoDB, and Spanner. Existing resources are left
 * unchanged (all operations are idempotent).
 * <p>
 * <b>Cosmos DB + RBAC note:</b> if your identity only has the data-plane
 * <em>Built-in Data Contributor</em> role, database creation will fail with
 * 403 Forbidden. Pre-create the databases via the Azure Portal or CLI, then
 * run the app — container creation will still work via the data-plane.
 */
public class ResourceProvisioner {

    /** Admin database storing the tenant registry. */
    private static final String ADMIN_DB = "riskplatform-admin";

    /** Full schema: database name → collections to ensure. */
    private static final Map<String, List<String>> SCHEMA;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(ADMIN_DB,                   List.of("tenants"));
        m.put("acme-capital-risk-db",     List.of("portfolios", "positions", "risk_metrics", "alerts"));
        m.put("vanguard-partners-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts"));
        m.put("summit-wealth-risk-db",    List.of("portfolios", "positions", "risk_metrics", "alerts"));
        m.put("_shared-risk-db",          List.of("market_data"));
        SCHEMA = Collections.unmodifiableMap(m);
    }

    private final HyperscaleDbClient client;

    public ResourceProvisioner(HyperscaleDbClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Provisions all required databases and containers for any provider.
     * Existing resources are left unchanged.
     */
    public void provision() {
        System.out.println("  Provisioning resources for " + client.providerId().displayName() + "...");
        for (Map.Entry<String, List<String>> entry : SCHEMA.entrySet()) {
            System.out.println("    Database: " + entry.getKey());
            for (String collection : entry.getValue()) {
                System.out.println("      Container: " + collection);
            }
        }
        client.provisionSchema(SCHEMA);
        System.out.println("  Resource provisioning complete.");
    }
}
