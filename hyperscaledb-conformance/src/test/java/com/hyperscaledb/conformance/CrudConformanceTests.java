// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance;

import com.hyperscaledb.api.*;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider-agnostic conformance tests exercising the portable CRUD + query
 * contract.
 * <p>
 * Subclass this and implement {@link #createClient()} plus
 * {@link #getAddress()} to run the full conformance suite against any provider.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class CrudConformanceTests {

    protected abstract HyperscaleDbClient createClient();
    protected abstract ResourceAddress getAddress();

    private HyperscaleDbClient client;

    @BeforeEach void setUp()   { client = createClient(); }
    @AfterEach  void tearDown() throws Exception { if (client != null) client.close(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> item, String field) {
        Object v = item.get(field);
        return v != null ? v.toString() : "";
    }
    private static int num(Map<String, Object> item, String field) {
        Object v = item.get(field);
        if (v instanceof Number n) return n.intValue();
        return v != null ? Integer.parseInt(v.toString()) : 0;
    }
    private static boolean bool(Map<String, Object> item, String field) {
        Object v = item.get(field);
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v != null ? v.toString() : "false");
    }

    // ── CRUD tests ────────────────────────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("upsert + read roundtrip")
    void upsertAndRead() {
        Key key = Key.of("conf-test-1", "conf-test-1");
        client.upsert(getAddress(), key,
                Map.of("title", "Conformance Test Item", "value", 42, "active", true));

        Map<String, Object> result = client.read(getAddress(), key);
        assertNotNull(result, "Document should be returned after upsert");
        assertEquals("Conformance Test Item", str(result, "title"));
        assertEquals(42, num(result, "value"));
        assertTrue(bool(result, "active"));
    }

    @Test @Order(2)
    @DisplayName("upsert overwrites existing document")
    void upsertOverwrites() {
        Key key = Key.of("conf-test-upsert", "conf-test-upsert");
        client.upsert(getAddress(), key, Map.of("version", 1));
        client.upsert(getAddress(), key, Map.of("version", 2, "extra", "field"));

        Map<String, Object> result = client.read(getAddress(), key);
        assertNotNull(result);
        assertEquals(2, num(result, "version"));
        assertTrue(result.containsKey("extra"));
        client.delete(getAddress(), key);
    }

    @Test @Order(3)
    @DisplayName("read returns null for nonexistent key")
    void readNonExistent() {
        assertNull(client.read(getAddress(), Key.of("does-not-exist-xyz", "does-not-exist-xyz")),
                "Should return null for nonexistent document");
    }

    @Test @Order(4)
    @DisplayName("delete removes document")
    void deleteDocument() {
        Key key = Key.of("conf-test-delete", "conf-test-delete");
        client.upsert(getAddress(), key, Map.of("title", "To be deleted"));
        assertNotNull(client.read(getAddress(), key));
        client.delete(getAddress(), key);
        assertNull(client.read(getAddress(), key));
    }

    @Test @Order(5)
    @DisplayName("delete of nonexistent key is idempotent")
    void deleteIdempotent() {
        assertDoesNotThrow(() -> client.delete(getAddress(), Key.of("never-existed-xyz", "never-existed-xyz")));
    }

    @Test @Order(6)
    @DisplayName("query returns items")
    void queryAll() {
        for (int i = 1; i <= 3; i++) {
            client.upsert(getAddress(), Key.of("conf-query-" + i, "conf-query-" + i),
                    Map.of("title", "Query Item " + i, "batch", "conformance"));
        }
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder().expression("SELECT * FROM c").pageSize(50).build());
        assertNotNull(page);
        assertFalse(page.items().isEmpty(), "Query should return at least our inserted items");
        for (int i = 1; i <= 3; i++) client.delete(getAddress(), Key.of("conf-query-" + i, "conf-query-" + i));
    }

    @Test @Order(7)
    @DisplayName("query with page size limits results")
    void queryPaging() {
        for (int i = 1; i <= 5; i++) {
            client.upsert(getAddress(), Key.of("conf-page-" + i, "conf-page-" + i),
                    Map.of("title", "Page Item " + i));
        }
        QueryPage page1 = client.query(getAddress(),
                QueryRequest.builder().expression("SELECT * FROM c").pageSize(2).build());
        assertNotNull(page1);
        assertTrue(page1.items().size() <= 2, "Page should respect pageSize limit");
        for (int i = 1; i <= 5; i++) client.delete(getAddress(), Key.of("conf-page-" + i, "conf-page-" + i));
    }

    @Test @Order(8)
    @DisplayName("capabilities returns non-empty set")
    void capabilities() {
        CapabilitySet caps = client.capabilities();
        assertNotNull(caps);
        assertFalse(caps.all().isEmpty(), "Provider should declare at least one capability");
    }

    @Test @Order(9)
    @DisplayName("providerId matches expected provider")
    void providerId() {
        assertNotNull(client.providerId());
    }

    @Test @Order(10)
    @DisplayName("cleanup conformance test items")
    void cleanup() {
        client.delete(getAddress(), Key.of("conf-test-1", "conf-test-1"));
    }

    // ── Partition-key-scoped query tests ──────────────────────────────────────

    @Test @Order(11)
    @DisplayName("partitionKey scopes query to matching items only")
    void queryByPartitionKey() {
        for (int i = 1; i <= 3; i++)
            client.upsert(getAddress(), Key.of("alpha", "pk-alpha-" + i),
                    Map.of("title", "Alpha Item " + i, "group", "alpha"));
        for (int i = 1; i <= 2; i++)
            client.upsert(getAddress(), Key.of("beta", "pk-beta-" + i),
                    Map.of("title", "Beta Item " + i, "group", "beta"));

        QueryPage alphaPage = client.query(getAddress(),
                QueryRequest.builder().partitionKey("alpha").pageSize(100).build());
        assertNotNull(alphaPage);
        assertEquals(3, alphaPage.items().size(), "Partition 'alpha' should contain exactly 3 items");
        for (Map<String, Object> item : alphaPage.items())
            assertEquals("alpha", str(item, "group"), "All items should belong to the alpha group");

        QueryPage betaPage = client.query(getAddress(),
                QueryRequest.builder().partitionKey("beta").pageSize(100).build());
        assertNotNull(betaPage);
        assertEquals(2, betaPage.items().size(), "Partition 'beta' should contain exactly 2 items");

        for (int i = 1; i <= 3; i++) client.delete(getAddress(), Key.of("alpha", "pk-alpha-" + i));
        for (int i = 1; i <= 2; i++) client.delete(getAddress(), Key.of("beta", "pk-beta-" + i));
    }

    @Test @Order(12)
    @DisplayName("partitionKey null falls back to cross-partition query")
    void queryWithoutPartitionKey() {
        client.upsert(getAddress(), Key.of("cross-a", "pk-cross-a"), Map.of("title", "Cross-A"));
        client.upsert(getAddress(), Key.of("cross-b", "pk-cross-b"), Map.of("title", "Cross-B"));

        QueryPage page = client.query(getAddress(),
                QueryRequest.builder().pageSize(200).build());
        assertNotNull(page);
        assertTrue(page.items().size() >= 2,
                "Cross-partition query should return items from multiple partitions");

        client.delete(getAddress(), Key.of("cross-a", "pk-cross-a"));
        client.delete(getAddress(), Key.of("cross-b", "pk-cross-b"));
    }

    @Test @Order(13)
    @DisplayName("partitionKey for nonexistent partition returns empty result")
    void queryNonexistentPartition() {
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder().partitionKey("nonexistent-partition-xyz").pageSize(100).build());
        assertNotNull(page);
        assertTrue(page.items().isEmpty(),
                "Query on nonexistent partition should return no items");
    }
}
