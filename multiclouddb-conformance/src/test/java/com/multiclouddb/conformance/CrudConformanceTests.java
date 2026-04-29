// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider-agnostic conformance tests exercising the portable CRUD + query
 * contract.
 * <p>
 * Subclass this and implement {@link #createClient()} plus
 * {@link #getAddress()} to run the full conformance suite against any provider.
 * <p>
 * These tests are the executable specification of the cross-provider portability
 * contract — every behaviour they assert MUST hold identically across all
 * supported providers (Cosmos DB, DynamoDB, Spanner). When a provider-specific
 * limitation prevents identical behaviour, the conforming response is to either
 * (a) advertise the limitation via {@link CapabilitySet} and reject the
 * unsupported operation with {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY},
 * or (b) surface a {@link MulticloudDbException} with a portable error category;
 * silently producing different results is never acceptable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class CrudConformanceTests {

    protected abstract MulticloudDbClient createClient();
    protected abstract ResourceAddress getAddress();

    private MulticloudDbClient client;

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

    /** Best-effort cleanup that swallows NOT_FOUND so cleanup never blocks teardown. */
    private void safeDelete(MulticloudDbKey key) {
        try {
            client.delete(getAddress(), key);
        } catch (MulticloudDbException ignored) {
            // best-effort cleanup
        }
    }

    // ── CRUD tests ────────────────────────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("upsert + read roundtrip")
    void upsertAndRead() {
        MulticloudDbKey key = MulticloudDbKey.of("conf-test-1", "conf-test-1");
        client.upsert(getAddress(), key,
                Map.of("title", "Conformance Test Item", "value", 42, "active", true));

        DocumentResult result = client.read(getAddress(), key);
        assertNotNull(result, "Document should be returned after upsert");
        assertEquals("Conformance Test Item", result.document().get("title").asText());
        assertEquals(42, result.document().get("value").asInt());
        assertTrue(result.document().get("active").asBoolean());
    }

    @Test @Order(2)
    @DisplayName("upsert overwrites existing document (full replacement, no partial merge)")
    void upsertOverwrites() {
        MulticloudDbKey key = MulticloudDbKey.of("conf-test-upsert", "conf-test-upsert");
        client.upsert(getAddress(), key,
                Map.of("version", 1, "originalOnly", "should-disappear", "shared", "v1"));
        client.upsert(getAddress(), key, Map.of("version", 2, "extra", "field", "shared", "v2"));

        DocumentResult result = client.read(getAddress(), key);
        assertNotNull(result);
        JsonNode doc = result.document();
        assertEquals(2, doc.get("version").asInt(), "version should be replaced");
        assertEquals("v2", doc.get("shared").asText(), "shared field should be replaced");
        assertTrue(doc.has("extra"), "new field should be present");
        // upsert is a full document replacement — fields from the previous version
        // that are not in the new payload must NOT survive (no partial merge).
        assertFalse(doc.has("originalOnly"),
                "upsert must fully replace the document; stale fields must not survive");
        safeDelete(key);
    }

    @Test @Order(3)
    @DisplayName("read returns null for nonexistent key")
    void readNonExistent() {
        assertNull(client.read(getAddress(), MulticloudDbKey.of("does-not-exist-xyz", "does-not-exist-xyz")),
                "Should return null for nonexistent document");
    }

    @Test @Order(4)
    @DisplayName("delete removes document")
    void deleteDocument() {
        MulticloudDbKey key = MulticloudDbKey.of("conf-test-delete", "conf-test-delete");
        client.upsert(getAddress(), key, Map.of("title", "To be deleted"));
        assertNotNull(client.read(getAddress(), key));
        client.delete(getAddress(), key);
        assertNull(client.read(getAddress(), key));
    }

    @Test @Order(5)
    @DisplayName("delete of nonexistent key throws MulticloudDbException with NOT_FOUND")
    void deleteOfMissingKeyThrowsNotFound() {
        MulticloudDbKey key = MulticloudDbKey.of("never-existed-xyz", "never-existed-xyz");
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.delete(getAddress(), key),
                "Deleting a nonexistent key must throw — providers must not silently swallow missing-key");
        assertNotNull(ex.error(), "Exception must carry a structured error");
        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, ex.error().category(),
                "Delete-missing must normalize to NOT_FOUND across providers");
    }

    @Test @Order(6)
    @DisplayName("query returns items")
    void queryAll() {
        for (int i = 1; i <= 3; i++) {
            client.upsert(getAddress(), MulticloudDbKey.of("conf-query-" + i, "conf-query-" + i),
                    Map.of("title", "Query Item " + i, "batch", "conformance"));
        }
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder().expression("SELECT * FROM c").maxPageSize(50).build());
        assertNotNull(page);
        assertFalse(page.items().isEmpty(), "Query should return at least our inserted items");
        for (int i = 1; i <= 3; i++) safeDelete(MulticloudDbKey.of("conf-query-" + i, "conf-query-" + i));
    }

    @Test @Order(7)
    @DisplayName("query with page size limits results")
    void queryPaging() {
        for (int i = 1; i <= 5; i++) {
            client.upsert(getAddress(), MulticloudDbKey.of("conf-page-" + i, "conf-page-" + i),
                    Map.of("title", "Page Item " + i));
        }
        QueryPage page1 = client.query(getAddress(),
                QueryRequest.builder().expression("SELECT * FROM c").maxPageSize(2).build());
        assertNotNull(page1);
        assertTrue(page1.items().size() <= 2, "Page should respect pageSize limit");
        for (int i = 1; i <= 5; i++) safeDelete(MulticloudDbKey.of("conf-page-" + i, "conf-page-" + i));
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
        safeDelete(MulticloudDbKey.of("conf-test-1", "conf-test-1"));
    }

    // ── Partition-key-scoped query tests ──────────────────────────────────────

    @Test @Order(11)
    @DisplayName("partitionKey scopes query to matching items only")
    void queryByPartitionKey() {
        for (int i = 1; i <= 3; i++)
            client.upsert(getAddress(), MulticloudDbKey.of("alpha", "pk-alpha-" + i),
                    Map.of("title", "Alpha Item " + i, "group", "alpha"));
        for (int i = 1; i <= 2; i++)
            client.upsert(getAddress(), MulticloudDbKey.of("beta", "pk-beta-" + i),
                    Map.of("title", "Beta Item " + i, "group", "beta"));

        QueryPage alphaPage = client.query(getAddress(),
                QueryRequest.builder().partitionKey("alpha").maxPageSize(100).build());
        assertNotNull(alphaPage);
        assertEquals(3, alphaPage.items().size(), "Partition 'alpha' should contain exactly 3 items");
        for (Map<String, Object> item : alphaPage.items())
            assertEquals("alpha", str(item, "group"), "All items should belong to the alpha group");

        QueryPage betaPage = client.query(getAddress(),
                QueryRequest.builder().partitionKey("beta").maxPageSize(100).build());
        assertNotNull(betaPage);
        assertEquals(2, betaPage.items().size(), "Partition 'beta' should contain exactly 2 items");

        for (int i = 1; i <= 3; i++) safeDelete(MulticloudDbKey.of("alpha", "pk-alpha-" + i));
        for (int i = 1; i <= 2; i++) safeDelete(MulticloudDbKey.of("beta", "pk-beta-" + i));
    }

    @Test @Order(12)
    @DisplayName("partitionKey null falls back to cross-partition query (returns items from multiple partitions)")
    void queryWithoutPartitionKey() {
        client.upsert(getAddress(), MulticloudDbKey.of("cross-a", "pk-cross-a"), Map.of("title", "Cross-A", "marker", "cross-conf"));
        client.upsert(getAddress(), MulticloudDbKey.of("cross-b", "pk-cross-b"), Map.of("title", "Cross-B", "marker", "cross-conf"));

        QueryPage page = client.query(getAddress(),
                QueryRequest.builder()
                        .expression("marker = @m")
                        .parameter("m", "cross-conf")
                        .maxPageSize(200).build());
        assertNotNull(page);
        // Cross-partition query must return items from BOTH partitions, not just one.
        Set<String> seenPartitions = new HashSet<>();
        for (Map<String, Object> item : page.items()) {
            String t = str(item, "title");
            if ("Cross-A".equals(t)) seenPartitions.add("cross-a");
            else if ("Cross-B".equals(t)) seenPartitions.add("cross-b");
        }
        assertTrue(seenPartitions.size() >= 2,
                "Cross-partition query should surface items from at least 2 distinct partitions; saw: "
                        + seenPartitions);

        safeDelete(MulticloudDbKey.of("cross-a", "pk-cross-a"));
        safeDelete(MulticloudDbKey.of("cross-b", "pk-cross-b"));
    }

    @Test @Order(13)
    @DisplayName("partitionKey for nonexistent partition returns empty result")
    void queryNonexistentPartition() {
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder().partitionKey("nonexistent-partition-xyz").maxPageSize(100).build());
        assertNotNull(page);
        assertTrue(page.items().isEmpty(),
                "Query on nonexistent partition should return no items");
    }

    // ── Type fidelity / CRUD edge cases ───────────────────────────────────────

    @Test @Order(14)
    @DisplayName("upsert + read preserves all primitive types and structure")
    void typeFidelityRoundtrip() {
        MulticloudDbKey key = MulticloudDbKey.of("conf-types", "conf-types");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("strField", "hello-world");
        doc.put("intField", 12345);
        doc.put("longField", 9_876_543_210L);
        doc.put("doubleField", 3.14159);
        doc.put("boolTrue", true);
        doc.put("boolFalse", false);
        doc.put("nullField", null);
        doc.put("nestedObj", Map.of("inner", "value", "n", 7));
        doc.put("arrayField", List.of("a", "b", "c"));
        doc.put("emptyArray", List.of());

        try {
            client.upsert(getAddress(), key, doc);
            DocumentResult r = client.read(getAddress(), key);
            assertNotNull(r, "Document should be readable after upsert");
            JsonNode d = r.document();

            assertEquals("hello-world", d.get("strField").asText(), "string fidelity");
            assertEquals(12345, d.get("intField").asInt(), "int fidelity");
            assertEquals(9_876_543_210L, d.get("longField").asLong(), "long fidelity");
            assertEquals(3.14159, d.get("doubleField").asDouble(), 1e-9, "double fidelity");
            assertTrue(d.get("boolTrue").asBoolean(), "boolean true fidelity");
            assertFalse(d.get("boolFalse").asBoolean(), "boolean false fidelity");
            assertTrue(d.has("nullField"), "null field should round-trip as a present field");
            assertTrue(d.get("nullField").isNull(), "null field should round-trip as JSON null");
            assertNotNull(d.get("nestedObj"), "nested object should be present");
            assertEquals("value", d.get("nestedObj").get("inner").asText(), "nested object fidelity");
            assertEquals(7, d.get("nestedObj").get("n").asInt(), "nested numeric fidelity");
            assertNotNull(d.get("arrayField"), "array should be present");
            assertTrue(d.get("arrayField").isArray(), "array field should round-trip as a JSON array");
            assertEquals(3, d.get("arrayField").size(), "array size fidelity");
            assertEquals("a", d.get("arrayField").get(0).asText(), "array element fidelity");
            assertTrue(d.get("emptyArray").isArray(), "empty array fidelity");
            assertEquals(0, d.get("emptyArray").size(), "empty array length fidelity");
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(15)
    @DisplayName("create of duplicate key throws MulticloudDbException with CONFLICT")
    void createDuplicateKeyThrowsConflict() {
        MulticloudDbKey key = MulticloudDbKey.of("conf-dup", "conf-dup");
        try {
            client.create(getAddress(), key, Map.of("title", "first"));
            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.create(getAddress(), key, Map.of("title", "second")),
                    "create of duplicate key must throw");
            assertEquals(MulticloudDbErrorCategory.CONFLICT, ex.error().category(),
                    "Duplicate-create must normalize to CONFLICT across providers");
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(16)
    @DisplayName("query with no matches returns empty page (not null, not exception)")
    void queryWithNoMatchesReturnsEmptyPage() {
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder()
                        .expression("title = @t")
                        .parameter("t", "no-document-has-this-title-xyz-12345")
                        .maxPageSize(50)
                        .build());
        assertNotNull(page, "Query must return a non-null page even when no items match");
        assertNotNull(page.items(), "Page items() must never be null");
        assertTrue(page.items().isEmpty(), "Items list must be empty when no documents match");
    }

    @Test @Order(17)
    @DisplayName("page-size invariance: total items is the same for any page size")
    void pageSizeInvariantTotalCount() {
        // Seed a known set in a dedicated partition so we have a deterministic universe.
        String pk = "page-invariance";
        int seedCount = 7;
        String marker = "pi-" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 1; i <= seedCount; i++) {
            client.upsert(getAddress(), MulticloudDbKey.of(pk, "pi-" + i),
                    Map.of("marker", marker, "n", i));
        }
        try {
            int totalAtPage1 = exhaustiveCount(pk, marker, 1);
            int totalAtPage3 = exhaustiveCount(pk, marker, 3);
            int totalAtPage50 = exhaustiveCount(pk, marker, 50);

            assertEquals(seedCount, totalAtPage1,
                    "Page size 1 must yield all " + seedCount + " items via continuation");
            assertEquals(totalAtPage1, totalAtPage3,
                    "Page sizes 1 and 3 must yield identical total counts");
            assertEquals(totalAtPage1, totalAtPage50,
                    "Page sizes 1 and 50 must yield identical total counts");
        } finally {
            for (int i = 1; i <= seedCount; i++) safeDelete(MulticloudDbKey.of(pk, "pi-" + i));
        }
    }

    private int exhaustiveCount(String partition, String marker, int pageSize) {
        int total = 0;
        Set<String> seenIds = new HashSet<>();
        String token = null;
        int safety = 0;
        do {
            QueryRequest.Builder b = QueryRequest.builder()
                    .partitionKey(partition)
                    .expression("marker = @m")
                    .parameter("m", marker)
                    .maxPageSize(pageSize);
            if (token != null) b.continuationToken(token);
            QueryPage p = client.query(getAddress(), b.build());
            assertNotNull(p);
            for (Map<String, Object> item : p.items()) {
                Object n = item.get("n");
                String stableId = (n == null ? "?" : n.toString());
                assertTrue(seenIds.add(stableId),
                        "Pagination at pageSize=" + pageSize + " produced a duplicate item (n=" + stableId + ")");
                total++;
            }
            token = p.continuationToken();
            safety++;
            if (safety > 1000) fail("Pagination did not terminate within 1000 pages");
        } while (token != null);
        return total;
    }

    // ── Lifecycle / configuration tests ───────────────────────────────────────

    @Test @Order(18)
    @DisplayName("close() is idempotent — calling twice does not throw")
    void closeIsIdempotent() throws Exception {
        // close() once now and let @AfterEach close again to assert idempotency.
        // To detect throw on second close we explicitly close twice here AND null-out
        // the field so the @AfterEach doesn't double-close (some providers' tearDown
        // handles their own resource freeing). We then re-create the client so other
        // ordered tests after this one continue to operate on a fresh client.
        MulticloudDbClient first = client;
        assertDoesNotThrow(first::close, "first close() must not throw");
        assertDoesNotThrow(first::close, "second close() must be idempotent");
        // ensure the @AfterEach close is also safe (third close):
        // recreate so subsequent tests have a working client (test ordering allows this)
        client = createClient();
    }

    @Test @Order(19)
    @DisplayName("ensureDatabase() is idempotent on existing database")
    void ensureDatabaseIsIdempotent() {
        String db = getAddress().database();
        assertDoesNotThrow(() -> client.ensureDatabase(db),
                "ensureDatabase on existing database must not throw");
        assertDoesNotThrow(() -> client.ensureDatabase(db),
                "ensureDatabase must be idempotent on subsequent calls");
    }

    @Test @Order(20)
    @DisplayName("ensureContainer() is idempotent on existing container")
    void ensureContainerIsIdempotent() {
        ResourceAddress address = getAddress();
        assertDoesNotThrow(() -> client.ensureContainer(address),
                "ensureContainer on existing container must not throw");
        assertDoesNotThrow(() -> client.ensureContainer(address),
                "ensureContainer must be idempotent on subsequent calls");
    }

    // ── Portable expression runtime parity ────────────────────────────────────
    //
    // The us1b ExpressionTranslationTest already covers translation. These tests
    // verify that the *runtime* result sets match expectations across all
    // providers for operators and portable functions that historically were only
    // tested at translation time.

    @Test @Order(30)
    @DisplayName("comparison operators (=, !=, <, <=, >, >=) yield expected runtime result sets")
    void runtimeComparisonOperators() {
        String pk = "cmp-conf";
        String marker = "cmp-" + UUID.randomUUID().toString().substring(0, 6);
        // Seed 5 items with ages 10, 20, 30, 40, 50.
        int[] ages = { 10, 20, 30, 40, 50 };
        for (int a : ages) {
            client.upsert(getAddress(), MulticloudDbKey.of(pk, "cmp-" + a),
                    Map.of("marker", marker, "age", a));
        }
        try {
            // Each pair: expression, expected count
            assertCount("age = @v AND marker = @m", Map.of("v", 30, "m", marker), pk, 1, "=");
            assertCount("age != @v AND marker = @m", Map.of("v", 30, "m", marker), pk, 4, "!=");
            assertCount("age < @v AND marker = @m", Map.of("v", 30, "m", marker), pk, 2, "<");
            assertCount("age <= @v AND marker = @m", Map.of("v", 30, "m", marker), pk, 3, "<=");
            assertCount("age > @v AND marker = @m", Map.of("v", 30, "m", marker), pk, 2, ">");
            assertCount("age >= @v AND marker = @m", Map.of("v", 30, "m", marker), pk, 3, ">=");
        } finally {
            for (int a : ages) safeDelete(MulticloudDbKey.of(pk, "cmp-" + a));
        }
    }

    @Test @Order(31)
    @DisplayName("IN and BETWEEN yield expected runtime result sets")
    void runtimeInAndBetween() {
        String pk = "inbtw-conf";
        String marker = "ib-" + UUID.randomUUID().toString().substring(0, 6);
        int[] ages = { 10, 20, 30, 40, 50 };
        for (int a : ages) {
            client.upsert(getAddress(), MulticloudDbKey.of(pk, "ib-" + a),
                    Map.of("marker", marker, "age", a));
        }
        try {
            assertCount("age IN (@a, @b, @c) AND marker = @m",
                    Map.of("a", 10, "b", 30, "c", 50, "m", marker), pk, 3, "IN");
            assertCount("age BETWEEN @lo AND @hi AND marker = @m",
                    Map.of("lo", 20, "hi", 40, "m", marker), pk, 3, "BETWEEN");
        } finally {
            for (int a : ages) safeDelete(MulticloudDbKey.of(pk, "ib-" + a));
        }
    }

    private void assertCount(String expr, Map<String, Object> params,
                             String partitionKey, int expected, String label) {
        QueryRequest.Builder b = QueryRequest.builder()
                .expression(expr)
                .partitionKey(partitionKey)
                .maxPageSize(100);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            b.parameter(e.getKey(), e.getValue());
        }
        QueryPage page = client.query(getAddress(), b.build());
        assertNotNull(page, "page must not be null for " + label);
        assertEquals(expected, page.items().size(),
                "Operator '" + label + "' should match exactly " + expected + " items; expr=" + expr);
    }
}
