// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.e2e;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * End-to-end portability test for the Multicloud DB SDK.
 *
 * <p>Runs the same CRUD + query calls against whichever provider is configured
 * in the active properties file. Switch providers without changing any code:
 * <pre>
 *   mvn -pl multiclouddb-e2e process-resources exec:java                                          # Cosmos DB (default)
 *   mvn -pl multiclouddb-e2e process-resources exec:java -Dmulticlouddb.config=dynamo.properties
 *   mvn -pl multiclouddb-e2e process-resources exec:java -Dmulticlouddb.config=spanner.properties
 * </pre>
 *
 * <p>Copy and fill in the appropriate {@code *.properties} file under
 * {@code src/main/resources/} before running. See the README for details.
 */
public class Main {

    private final MulticloudDbClient client;
    private final ResourceAddress address;

    Main(MulticloudDbClient client, ResourceAddress address) {
        this.client  = client;
        this.address = address;
    }

    public static void main(String[] args) throws Exception {

        // ── Load config ───────────────────────────────────────────────────
        ConfigLoader.AppConfig cfg = ConfigLoader.load("cosmos.properties");
        String database   = cfg.get("multiclouddb.database",   "e2etest");
        String collection = cfg.get("multiclouddb.collection", "products");

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        Multicloud DB SDK — E2E Portability Test              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf(" Provider  : %s%n", cfg.sdk().provider().displayName());
        System.out.printf(" Database  : %s%n", database);
        System.out.printf(" Collection: %s%n", collection);
        System.out.println();

        try (MulticloudDbClient c = MulticloudDbClientFactory.create(cfg.sdk())) {
            new Main(c, new ResourceAddress(database, collection)).run(cfg);
        }
    }

    void run(ConfigLoader.AppConfig cfg) {

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
        client.ensureDatabase(address.database());
        client.ensureContainer(address);
        System.out.println("  Database and collection are ready.");
        System.out.println();

        // ── SDK warm-up ───────────────────────────────────────────────
        // Prime both the read-path and write-path metadata caches before the
        // timed test operations begin. A query alone only warms the read path;
        // the first real upsert would still incur a write-path metadata lookup
        // (~20–80ms) and fire cosmos.slow WARN logs.
        System.out.println("── SDK warm-up ────────────────────────────────────────────────");
        client.query(address, QueryRequest.builder().maxPageSize(1).build());
        MulticloudDbKey warmupKey = MulticloudDbKey.of("__warmup__", "__warmup__");
        client.upsert(address, warmupKey, Map.of("id", "__warmup__"));
        client.delete(address, warmupKey);
        System.out.println("  Read and write metadata cached.");
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
        DocumentResult readResult = read("prod-001");
        if (readResult == null) {
            throw new AssertionError("Expected document for prod-001 but got null");
        }
        System.out.println();

        if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
            // ── PARTIAL UPDATE ────────────────────────────────────────
            System.out.println("── PARTIAL UPDATE (prod-005: price + stock only) ─────────────");
            update("prod-005", Map.of("price", 54.99, "inStock", true));
            System.out.println();

            // ── READ (verify update) ──────────────────────────────────
            System.out.println("── READ (verify selected fields + omitted-field preservation) ─");
            DocumentResult updateResult = read("prod-005");
            if (updateResult == null) {
                throw new AssertionError("Expected document for prod-005 after update but got null");
            }
            if (!"USB-C Hub".equals(updateResult.document().path("name").asText())
                    || !"electronics".equals(updateResult.document().path("category").asText())
                    || Math.abs(updateResult.document().path("price").asDouble() - 54.99) > 0.0001
                    || !updateResult.document().path("inStock").asBoolean()) {
                throw new AssertionError("Partial update did not preserve omitted product fields");
            }
            System.out.println("    → selected fields changed; name and category were preserved");
            System.out.println();

            System.out.println("── WIDE PARTIAL UPDATE (>10 fields, Cosmos/Dynamo) ───────────");
            wideUpdate("prod-004");
            System.out.println();
        } else {
            System.out.println("── PARTIAL UPDATE ─────────────────────────────────────────────");
            System.out.printf("  Skipped: %s does not advertise partial_update.%n",
                    client.providerId().displayName());
            System.out.println();
        }

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

