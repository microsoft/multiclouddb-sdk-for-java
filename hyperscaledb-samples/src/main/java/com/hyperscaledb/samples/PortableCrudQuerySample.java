// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples;

import com.hyperscaledb.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Portable CRUD + Query sample demonstrating Hyperscale DB SDK portability.
 * <p>
 * This sample runs the same code against any supported provider (Cosmos DB,
 * DynamoDB, or Spanner) by changing only the configuration file.
 * <p>
 * Usage:
 * 
 * <pre>
 *   # Run against Cosmos DB emulator (default)
 *   mvn exec:java -pl hyperscaledb-samples \
 *       -Dexec.mainClass=com.hyperscaledb.samples.PortableCrudQuerySample
 *
 *   # Run against DynamoDB Local
 *   mvn exec:java -pl hyperscaledb-samples \
 *       -Dexec.mainClass=com.hyperscaledb.samples.PortableCrudQuerySample \
 *       -Dhyperscaledb.config=todo-app-dynamo.properties
 *
 *   # Run against Spanner Emulator
 *   mvn exec:java -pl hyperscaledb-samples \
 *       -Dexec.mainClass=com.hyperscaledb.samples.PortableCrudQuerySample \
 *       -Dhyperscaledb.config=todo-app-spanner.properties
 * </pre>
 */
public class PortableCrudQuerySample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // Load config — switch providers by pointing to a different properties file
        ConfigLoader.AppConfig appConfig = ConfigLoader.load("todo-app-cosmos.properties");
        ProviderId provider = appConfig.sdk().provider();

        System.out.println("=== Hyperscale DB Portable CRUD + Query Sample ===");
        System.out.println("Provider: " + provider.displayName());
        System.out.println();

        // Resource address comes from config — no provider-specific code needed
        String database = appConfig.property("hyperscaledb.database", "todoapp");
        String collection = appConfig.property("hyperscaledb.collection", "todos");
        ResourceAddress address = new ResourceAddress(database, collection);

        try (HyperscaleDbClient client = HyperscaleDbClientFactory.create(appConfig.sdk())) {
            // === 1. UPSERT: Create documents ===
            System.out.println("--- UPSERT: Creating documents ---");
            for (int i = 1; i <= 5; i++) {
                Key key = Key.of("sample-" + i, "sample-" + i);
                ObjectNode doc = MAPPER.createObjectNode();
                doc.put("title", "Task " + i);
                doc.put("status", i <= 3 ? "active" : "completed");
                doc.put("priority", i);
                client.upsert(address, key, doc);
                System.out.println("  Created: sample-" + i + " (status=" + (i <= 3 ? "active" : "completed") + ")");
            }
            System.out.println();

            // === 2. READ: Retrieve a document ===
            System.out.println("--- READ: Retrieve document ---");
            Key getKey = Key.of("sample-1", "sample-1");
            JsonNode retrieved = client.read(address, getKey);
            if (retrieved != null) {
                System.out.println("  Retrieved: " + retrieved.toPrettyString());
            }
            System.out.println();

            // === 3. UPSERT: Update a document ===
            System.out.println("--- UPSERT: Update document ---");
            ObjectNode updated = MAPPER.createObjectNode();
            updated.put("title", "Task 1 (Updated)");
            updated.put("status", "completed");
            updated.put("priority", 1);
            client.upsert(address, getKey, updated);
            System.out.println("  Updated sample-1 status to 'completed'");
            System.out.println();

            // === 4. QUERY: Portable expression queries ===
            System.out.println("--- QUERY: Portable expressions ---");

            // 4a. Full scan with paging
            System.out.println("  [Full scan, pageSize=2]");
            QueryRequest fullScan = QueryRequest.builder()
                    .pageSize(2)
                    .build();
            QueryPage page = client.query(address, fullScan);
            System.out.println("    Page 1: " + page.items().size() + " items, hasMore=" + page.hasMore());

            // 4b. Portable filter: status = @status
            System.out.println("  [Filter: status = @status]");
            QueryRequest statusQuery = QueryRequest.builder()
                    .expression("status = @status")
                    .parameters(Map.of("status", "active"))
                    .pageSize(50)
                    .build();
            QueryPage activePage = client.query(address, statusQuery);
            System.out.println("    Found " + activePage.items().size() + " active items");

            // 4c. Portable filter with function: starts_with(title, @prefix)
            System.out.println("  [Filter: starts_with(title, @prefix)]");
            QueryRequest prefixQuery = QueryRequest.builder()
                    .expression("starts_with(title, @prefix)")
                    .parameters(Map.of("prefix", "Task"))
                    .pageSize(50)
                    .build();
            QueryPage prefixPage = client.query(address, prefixQuery);
            System.out.println("    Found " + prefixPage.items().size() + " items starting with 'Task'");

            // 4d. Combined filter
            System.out.println("  [Filter: status = @status AND priority > @minP]");
            QueryRequest combinedQuery = QueryRequest.builder()
                    .expression("status = @status AND priority > @minP")
                    .parameters(Map.of("status", "active", "minP", 1))
                    .pageSize(50)
                    .build();
            QueryPage combinedPage = client.query(address, combinedQuery);
            System.out.println("    Found " + combinedPage.items().size() + " active items with priority > 1");
            System.out.println();

            // === 5. CAPABILITIES: Check provider capabilities ===
            System.out.println("--- CAPABILITIES ---");
            CapabilitySet caps = client.capabilities();
            for (Capability cap : caps.all()) {
                System.out.println("  " + cap.name() + ": " + (cap.supported() ? "YES" : "NO")
                        + (cap.notes() != null ? " (" + cap.notes() + ")" : ""));
            }
            System.out.println();

            // === 6. Native expression fallback (escape hatch) ===
            System.out.println("--- NATIVE EXPRESSION (escape hatch) ---");
            // Native expressions use provider-specific syntax and physical table names.
            // For DynamoDB, the SDK resolves ResourceAddress(database, collection)
            // to the physical table "database__collection".
            String nativeExpr = switch (provider.id()) {
                case "cosmos" -> "SELECT * FROM c WHERE c.priority >= 3";
                case "dynamo" -> "SELECT * FROM \"" + database + "__" + collection + "\" WHERE priority >= 3";
                case "spanner" -> "SELECT * FROM " + collection + " WHERE priority >= 3";
                default -> null;
            };
            if (nativeExpr != null) {
                QueryRequest nativeQuery = QueryRequest.builder()
                        .nativeExpression(nativeExpr)
                        .pageSize(50)
                        .build();
                QueryPage nativePage = client.query(address, nativeQuery);
                System.out
                        .println("  Native query returned " + nativePage.items().size() + " items with priority >= 3");
            }
            System.out.println();

            // === 7. DELETE: Clean up ===
            System.out.println("--- DELETE: Cleaning up ---");
            for (int i = 1; i <= 5; i++) {
                client.delete(address, Key.of("sample-" + i, "sample-" + i));
                System.out.println("  Deleted: sample-" + i);
            }
            System.out.println();

            System.out.println("=== Sample complete ===");
        }
    }
}
