package com.hyperscaledb.conformance.us1b;

import com.hyperscaledb.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for portable query expressions against Cosmos DB Emulator.
 * <p>
 * Inserts real data, runs portable WHERE-clause expressions through the full
 * parse → validate → translate → execute pipeline, and verifies filtered
 * results.
 * <p>
 * Prerequisites: Cosmos DB Emulator running on https://localhost:8081
 * with database "todoapp" and container "todos" (partition key: /partitionKey).
 */
@DisplayName("Cosmos DB — Portable Query Integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CosmosQueryIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENDPOINT = System.getProperty(
            "cosmos.endpoint", "https://localhost:8081");
    private static final String KEY = System.getProperty(
            "cosmos.key",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
    private static final String DATABASE = "todoapp";
    private static final String CONTAINER = "todos";

    private HyperscaleDbClient client;
    private final ResourceAddress address = new ResourceAddress(DATABASE, CONTAINER);

    @BeforeAll
    void setUp() {
        HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", ENDPOINT)
                .connection("key", KEY)
                .connection("connectionMode", "gateway")
                .build();
        client = HyperscaleDbClientFactory.create(config);

        // Insert test dataset
        insertDoc("qtest-1", "Buy groceries", "active", 1, "shopping");
        insertDoc("qtest-2", "Write report", "completed", 2, "work");
        insertDoc("qtest-3", "Book flight", "active", 3, "travel");
        insertDoc("qtest-4", "Read book", "active", 4, "personal");
        insertDoc("qtest-5", "Ship package", "completed", 5, "shopping");
        insertDoc("qtest-6", "Buy stamps", "active", 6, "shopping");

        System.out.println("[Cosmos] Inserted 6 test documents into " + DATABASE + "/" + CONTAINER);
    }

    @AfterAll
    void tearDown() throws Exception {
        // Cleanup test data
        for (int i = 1; i <= 6; i++) {
            try {
                client.delete(address, Key.of("qtest-" + i, "qtest-" + i));
            } catch (Exception ignored) {
            }
        }
        System.out.println("[Cosmos] Cleaned up test documents");
        if (client != null)
            client.close();
    }

    private void insertDoc(String id, String title, String status, int priority, String category) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("title", title);
        doc.put("status", status);
        doc.put("priority", priority);
        doc.put("category", category);
        client.upsert(address, Key.of(id, id), doc);
    }

    // ---- Portable Expression Integration Tests ----

    @Test
    @Order(1)
    @DisplayName("equality filter: status = @status returns only active items")
    void equalityFilter() {
        QueryRequest query = QueryRequest.builder()
                .expression("status = @status")
                .parameters(Map.of("status", "active"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        assertNotNull(page);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] equality filter returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": " + item.get("title").asText()
                    + " [status=" + item.get("status").asText() + "]");
        }

        assertFalse(items.isEmpty(), "Should find active items");
        // Every returned item must have status=active
        for (JsonNode item : items) {
            assertEquals("active", item.get("status").asText(),
                    "All returned items should have status=active");
        }
    }

    @Test
    @Order(2)
    @DisplayName("AND filter: status = @status AND category = @cat")
    void andFilter() {
        QueryRequest query = QueryRequest.builder()
                .expression("status = @status AND category = @cat")
                .parameters(Map.of("status", "active", "cat", "shopping"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] AND filter returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": " + item.get("title").asText());
        }

        assertFalse(items.isEmpty(), "Should find active shopping items");
        for (JsonNode item : items) {
            assertEquals("active", item.get("status").asText());
            assertEquals("shopping", item.get("category").asText());
        }
    }

    @Test
    @Order(3)
    @DisplayName("comparison filter: priority > @minPriority")
    void comparisonFilter() {
        QueryRequest query = QueryRequest.builder()
                .expression("priority > @minPriority")
                .parameters(Map.of("minPriority", 3))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] comparison filter (priority > 3) returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": priority=" + item.get("priority").asInt());
        }

        assertFalse(items.isEmpty(), "Should find items with priority > 3");
        for (JsonNode item : items) {
            assertTrue(item.get("priority").asInt() > 3,
                    "All items should have priority > 3");
        }
    }

    @Test
    @Order(4)
    @DisplayName("starts_with function: starts_with(title, @prefix)")
    void startsWithFunction() {
        QueryRequest query = QueryRequest.builder()
                .expression("starts_with(title, @prefix)")
                .parameters(Map.of("prefix", "Buy"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] starts_with('Buy') returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": " + item.get("title").asText());
        }

        assertFalse(items.isEmpty(), "Should find items starting with 'Buy'");
        for (JsonNode item : items) {
            assertTrue(item.get("title").asText().startsWith("Buy"),
                    "All items should have title starting with 'Buy'");
        }
    }

    @Test
    @Order(5)
    @DisplayName("contains function: contains(title, @substr)")
    void containsFunction() {
        QueryRequest query = QueryRequest.builder()
                .expression("contains(title, @substr)")
                .parameters(Map.of("substr", "book"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] contains('book') returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": " + item.get("title").asText());
        }

        // Cosmos SQL CONTAINS is case-sensitive, so "book" should match "Book" only if
        // casing matches
        // "Read book" has lowercase "book" — should match
        // "Book flight" has uppercase "Book" — Cosmos CONTAINS is case-sensitive, won't
        // match "book"
        for (JsonNode item : items) {
            assertTrue(item.get("title").asText().contains("book"),
                    "Returned items should contain 'book' in title");
        }
    }

    @Test
    @Order(6)
    @DisplayName("NOT expression: NOT status = @status")
    void notExpression() {
        QueryRequest query = QueryRequest.builder()
                .expression("NOT status = @status")
                .parameters(Map.of("status", "active"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] NOT active returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": status=" + item.get("status").asText());
        }

        assertFalse(items.isEmpty(), "Should find non-active items");
        for (JsonNode item : items) {
            assertNotEquals("active", item.get("status").asText(),
                    "All returned items should NOT be active");
        }
    }

    @Test
    @Order(7)
    @DisplayName("OR expression: category = @cat1 OR category = @cat2")
    void orExpression() {
        QueryRequest query = QueryRequest.builder()
                .expression("category = @cat1 OR category = @cat2")
                .parameters(Map.of("cat1", "travel", "cat2", "personal"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] OR (travel|personal) returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": category=" + item.get("category").asText());
        }

        assertFalse(items.isEmpty(), "Should find travel or personal items");
        for (JsonNode item : items) {
            String cat = item.get("category").asText();
            assertTrue("travel".equals(cat) || "personal".equals(cat),
                    "Each item should be travel or personal, got: " + cat);
        }
    }

    @Test
    @Order(8)
    @DisplayName("complex compound: status = @s AND (category = @c1 OR category = @c2)")
    void complexCompound() {
        QueryRequest query = QueryRequest.builder()
                .expression("status = @s AND (category = @c1 OR category = @c2)")
                .parameters(Map.of("s", "active", "c1", "shopping", "c2", "travel"))
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] complex compound returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": "
                    + item.get("title").asText() + " [" + item.get("status").asText()
                    + ", " + item.get("category").asText() + "]");
        }

        assertFalse(items.isEmpty(), "Should find active items in shopping or travel");
        for (JsonNode item : items) {
            assertEquals("active", item.get("status").asText());
            String cat = item.get("category").asText();
            assertTrue("shopping".equals(cat) || "travel".equals(cat),
                    "Category should be shopping or travel, got: " + cat);
        }
    }

    @Test
    @Order(9)
    @DisplayName("native expression passthrough: Cosmos SQL with LIKE")
    void nativeExpressionPassthrough() {
        QueryRequest query = QueryRequest.builder()
                .nativeExpression("SELECT * FROM c WHERE c.title LIKE '%flight%'")
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] native LIKE returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("id").asText() + ": " + item.get("title").asText());
        }

        assertFalse(items.isEmpty(), "Should find items with 'flight' in title");
        for (JsonNode item : items) {
            assertTrue(item.get("title").asText().toLowerCase().contains("flight"));
        }
    }

    @Test
    @Order(10)
    @DisplayName("all data visible: full scan returns all 6 test items")
    void fullScanShowsAllData() {
        QueryRequest query = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .pageSize(100)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Cosmos] Full scan returned " + items.size() + " items total");

        long testItems = items.stream()
                .filter(item -> item.has("id") && item.get("id").asText().startsWith("qtest-"))
                .count();
        System.out.println("[Cosmos] Found " + testItems + " test items (qtest-*) in emulator");
        for (JsonNode item : items) {
            if (item.has("id") && item.get("id").asText().startsWith("qtest-")) {
                System.out.println("  -> " + item.get("id").asText()
                        + " | title=" + item.get("title").asText()
                        + " | status=" + item.get("status").asText()
                        + " | priority=" + item.get("priority").asInt()
                        + " | category=" + item.get("category").asText());
            }
        }

        assertTrue(testItems >= 6, "Should find all 6 test items in emulator, found: " + testItems);
    }
}