        // ── LIST (confirm deletion, expect 4 items) ───────────────────
        System.out.println("── LIST (after delete, expect 4 items) ───────────────────────");
        int remaining = query(QueryRequest.builder()
                .maxPageSize(10)
                .build());
        if (remaining != 4) {
            throw new AssertionError("Expected 4 items after deleting prod-003 but got " + remaining);
        }
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

    // ── CRUD helpers — each one prints the raw SDK call it makes ─────────

    private void upsert(String id, String name, String category,
                        double price, boolean inStock) {
        MulticloudDbKey key = MulticloudDbKey.of(id, id);
        Map<String, Object> doc = Map.of(
                "id", id, "name", name, "category", category,
                "price", price, "inStock", inStock);

        System.out.printf("  client.upsert(address, key(%s), doc)%n", id);
        client.upsert(address, key, doc);
        System.out.printf("    → upserted: %s | %s | $%.2f | inStock=%b%n",
                id, name, price, inStock);
    }

    private DocumentResult read(String id) {
        MulticloudDbKey key = MulticloudDbKey.of(id, id);

        System.out.printf("  client.read(address, key(%s))%n", id);
        DocumentResult result = client.read(address, key);
        if (result != null) {
            System.out.println("    → " + result.document().toPrettyString());
        } else {
            System.out.println("    → not found");
        }
        return result;
    }

    private void update(String id, Map<String, Object> fields) {
        MulticloudDbKey key = MulticloudDbKey.of(id, id);

        System.out.printf("  client.update(address, key(%s), fields=%s)%n", id, fields.keySet());
        client.update(address, key, fields);
        System.out.println("    → partial update applied");
    }

    private void wideUpdate(String id) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "wide-title");
        fields.put("value", 101);
        fields.put("active", true);
        fields.put("version", 2);
        fields.put("extra", "wide-extra");
        fields.put("batch", "wide-batch");
        fields.put("status", "wide-status");
        fields.put("priority", 9);
        fields.put("category", "wide-category");
        fields.put("shared", "wide-shared");
        fields.put("originalOnly", "wide-original");

        update(id, fields);
        DocumentResult result = read(id);
        if (result == null) {
            throw new AssertionError("Expected document for " + id + " after wide update");
        }
        fields.forEach((name, expected) -> {
            String actual = result.document().path(name).asText();
            if (!String.valueOf(expected).equals(actual)) {
                throw new AssertionError("Wide update mismatch for " + name
                        + ": expected=" + expected + ", actual=" + actual);
            }
        });
        if (!"Notebook (paper)".equals(result.document().path("name").asText())
                || Math.abs(result.document().path("price").asDouble() - 4.99) > 0.0001
                || !result.document().path("inStock").asBoolean()) {
            throw new AssertionError("Wide partial update did not preserve omitted product fields");
        }
        System.out.println("    → 11 fields changed in one logical update; omitted product fields preserved");
    }

    private void delete(String id) {
        MulticloudDbKey key = MulticloudDbKey.of(id, id);

        System.out.printf("  client.delete(address, key(%s))%n", id);
        client.delete(address, key);
        System.out.println("    → deleted");
    }

    /** Executes a query (looping through all pages) and returns total items found. */
    private int query(QueryRequest request) {
        if (request.expression() != null) {
            System.out.printf("  client.query(address, expression=\"%s\", params=%s)%n",
                    request.expression(), request.parameters());
        } else {
            System.out.printf("  client.query(address, maxPageSize=%s)%n",
                    request.maxPageSize() != null ? request.maxPageSize() : "(default)");
        }

        QueryRequest currentRequest = request;
        int pageNumber = 1;
        int totalItems = 0;

        while (true) {
            QueryPage page = client.query(address, currentRequest);
            totalItems += page.items().size();

            System.out.printf("    → page %d: %d item(s) returned%s%n",
                    pageNumber,
                    page.items().size(),
                    page.continuationToken() != null ? "  [more pages available]" : "");
            for (var item : page.items()) {
                System.out.println("      • " + item);
            }

            if (page.continuationToken() == null) {
                break;
            }

            currentRequest = QueryRequest.builder()
                    .continuationToken(page.continuationToken())
                    .build();
            pageNumber++;
        }

        System.out.printf("    → total: %d item(s) across %d page(s)%n", totalItems, pageNumber);
        return totalItems;
    }
}
