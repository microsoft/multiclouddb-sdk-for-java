// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us1b;

import com.hyperscaledb.api.HyperscaleDbClient;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.HyperscaleDbClientFactory;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
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
 * Integration tests for portable query expressions against the Spanner Emulator.
 * <p>
 * Prerequisites: Spanner Emulator running on localhost:9010 (gRPC).
 */
@DisplayName("Spanner — Portable Query Integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("spanner")
@Tag("emulator")
class SpannerQueryIntegrationTest {

    private static final String EMULATOR_HOST = System.getProperty("spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID   = "test-project";
    private static final String INSTANCE_ID  = "test-instance";
    private static final String DATABASE_ID  = "querytestdb";
    private static final String TABLE        = "querytests";

    private HyperscaleDbClient client;
    private final ResourceAddress address = new ResourceAddress(DATABASE_ID, TABLE);

    @BeforeAll
    void setUp() throws ExecutionException, InterruptedException {
        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST).setProjectId(PROJECT_ID).build();
        Spanner spanner = options.getService();
        try {
            InstanceAdminClient instanceAdmin = spanner.getInstanceAdminClient();
            try {
                instanceAdmin.createInstance(
                        InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                                .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                                .setDisplayName("Test Instance").setNodeCount(1).build()).get();
                System.out.println("[Spanner] Created instance: " + INSTANCE_ID);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SpannerException se && se.getErrorCode() == ErrorCode.ALREADY_EXISTS)
                    System.out.println("[Spanner] Instance already exists: " + INSTANCE_ID);
                else throw e;
            }
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
                                + ") PRIMARY KEY (partitionKey, sortKey)")).get();
                System.out.println("[Spanner] Created database: " + DATABASE_ID);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SpannerException se && se.getErrorCode() == ErrorCode.ALREADY_EXISTS)
                    System.out.println("[Spanner] Database already exists: " + DATABASE_ID);
                else throw e;
            }
        } finally { spanner.close(); }

        client = HyperscaleDbClientFactory.create(HyperscaleDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", PROJECT_ID)
                .connection("instanceId", INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", EMULATOR_HOST)
                .build());

        insertDoc("qtest-1", "Buy groceries", "active",    1, "shopping");
        insertDoc("qtest-2", "Write report",  "completed", 2, "work");
        insertDoc("qtest-3", "Book flight",   "active",    3, "travel");
        insertDoc("qtest-4", "Read book",     "active",    4, "personal");
        insertDoc("qtest-5", "Ship package",  "completed", 5, "shopping");
        insertDoc("qtest-6", "Buy stamps",    "active",    6, "shopping");
        System.out.println("[Spanner] Inserted 6 test documents into " + DATABASE_ID + "/" + TABLE);
    }

    @AfterAll
    void tearDown() throws Exception {
        for (int i = 1; i <= 6; i++) {
            try { client.delete(address, com.hyperscaledb.api.HyperscaleDbKey.of("qtest-" + i, "qtest-" + i)); }
            catch (Exception ignored) {}
        }
        System.out.println("[Spanner] Cleaned up test documents");
        if (client != null) client.close();
    }

    private void insertDoc(String id, String title, String status, int priority, String category) {
        client.upsert(address, com.hyperscaledb.api.HyperscaleDbKey.of(id, id),
                Map.of("title", title, "status", status, "priority", priority, "category", category));
    }

    private static String str(Map<String, Object> item, String field) {
        Object v = item.get(field);
        return v != null ? v.toString() : "";
    }
    private static int num(Map<String, Object> item, String field) {
        Object v = item.get(field);
        if (v instanceof Number n) return n.intValue();
        return v != null ? Integer.parseInt(v.toString()) : 0;
    }

    // ---- Portable Expression Integration Tests ----

    @Test @Order(1)
    @DisplayName("equality filter: status = @status returns only active items")
    void equalityFilter() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("status = @status").parameters(Map.of("status", "active")).maxPageSize(50).build());
        assertNotNull(page);
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] equality filter returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": " + str(i,"title") + " [status=" + str(i,"status") + "]"));
        assertFalse(items.isEmpty(), "Should find active items");
        for (Map<String, Object> item : items)
            assertEquals("active", str(item, "status"), "All returned items should have status=active");
    }

    @Test @Order(2)
    @DisplayName("AND filter: status = @status AND category = @cat")
    void andFilter() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("status = @status AND category = @cat")
                .parameters(Map.of("status", "active", "cat", "shopping")).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] AND filter returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": " + str(i,"title")));
        assertFalse(items.isEmpty(), "Should find active shopping items");
        for (Map<String, Object> item : items) {
            assertEquals("active", str(item, "status"));
            assertEquals("shopping", str(item, "category"));
        }
    }

    @Test @Order(3)
    @DisplayName("comparison filter: priority > @minPriority")
    void comparisonFilter() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("priority > @minPriority").parameters(Map.of("minPriority", 3)).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] comparison filter (priority > 3) returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": priority=" + num(i,"priority")));
        assertFalse(items.isEmpty(), "Should find items with priority > 3");
        for (Map<String, Object> item : items)
            assertTrue(num(item, "priority") > 3, "All items should have priority > 3");
    }

    @Test @Order(4)
    @DisplayName("starts_with function: starts_with(title, @prefix)")
    void startsWithFunction() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("starts_with(title, @prefix)").parameters(Map.of("prefix", "Buy")).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] starts_with('Buy') returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": " + str(i,"title")));
        assertFalse(items.isEmpty(), "Should find items starting with 'Buy'");
        for (Map<String, Object> item : items)
            assertTrue(str(item, "title").startsWith("Buy"), "All items should have title starting with 'Buy'");
    }

    @Test @Order(5)
    @DisplayName("contains function: contains(title, @substr)")
    void containsFunction() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("contains(title, @substr)").parameters(Map.of("substr", "book")).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] contains('book') returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": " + str(i,"title")));
        for (Map<String, Object> item : items)
            assertTrue(str(item, "title").contains("book"), "Returned items should contain 'book' in title");
    }

    @Test @Order(6)
    @DisplayName("NOT expression: NOT status = @status")
    void notExpression() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("NOT status = @status").parameters(Map.of("status", "active")).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] NOT active returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": status=" + str(i,"status")));
        assertFalse(items.isEmpty(), "Should find non-active items");
        for (Map<String, Object> item : items)
            assertNotEquals("active", str(item, "status"), "All returned items should NOT be active");
    }

    @Test @Order(7)
    @DisplayName("OR expression: category = @cat1 OR category = @cat2")
    void orExpression() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("category = @cat1 OR category = @cat2")
                .parameters(Map.of("cat1", "travel", "cat2", "personal")).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] OR (travel|personal) returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": category=" + str(i,"category")));
        assertFalse(items.isEmpty(), "Should find travel or personal items");
        for (Map<String, Object> item : items) {
            String cat = str(item, "category");
            assertTrue("travel".equals(cat) || "personal".equals(cat),
                    "Each item should be travel or personal, got: " + cat);
        }
    }

    @Test @Order(8)
    @DisplayName("complex compound: status = @s AND (category = @c1 OR category = @c2)")
    void complexCompound() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("status = @s AND (category = @c1 OR category = @c2)")
                .parameters(Map.of("s", "active", "c1", "shopping", "c2", "travel")).maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] complex compound returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": " + str(i,"title") + " [" + str(i,"status") + ", " + str(i,"category") + "]"));
        assertFalse(items.isEmpty(), "Should find active items in shopping or travel");
        for (Map<String, Object> item : items) {
            assertEquals("active", str(item, "status"));
            String cat = str(item, "category");
            assertTrue("shopping".equals(cat) || "travel".equals(cat),
                    "Category should be shopping or travel, got: " + cat);
        }
    }

    @Test @Order(9)
    @DisplayName("native expression passthrough: GoogleSQL with STARTS_WITH")
    void nativeExpressionPassthrough() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .nativeExpression("SELECT * FROM " + TABLE + " WHERE STARTS_WITH(title, 'Ship')")
                .maxPageSize(50).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] native GoogleSQL returned " + items.size() + " items");
        items.forEach(i -> System.out.println("  -> " + str(i,"sortKey") + ": " + str(i,"title")));
        assertFalse(items.isEmpty(), "Should find items starting with 'Ship'");
        for (Map<String, Object> item : items)
            assertTrue(str(item, "title").startsWith("Ship"));
    }

    @Test @Order(10)
    @DisplayName("all data visible: full scan returns all 6 test items")
    void fullScanShowsAllData() {
        QueryPage page = client.query(address, QueryRequest.builder()
                .expression("SELECT * FROM c").maxPageSize(100).build());
        List<Map<String, Object>> items = page.items();
        System.out.println("[Spanner] Full scan returned " + items.size() + " items total");
        long testItems = items.stream().filter(i -> str(i, "sortKey").startsWith("qtest-")).count();
        System.out.println("[Spanner] Found " + testItems + " test items (qtest-*) in emulator");
        items.stream()
                .filter(i -> str(i, "sortKey").startsWith("qtest-"))
                .forEach(i -> System.out.println("  -> " + str(i,"sortKey")
                        + " | title=" + str(i,"title")
                        + " | status=" + str(i,"status")
                        + " | priority=" + num(i,"priority")
                        + " | category=" + str(i,"category")));
        assertTrue(testItems >= 6, "Should find all 6 test items in emulator, found: " + testItems);
    }
}
