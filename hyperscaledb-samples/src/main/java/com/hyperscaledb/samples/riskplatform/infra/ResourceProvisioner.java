// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples.riskplatform.infra;

import com.hyperscaledb.api.HyperscaleDbClient;
import com.hyperscaledb.api.ResourceAddress;

import java.util.*;

/**
 * Pre-creates databases and containers/tables required by the Risk Platform.
 * <p>
 * <strong>Note:</strong> The SDK provisioning API ({@code ensureDatabase},
 * {@code ensureContainer}, {@code provisionSchema}) is deprecated and will be
 * removed. For production use, create resources with your provider's own tooling
 * before the application starts:
 * <ul>
 *   <li><b>Cosmos DB</b>: Azure Portal, ARM templates, Bicep, or Terraform</li>
 *   <li><b>DynamoDB</b>: AWS Console, CloudFormation, CDK, or Terraform</li>
 *   <li><b>Spanner</b>: GCP Console, Deployment Manager, or Terraform</li>
 * </ul>
 * This class keeps the deprecated calls for local-emulator / dev convenience only.
 */
@SuppressWarnings("deprecation")
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
     * Provision all required databases and containers/tables.
     * <p>
     * For local-emulator / dev use only. In production, create these resources
     * with Terraform, the Azure Portal, AWS Console, or GCP Console before
     * starting the application.
     *
     * @deprecated Provisioning will be removed from the SDK. Use your provider's
     *             own infrastructure tooling instead.
     */
    @Deprecated
    public void provision() {
        System.out.println("  Provisioning resources for " + client.providerId().displayName() + "...");
        System.out.println("  (Note: SDK provisioning is deprecated — use Terraform/IaC for production)");

        for (Map.Entry<String, List<String>> entry : SCHEMA.entrySet()) {
            String database = entry.getKey();
            System.out.println("    Database: " + database);
            client.ensureDatabase(database);
            for (String collection : entry.getValue()) {
                System.out.println("      Container: " + collection);
                client.ensureContainer(new ResourceAddress(database, collection));
            }
        }

        System.out.println("  Resource provisioning complete.");
    }
}
