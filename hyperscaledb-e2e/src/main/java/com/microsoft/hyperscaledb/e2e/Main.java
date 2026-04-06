// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.hyperscaledb.e2e;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.DocumentResult;
import com.hyperscaledb.api.HyperscaleDbClient;
import com.hyperscaledb.api.HyperscaleDbClientFactory;
import com.hyperscaledb.api.HyperscaleDbKey;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;

import java.util.Map;

/**
 * End-to-end portability test for the Hyperscale DB SDK.
 *
 * <p>Runs the same CRUD + query calls against whichever provider is configured
 * in the active properties file. Switch providers without changing any code:
 * <pre>
 *   mvn -pl hyperscaledb-e2e exec:java                                          # Cosmos DB (default)
 *   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=dynamo.properties
 *   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=spanner.properties
 * </pre>
 *
 * <p>Copy and fill in the appropriate {@code *.properties} file under
 * {@code src/main/resources/} before running. See the README for details.
 */
public class Main {

    // Shared state for helper methods
    private static HyperscaleDbClient client;
    private static ResourceAddress address;

    public static void main(String[] args) throws Exception {

        // ── Load config ───────────────────────────────────────────────────
        ConfigLoader.AppConfig cfg = ConfigLoader.load("cosmos.properties");
        String database   = cfg.get("hyperscaledb.database",   "e2etest");
        String collection = cfg.get("hyperscaledb.collection", "products");

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        Hyperscale DB SDK — E2E Portability Test              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf(" Provider  : %s%n", cfg.sdk().provider().displayName());
        System.out.printf(" Database  : %s%n", database);
        System.out.printf(" Collection: %s%n", collection);
        System.out.println();

        try (HyperscaleDbClient c = HyperscaleDbClientFactory.create(cfg.sdk())) {
            client  = c;
            address = new ResourceAddress(database, collection);

            // ── Provider capabilities ─────────────────────────────────────
            System.out.println("── Provider capabilities ──────────────────────────────────────");
            for (Capability cap : client.capabilities().all()) {
                String tick  = cap.supported() ? "✓" : "✗";
                String notes = cap.notes() != null ? "  (" + cap.notes() + ")" : "";
                System.out.printf("  %s  %-30s%s%n", tick, cap.name(), notes);
            }
            System.out.println();

            // ── Schema provisioning ───────────────────────────────────────
            System.out.println("── Schema provisioning ────────────────────────────────────────");
            client.ensureDatabase(database);
            client.ensureContainer(address);
            System.out.println("  Database and collection are ready.");
            System.out.println();

            // ── CREATE ────────────────────────────────────────────────────
            System.out.println("── CREATE ─────────────────────────────────────────────────────");
            upsert("prod-001", "Laptop Pro 15",    "electronics", 1299.99, true);
            upsert("prod-002", "Wireless Mouse",   "electronics",   29.99, true);
            upsert("prod-003", "Desk Lamp",        "furniture",     49.99, false);
            upsert("prod-004", "Notebook (paper)", "stationery",     4.99, true);
            upsert("prod-005", "USB-C Hub",        "electronics",   59.99, false);
            System.out.println();

            // ── READ ──────────────────────────────────────────────────────
            System.out.println("── READ ───────────────────────────────────────────────────────");
            read("prod-001");
            System.out.println();

            // ── UPDATE ────────────────────────────────────────────────────
            System.out.println("── UPDATE (prod-005: mark in-stock, new price) ────────────────");
            upsert("prod-005", "USB-C Hub", "electronics", 54.99, true);
            System.out.println();

            // ── READ (verify update) ──────────────────────────────────────
            System.out.println("── READ (verify update) ───────────────────────────────────────");
            read("prod-005");
            System.out.println();

            // ── LIST (full scan, paged) ───────────────────────────────────
            System.out.println("── LIST (pageSize=3) ──────────────────────────────────────────");
            query(QueryRequest.builder()
                    .maxPageSize(3)
                    .build());
            System.out.println();

            // ── QUERY: filter by category ─────────────────────────────────
            System.out.println("── QUERY  category = 'electronics' ────────────────────────────");
            query(QueryRequest.builder()
                    .expression("category = @cat")
                    .parameters(Map.of("cat", "electronics"))
                    .maxPageSize(50)
                    .build());
            System.out.println();

            // ── QUERY: compound filter ────────────────────────────────────
            System.out.println("── QUERY  inStock = true AND price < 100 ──────────────────────");
            query(QueryRequest.builder()
                    .expression("inStock = @inStock AND price < @maxPrice")
                    .parameters(Map.of("inStock", true, "maxPrice", 100.00))
                    .maxPageSize(50)
                    .build());
            System.out.println();

            // ── DELETE ────────────────────────────────────────────────────
            System.out.println("── DELETE (prod-003) ──────────────────────────────────────────");
            delete("prod-003");
            System.out.println();

            // ── LIST (confirm deletion) ───────────────────────────────────
            System.out.println("── LIST (after delete, expect 4 items) ───────────────────────");
            query(QueryRequest.builder()
                    .maxPageSize(10)
                    .build());
            System.out.println();

            // ── CLEANUP ───────────────────────────────────────────────────
            System.out.println("── CLEANUP ────────────────────────────────────────────────────");
            for (String id : new String[]{"prod-001", "prod-002", "prod-004", "prod-005"}) {
                delete(id);
            }
            System.out.println();

            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.printf ("║  ✓  E2E test completed successfully on %-20s  ║%n",
                    cfg.sdk().provider().displayName());
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
        }
    }

    // ── CRUD helpers — each one prints the raw SDK call it makes ─────────

    private static void upsert(String id, String name, String category,
                                double price, boolean inStock) {
        HyperscaleDbKey key = HyperscaleDbKey.of(id, id);
        Map<String, Object> doc = Map.of(
                "id", id, "name", name, "category", category,
                "price", price, "inStock", inStock);

        System.out.printf("  client.upsert(address, key(%s), doc)%n", id);
        client.upsert(address, key, doc);
        System.out.printf("    → upserted: %s | %s | $%.2f | inStock=%b%n",
                id, name, price, inStock);
    }

    private static void read(String id) {
        HyperscaleDbKey key = HyperscaleDbKey.of(id, id);

        System.out.printf("  client.read(address, key(%s))%n", id);
        DocumentResult result = client.read(address, key);
        if (result != null) {
            System.out.println("    → " + result.document().toPrettyString());
        } else {
            System.out.println("    → not found");
        }
    }

    private static void delete(String id) {
        HyperscaleDbKey key = HyperscaleDbKey.of(id, id);

        System.out.printf("  client.delete(address, key(%s))%n", id);
        client.delete(address, key);
        System.out.println("    → deleted");
    }

    private static void query(QueryRequest request) {
        if (request.expression() != null) {
            System.out.printf("  client.query(address, expression=\"%s\", params=%s)%n",
                    request.expression(), request.parameters());
        } else {
            System.out.printf("  client.query(address, maxPageSize=%d)%n",
                    request.maxPageSize());
        }
        QueryPage page = client.query(address, request);
        System.out.printf("    → %d item(s) returned%s%n",
                page.items().size(),
                page.continuationToken() != null ? "  [more pages available]" : "");
        for (var item : page.items()) {
            System.out.println("      • " + item);
        }
    }
}
