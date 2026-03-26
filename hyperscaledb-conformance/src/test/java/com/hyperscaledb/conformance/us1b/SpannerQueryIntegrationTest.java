package com.hyperscaledb.conformance.us1b;

import com.hyperscaledb.api.HyperscaleDbClient;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.HyperscaleDbClientFactory;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for portable query expressions against the Spanner
 * Emulator.
 * <p>
 * Inserts real data, runs portable WHERE-clause expressions through the full
 * parse → validate → translate (GoogleSQL) → execute pipeline, and verifies
 * filtered results.
 * <p>
 * Prerequisites: Spanner Emulator running on localhost:9010 (gRPC).
 * The instance, database, and "querytests" table are auto-created.
 */
@DisplayName("Spanner — Portable Query Integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("spanner")
@Tag("emulator")
class SpannerQueryIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EMULATOR_HOST = System.getProperty(
            "spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID = "test-project";
    private static final String INSTANCE_ID = "test-instance";
    private static final String DATABASE_ID = "querytestdb";
    private static final String TABLE = "querytests";

    private HyperscaleDbClient client;
    private final ResourceAddress address = new ResourceAddress(DATABASE_ID, TABLE);

    @BeforeAll
    void setUp() throws ExecutionException, InterruptedException {
        // Ensure emulator has instance + database + table
        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST)
                .setProjectId(PROJECT_ID)
                .build();
        Spanner spanner = options.getService();
        try {
            // Create instance (idempotent)
            InstanceAdminClient instanceAdmin = spanner.getInstanceAdminClient();
            try {
                instanceAdmin.createInstance(
                        InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                                .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                                .setDisplayName("Test Instance")
                                .setNodeCount(1)
                                .build())
                        .get();
                System.out.println("[Spanner] Created instance: " + INSTANCE_ID);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                    System.out.println("[Spanner] Instance already exists: " + INSTANCE_ID);
                } else {
                    throw e;
                }
            }

            // Create database with query test table
            DatabaseAdminClient dbAdmin = spanner.getDatabaseAdminClient();
            try {
                dbAdmin.createDatabase(INSTANCE_ID, DATABASE_ID, List.of(
                        "CREATE TABLE " + TABLE + " ("
                                + "  partitionKey STRING(MAX) NOT NULL,"
                                + "  sortKey STRING(MAX) NOT NULL,"
                                + "  title STRING(MAX),"
                                + "  status STRING(MAX),"
                                + "  priority INT64,"
                                + "  category STRING(MAX)"
                                + ") PRIMARY KEY (partitionKey, sortKey)"))
                        .get();
                System.out.println("[Spanner] Created database: " + DATABASE_ID + " with table: " + TABLE);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                    System.out.println("[Spanner] Database already exists: " + DATABASE_ID);
                } else {
                    throw e;
                }
            }
        } finally {
            spanner.close();
        }

        // Create Hyperscale DB client
        HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", PROJECT_ID)
                .connection("instanceId", INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", EMULATOR_HOST)
                .build();
        client = HyperscaleDbClientFactory.create(config);

        // Insert test dataset
        insertDoc("qtest-1", "Buy groceries", "active", 1, "shopping");
        insertDoc("qtest-2", "Write report", "completed", 2, "work");
        insertDoc("qtest-3", "Book flight", "active", 3, "travel");
        insertDoc("qtest-4", "Read book", "active", 4, "personal");
        insertDoc("qtest-5", "Ship package", "completed", 5, "shopping");
        insertDoc("qtest-6", "Buy stamps", "active", 6, "shopping");

        System.out.println("[Spanner] Inserted 6 test documents into " + DATABASE_ID + "/" + TABLE);
    }

    @AfterAll
    void tearDown() throws Exception {
        // Cleanup test data
        for (int i = 1; i <= 6; i++) {
            try {
                client.delete(address, com.hyperscaledb.api.Key.of("qtest-" + i, "qtest-" + i));
            } catch (Exception ignored) {
            }
        }
        System.out.println("[Spanner] Cleaned up test documents");
        if (client != null)
            client.close();
    }

    private void insertDoc(String id, String title, String status, int priority, String category) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("title", title);
        doc.put("status", status);
        doc.put("priority", priority);
        doc.put("category", category);
        client.upsert(address, com.hyperscaledb.api.Key.of(id, id), doc);
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
        System.out.println("[Spanner] equality filter returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": " + item.get("title").asText()
                    + " [status=" + item.get("status").asText() + "]");
        }

        assertFalse(items.isEmpty(), "Should find active items");
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
        System.out.println("[Spanner] AND filter returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": " + item.get("title").asText());
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
        System.out.println("[Spanner] comparison filter (priority > 3) returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": priority=" + item.get("priority").asInt());
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
        System.out.println("[Spanner] starts_with('Buy') returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": " + item.get("title").asText());
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
        System.out.println("[Spanner] contains('book') returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": " + item.get("title").asText());
        }

        // Spanner STRPOS is case-sensitive: "book" matches "Read book" but not "Book
        // flight"
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
        System.out.println("[Spanner] NOT active returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": status=" + item.get("status").asText());
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
        System.out.println("[Spanner] OR (travel|personal) returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": category=" + item.get("category").asText());
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
        System.out.println("[Spanner] complex compound returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": "
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
    @DisplayName("native expression passthrough: GoogleSQL with STARTS_WITH")
    void nativeExpressionPassthrough() {
        QueryRequest query = QueryRequest.builder()
                .nativeExpression("SELECT * FROM " + TABLE + " WHERE STARTS_WITH(title, 'Ship')")
                .pageSize(50)
                .build();

        QueryPage page = client.query(address, query);
        List<JsonNode> items = page.items();
        System.out.println("[Spanner] native GoogleSQL returned " + items.size() + " items");
        for (JsonNode item : items) {
            System.out.println("  -> " + item.get("sortKey").asText() + ": " + item.get("title").asText());
        }

        assertFalse(items.isEmpty(), "Should find items starting with 'Ship'");
        for (JsonNode item : items) {
            assertTrue(item.get("title").asText().startsWith("Ship"));
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
        System.out.println("[Spanner] Full scan returned " + items.size() + " items total");

        long testItems = items.stream()
                .filter(item -> item.has("sortKey") && item.get("sortKey").asText().startsWith("qtest-"))
                .count();
        System.out.println("[Spanner] Found " + testItems + " test items (qtest-*) in emulator");
        for (JsonNode item : items) {
            if (item.has("sortKey") && item.get("sortKey").asText().startsWith("qtest-")) {
                System.out.println("  -> " + item.get("sortKey").asText()
                        + " | title=" + item.get("title").asText()
                        + " | status=" + item.get("status").asText()
                        + " | priority=" + item.get("priority").asInt()
                        + " | category=" + item.get("category").asText());
            }
        }

        assertTrue(testItems >= 6, "Should find all 6 test items in emulator, found: " + testItems);
    }
}
