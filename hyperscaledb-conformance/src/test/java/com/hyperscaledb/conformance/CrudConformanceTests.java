package com.hyperscaledb.conformance;

import com.hyperscaledb.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider-agnostic conformance tests exercising the portable CRUD + query
 * contract.
 * <p>
 * Subclass this and implement {@link #createClient()} plus
 * {@link #getAddress()} to run
 * the full conformance suite against any provider.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class CrudConformanceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected abstract HyperscaleDbClient createClient();

    protected abstract ResourceAddress getAddress();

    private HyperscaleDbClient client;

    @BeforeEach
    void setUp() {
        client = createClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("upsert + read roundtrip")
    void upsertAndRead() {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("title", "Conformance Test Item");
        doc.put("value", 42);
        doc.put("active", true);

        Key key = Key.of("conf-test-1", "conf-test-1");
        client.upsert(getAddress(), key, doc);

        JsonNode result = client.read(getAddress(), key);
        assertNotNull(result, "Document should be returned after upsert");
        assertEquals("Conformance Test Item", result.get("title").asText());
        assertEquals(42, result.get("value").asInt());
        assertTrue(result.get("active").asBoolean());
    }

    @Test
    @Order(2)
    @DisplayName("upsert overwrites existing document")
    void upsertOverwrites() {
        Key key = Key.of("conf-test-upsert", "conf-test-upsert");

        ObjectNode doc1 = MAPPER.createObjectNode();
        doc1.put("version", 1);
        client.upsert(getAddress(), key, doc1);

        ObjectNode doc2 = MAPPER.createObjectNode();
        doc2.put("version", 2);
        doc2.put("extra", "field");
        client.upsert(getAddress(), key, doc2);

        JsonNode result = client.read(getAddress(), key);
        assertNotNull(result);
        assertEquals(2, result.get("version").asInt());
        assertTrue(result.has("extra"));

        // cleanup
        client.delete(getAddress(), key);
    }

    @Test
    @Order(3)
    @DisplayName("read returns null for nonexistent key")
    void readNonExistent() {
        Key key = Key.of("does-not-exist-xyz", "does-not-exist-xyz");
        JsonNode result = client.read(getAddress(), key);
        assertNull(result, "Should return null for nonexistent document");
    }

    @Test
    @Order(4)
    @DisplayName("delete removes document")
    void deleteDocument() {
        Key key = Key.of("conf-test-delete", "conf-test-delete");

        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("title", "To be deleted");
        client.upsert(getAddress(), key, doc);

        // Verify it exists
        assertNotNull(client.read(getAddress(), key));

        // Delete
        client.delete(getAddress(), key);

        // Verify gone
        assertNull(client.read(getAddress(), key));
    }

    @Test
    @Order(5)
    @DisplayName("delete of nonexistent key is idempotent")
    void deleteIdempotent() {
        Key key = Key.of("never-existed-xyz", "never-existed-xyz");
        // Should not throw
        assertDoesNotThrow(() -> client.delete(getAddress(), key));
    }

    @Test
    @Order(6)
    @DisplayName("query returns items")
    void queryAll() {
        // Insert some items
        for (int i = 1; i <= 3; i++) {
            Key key = Key.of("conf-query-" + i, "conf-query-" + i);
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("title", "Query Item " + i);
            doc.put("batch", "conformance");
            client.upsert(getAddress(), key, doc);
        }

        // Query all
        QueryRequest query = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .pageSize(50)
                .build();

        QueryPage page = client.query(getAddress(), query);
        assertNotNull(page);
        assertFalse(page.items().isEmpty(), "Query should return at least our inserted items");

        // Cleanup
        for (int i = 1; i <= 3; i++) {
            client.delete(getAddress(), Key.of("conf-query-" + i, "conf-query-" + i));
        }
    }

    @Test
    @Order(7)
    @DisplayName("query with page size limits results")
    void queryPaging() {
        // Insert 5 items
        for (int i = 1; i <= 5; i++) {
            Key key = Key.of("conf-page-" + i, "conf-page-" + i);
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("title", "Page Item " + i);
            client.upsert(getAddress(), key, doc);
        }

        // Query with page size 2
        QueryRequest query = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .pageSize(2)
                .build();

        QueryPage page1 = client.query(getAddress(), query);
        assertNotNull(page1);
        assertTrue(page1.items().size() <= 2, "Page should respect pageSize limit");

        // Cleanup
        for (int i = 1; i <= 5; i++) {
            client.delete(getAddress(), Key.of("conf-page-" + i, "conf-page-" + i));
        }
    }

    @Test
    @Order(8)
    @DisplayName("capabilities returns non-empty set")
    void capabilities() {
        CapabilitySet caps = client.capabilities();
        assertNotNull(caps);
        assertFalse(caps.all().isEmpty(), "Provider should declare at least one capability");
    }

    @Test
    @Order(9)
    @DisplayName("providerId matches expected provider")
    void providerId() {
        ProviderId id = client.providerId();
        assertNotNull(id);
    }

    @Test
    @Order(10)
    @DisplayName("cleanup conformance test items")
    void cleanup() {
        // Clean up the item from test 1
        client.delete(getAddress(), Key.of("conf-test-1", "conf-test-1"));
    }

    // ── Partition-key-scoped query tests (US1e) ─────────────────────────────

    @Test
    @Order(11)
    @DisplayName("partitionKey scopes query to matching items only")
    void queryByPartitionKey() {
        // Insert items across two partition keys
        for (int i = 1; i <= 3; i++) {
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("title", "Alpha Item " + i);
            doc.put("group", "alpha");
            client.upsert(getAddress(), Key.of("alpha", "pk-alpha-" + i), doc);
        }
        for (int i = 1; i <= 2; i++) {
            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("title", "Beta Item " + i);
            doc.put("group", "beta");
            client.upsert(getAddress(), Key.of("beta", "pk-beta-" + i), doc);
        }

        // Query scoped to "alpha" partition
        QueryPage alphaPage = client.query(getAddress(),
                QueryRequest.builder()
                        .partitionKey("alpha")
                        .pageSize(100)
                        .build());
        assertNotNull(alphaPage);
        assertEquals(3, alphaPage.items().size(),
                "Partition 'alpha' should contain exactly 3 items");
        for (JsonNode item : alphaPage.items()) {
            assertEquals("alpha", item.path("group").asText(),
                    "All items should belong to the alpha group");
        }

        // Query scoped to "beta" partition
        QueryPage betaPage = client.query(getAddress(),
                QueryRequest.builder()
                        .partitionKey("beta")
                        .pageSize(100)
                        .build());
        assertNotNull(betaPage);
        assertEquals(2, betaPage.items().size(),
                "Partition 'beta' should contain exactly 2 items");

        // Cleanup
        for (int i = 1; i <= 3; i++) {
            client.delete(getAddress(), Key.of("alpha", "pk-alpha-" + i));
        }
        for (int i = 1; i <= 2; i++) {
            client.delete(getAddress(), Key.of("beta", "pk-beta-" + i));
        }
    }

    @Test
    @Order(12)
    @DisplayName("partitionKey null falls back to cross-partition query")
    void queryWithoutPartitionKey() {
        // Insert items in two partitions
        ObjectNode docA = MAPPER.createObjectNode();
        docA.put("title", "Cross-A");
        client.upsert(getAddress(), Key.of("cross-a", "pk-cross-a"), docA);

        ObjectNode docB = MAPPER.createObjectNode();
        docB.put("title", "Cross-B");
        client.upsert(getAddress(), Key.of("cross-b", "pk-cross-b"), docB);

        // Query without partition key — should return items from all partitions
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder()
                        .pageSize(200)
                        .build());
        assertNotNull(page);
        assertTrue(page.items().size() >= 2,
                "Cross-partition query should return items from multiple partitions");

        // Cleanup
        client.delete(getAddress(), Key.of("cross-a", "pk-cross-a"));
        client.delete(getAddress(), Key.of("cross-b", "pk-cross-b"));
    }

    @Test
    @Order(13)
    @DisplayName("partitionKey for nonexistent partition returns empty result")
    void queryNonexistentPartition() {
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder()
                        .partitionKey("nonexistent-partition-xyz")
                        .pageSize(100)
                        .build());
        assertNotNull(page);
        assertTrue(page.items().isEmpty(),
                "Query on nonexistent partition should return no items");
    }
}
