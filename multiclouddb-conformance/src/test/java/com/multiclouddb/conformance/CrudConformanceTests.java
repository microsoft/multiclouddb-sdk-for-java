// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.internal.DocumentSizeValidator;
import com.multiclouddb.api.internal.PartialUpdateStructureValidator;
import com.multiclouddb.api.internal.PartialUpdateValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> PROVIDER_OWNED_FIELDS = Set.of(
            "id", "partitionKey", "sortKey", "ttl", "ttlExpiry", "data");

    private record CanonicalPojo(String name, List<Integer> scores) { }

    private record BinaryPojo(byte[] payload) { }

    private record IterablePojo(Iterable<Integer> values) { }

    private static final class PojoNode {
        public PojoNode child;
    }

    private static final class FailingPojo {
        public String getValue() {
            throw new IllegalStateException("getter failed");
        }
    }

    private record UnsafeValueCase(
            String documentReason, String updateReason,
            Object value, boolean expectsCause) { }

    @JsonSerialize(using = CanonicalUpdateFieldsSerializer.class)
    private static final class CanonicalUpdateFields
            extends LinkedHashMap<String, Object> {
    }

    private static final class CanonicalUpdateFieldsSerializer
            extends StdSerializer<CanonicalUpdateFields> {

        private CanonicalUpdateFieldsSerializer() {
            super(CanonicalUpdateFields.class);
        }

        @Override
        public void serialize(CanonicalUpdateFields value, JsonGenerator generator,
                SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            for (int i = 0; i < 11; i++) {
                generator.writeStringField("emitted" + i, "value");
            }
            generator.writeEndObject();
        }
    }

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

    private static void assertPortableResult(JsonNode document) {
        document.fieldNames().forEachRemaining(field -> {
            assertFalse(isProviderOwnedField(field),
                    field + " must not leak into read results");
            assertFalse(field.startsWith("_"),
                    field + " must not leak into read results");
        });
    }

    private static void assertPortableResult(Map<String, Object> document) {
        document.keySet().forEach(field -> {
            assertFalse(isProviderOwnedField(field),
                    field + " must not leak into query results");
            assertFalse(field.startsWith("_"),
                    field + " must not leak into query results");
        });
    }

    private static boolean isProviderOwnedField(String field) {
        return PROVIDER_OWNED_FIELDS.stream()
                .anyMatch(providerField -> providerField.equalsIgnoreCase(field));
    }
    /**
     * Cleanup helper that delegates to {@link com.multiclouddb.api.MulticloudDbClient#delete}.
     * Delete is idempotent across providers, so calling this on an already-deleted
     * (or never-created) key is a silent no-op. Real provider errors (auth,
     * network, invalid request) still propagate and may fail teardown — by design,
     * since masking those would hide environment-level problems.
     */
    private void safeDelete(MulticloudDbKey key) {
        client.delete(getAddress(), key);
    }

    private static Map<String, Object> fieldsOfSerializedSize(
            String fieldName, int targetBytes) throws Exception {
        int overhead = JSON.writeValueAsBytes(Map.of(fieldName, "")).length;
        Map<String, Object> fields = Map.of(
                fieldName, "A".repeat(targetBytes - overhead));
        assertEquals(targetBytes, JSON.writeValueAsBytes(fields).length,
                "Fixture must serialize to the requested byte size");
        return fields;
    }

    private static Map<String, Object> fieldsWithNestingDepth(int depth) {
        Object value = "leaf-value";
        for (int i = 0; i < depth; i++) {
            value = Map.of("level", value);
        }
        return Map.of("profile", value);
    }

    private static Map<String, Object> fieldsAtStructuralFootprint() {
        String fieldName = "arrayField";
        int fixedBytes = fieldName.length() + 3 + 1;
        int elementCount =
                (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - fixedBytes) / 4;
        int trailingStringBytes = PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES
                - fixedBytes - (4 * elementCount);
        assertEquals(PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES,
                fixedBytes + (4 * elementCount) + trailingStringBytes,
                "Fixture footprint must equal the portable structural limit");
        List<Object> values = new ArrayList<>(
                Collections.nCopies(elementCount, Map.of()));
        values.add("A".repeat(trailingStringBytes));
        return Map.of(fieldName, values);
    }

    private static Map<String, Object> fieldsOverStructuralFootprint() {
        int elementCount = (PartialUpdateStructureValidator.MAX_FOOTPRINT_BYTES - 4) / 4 + 1;
        return Map.of("x", Collections.nCopies(elementCount, Map.of()));
    }

    private static List<UnsafeValueCase> unsafeWriteValues() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);

        Object deep = "leaf";
        for (int i = 0; i < 5_000; i++) {
            deep = Map.of("level", deep);
        }

        PojoNode pojoCycle = new PojoNode();
        pojoCycle.child = pojoCycle;

        PojoNode deepPojo = new PojoNode();
        for (int i = 0; i < PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1; i++) {
            PojoNode parent = new PojoNode();
            parent.child = deepPojo;
            deepPojo = parent;
        }

        Iterable<Integer> unbounded = () -> new Iterator<>() {
            private int value;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Integer next() {
                return value++;
            }
        };

        return List.of(
                new UnsafeValueCase(
                        "document_value_cycle", "partial_update_value_cycle",
                        cycle, false),
                new UnsafeValueCase(
                        PartialUpdateStructureValidator.DOCUMENT_DEPTH_LIMIT_REASON,
                        PartialUpdateStructureValidator.DEPTH_LIMIT_REASON, deep, false),
                new UnsafeValueCase(
                        "document_value_cycle", "partial_update_value_cycle",
                        pojoCycle, false),
                new UnsafeValueCase(
                        PartialUpdateStructureValidator.DOCUMENT_DEPTH_LIMIT_REASON,
                        PartialUpdateStructureValidator.DEPTH_LIMIT_REASON, deepPojo, false),
                new UnsafeValueCase(
                        "non_portable_iterable", "non_portable_iterable",
                        unbounded, false),
                new UnsafeValueCase(
                        "non_portable_iterable", "non_portable_iterable",
                        new IterablePojo(unbounded), false),
                new UnsafeValueCase(
                        "portable_value_normalization_failed",
                        "portable_value_normalization_failed",
                        new FailingPojo(), true),
                new UnsafeValueCase(
                        PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                        PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                        new BinaryPojo(new byte[] {1, 2}), false));
    }

    private static Map<String, Object> tooManyUpdateFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < 11; i++) {
            fields.put("field" + i, i);
        }
        assertEquals(11, fields.size());
        return fields;
    }

    private static Map<String, Object> maximumUpdateFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < PartialUpdateValidator.MAX_FIELDS; i++) {
            fields.put("field" + i, i);
        }
        assertEquals(PartialUpdateValidator.MAX_FIELDS, fields.size());
        return fields;
    }

    private void assumePartialUpdateSupported() {
        assumeTrue(client.capabilities().isSupported(Capability.PARTIAL_UPDATE),
                "Provider does not participate in the portable partial-update release");
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
        assertPortableResult(result.document());

        Map<String, Object> replacement = JSON.convertValue(result.document(), MAP_TYPE);
        assertDoesNotThrow(() -> client.upsert(getAddress(), key, replacement),
                "A read result must be reusable as a complete replacement document");
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
    @DisplayName("delete of nonexistent key is a silent no-op (idempotent)")
    void deleteOfMissingKeyIsSilent() {
        // Use a per-invocation unique key so a previous failed run, a parallel runner,
        // or seeded state cannot accidentally make the key exist when this test runs.
        String unique = "never-existed-" + UUID.randomUUID();
        MulticloudDbKey key = MulticloudDbKey.of(unique, unique);
        // Delete is the LCD across Cosmos/Dynamo/Spanner: a missing key is a silent
        // no-op, never an exception. Callers that need NOT_FOUND on a missing key
        // must use update() instead.
        assertDoesNotThrow(() -> client.delete(getAddress(), key),
                "Delete of a nonexistent key must be silent — providers must not throw on missing");
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
        page.items().forEach(CrudConformanceTests::assertPortableResult);
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
        // Per-run unique marker so a long-lived emulator (or leftover items
        // from a previously failed run) cannot interfere with the assertion:
        // we only ever see items seeded by *this* invocation.
        String marker = "cross-conf-" + java.util.UUID.randomUUID();
        client.upsert(getAddress(), MulticloudDbKey.of("cross-a", "pk-cross-a"), Map.of("title", "Cross-A", "marker", marker));
        client.upsert(getAddress(), MulticloudDbKey.of("cross-b", "pk-cross-b"), Map.of("title", "Cross-B", "marker", marker));

        // Iterate continuation tokens so we evaluate the *full* result set
        // for this marker, not just the first page. With a fixed marker we
        // could otherwise be fooled by leftover rows pushing the seeded items
        // past page-1.
        Set<String> seenPartitions = new HashSet<>();
        String continuation = null;
        do {
            QueryRequest.Builder qb = QueryRequest.builder()
                    .expression("marker = @m")
                    .parameter("m", marker)
                    .maxPageSize(200);
            if (continuation != null) qb.continuationToken(continuation);
            QueryPage page = client.query(getAddress(), qb.build());
            assertNotNull(page);
            for (Map<String, Object> item : page.items()) {
                String t = str(item, "title");
                if ("Cross-A".equals(t)) seenPartitions.add("cross-a");
                else if ("Cross-B".equals(t)) seenPartitions.add("cross-b");
            }
            continuation = page.continuationToken();
        } while (continuation != null);

        // Cross-partition query must return items from BOTH partitions, not just one.
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
        // Just under Long.MAX_VALUE and well above 2^53 (~9.007e15) — a silent
        // int/long → double coercion would lose precision here, so a value
        // mismatch (not just a type mismatch) would surface the regression.
        doc.put("bigLongField", 9_223_372_036_854_775_800L);
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
            // JsonNode.asInt() / asLong() coerce silently — a provider that stores
            // integers as JSON doubles would still pass a value comparison up to
            // 2^53. Guard the *type* explicitly with isIntegralNumber() /
            // canConvertToLong() so a silent int → double promotion fails here.
            assertTrue(d.get("intField").isIntegralNumber(),
                    "int field must round-trip as an integral JSON number, not a double");
            assertEquals(12345, d.get("intField").asInt(), "int value fidelity");
            assertTrue(d.get("longField").isIntegralNumber(),
                    "long field must round-trip as an integral JSON number, not a double");
            assertTrue(d.get("longField").canConvertToLong(),
                    "long field must round-trip without losing precision");
            assertEquals(9_876_543_210L, d.get("longField").asLong(), "long value fidelity");
            assertTrue(d.get("bigLongField").isIntegralNumber(),
                    "near-Long.MAX_VALUE must round-trip as an integral JSON number");
            assertTrue(d.get("bigLongField").canConvertToLong(),
                    "near-Long.MAX_VALUE must round-trip without losing precision");
            assertEquals(9_223_372_036_854_775_800L, d.get("bigLongField").asLong(),
                    "near-Long.MAX_VALUE value fidelity (would fail on silent long → double)");
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
        // Per-run unique key. A fixed key would make this test flaky: a
        // previous failed run (or a long-lived emulator) could leave the row
        // behind, causing the *first* create() to spuriously throw CONFLICT
        // before the second call (the actual subject of the assertion) runs.
        String unique = "conf-dup-" + java.util.UUID.randomUUID();
        MulticloudDbKey key = MulticloudDbKey.of(unique, unique);
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
        // Per-run unique sentinel — guarantees no document in long-lived emulator
        // state or shared test environments accidentally satisfies the predicate.
        String unmatchableTitle = "no-document-has-this-title-" + java.util.UUID.randomUUID();
        QueryPage page = client.query(getAddress(),
                QueryRequest.builder()
                        .expression("title = @t")
                        .parameter("t", unmatchableTitle)
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
            // Compare the actual *set* of returned items, not just counts.
            // A buggy provider that returns 7 items at pageSize=50 and a
            // different 7 items at pageSize=1 (e.g. one stale + one missing)
            // would yield identical counts but a set-equality failure here
            // — that's the regression this assertion guards against.
            Set<String> idsAtPage1 = exhaustiveCount(pk, marker, 1);
            Set<String> idsAtPage3 = exhaustiveCount(pk, marker, 3);
            Set<String> idsAtPage50 = exhaustiveCount(pk, marker, 50);

            assertEquals(seedCount, idsAtPage1.size(),
                    "Page size 1 must yield all " + seedCount + " items via continuation");
            assertEquals(idsAtPage1, idsAtPage3,
                    "Page sizes 1 and 3 must yield the identical *set* of items "
                            + "(same identities, no duplicates, no omissions)");
            assertEquals(idsAtPage1, idsAtPage50,
                    "Page sizes 1 and 50 must yield the identical *set* of items "
                            + "(same identities, no duplicates, no omissions)");
        } finally {
            for (int i = 1; i <= seedCount; i++) safeDelete(MulticloudDbKey.of(pk, "pi-" + i));
        }
    }

    private Set<String> exhaustiveCount(String partition, String marker, int pageSize) {
        Set<String> seenIds = new LinkedHashSet<>();
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
                // The seed loop assigns the field "n" the values 1..seedCount, one
                // value per inserted document, so "n" is guaranteed unique within
                // this test's universe and works as a portable stable id across
                // all providers (Cosmos does not inject a "sortKey" field on
                // returned items, only "id" + "partitionKey", so a key-based
                // dedup would not be portable here).
                String stableId = str(item, "n");
                assertTrue(seenIds.add(stableId),
                        "Pagination at pageSize=" + pageSize
                                + " produced a duplicate item (n=" + stableId + ")");
            }
            token = p.continuationToken();
            safety++;
            if (safety > 1000) fail("Pagination did not terminate within 1000 pages");
        } while (token != null);
        return seenIds;
    }

    // ── Lifecycle / configuration tests ───────────────────────────────────────

    @Test @Order(18)
    @DisplayName("close() is idempotent — calling twice does not throw")
    void closeIsIdempotent() throws Exception {
        // Use a dedicated, throwaway client so the shared @BeforeEach/@AfterEach
        // lifecycle is not perturbed. The shared `client` field is left untouched,
        // so @AfterEach will close exactly one (still-open) client as designed.
        MulticloudDbClient throwaway = createClient();
        assertDoesNotThrow(throwaway::close, "first close() must not throw");
        assertDoesNotThrow(throwaway::close,
                "second close() on an already-closed client must be a no-op");
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

    @Test @Order(21)
    @DisplayName("post-close operations throw MulticloudDbException(CLIENT_CLOSED, retryable=false)")
    void postCloseOperationsThrowClientClosed() throws Exception {
        // Use a dedicated throwaway client so the shared @BeforeEach/@AfterEach
        // lifecycle is not perturbed. The shared `client` field is left untouched,
        // so @AfterEach will close exactly one (still-open) client as designed.
        //
        // Provider-portability contract: after close(), every public CRUD/query/
        // provisioning entry point must surface a typed CLIENT_CLOSED envelope
        // rather than leaking a raw IllegalStateException from the underlying
        // SDK (azure-cosmos, aws-sdk, google-cloud-spanner). Telemetry,
        // retry-policy, and circuit-breaker layers all branch on the typed
        // category, so a raw exception would silently bypass those layers and
        // be classified as a generic transport error.
        //
        // CLIENT_CLOSED must also be retryable()==false: closing is a terminal
        // lifecycle state, and a retrying caller would loop indefinitely.
        MulticloudDbClient throwaway = createClient();
        throwaway.close();

        ResourceAddress address = getAddress();
        String marker = "closed-" + UUID.randomUUID().toString().substring(0, 8);
        MulticloudDbKey key = MulticloudDbKey.of(marker, marker);
        QueryRequest q = QueryRequest.builder().build();

        // Mutating ops fail before any network call, so no cleanup is needed —
        // the closed client cannot have written anything.
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.create(address, key, Map.of("k", "v"), null)),
                "create");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.read(address, key, null)),
                "read");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.update(address, key, Map.of("k", "v"), null)),
                "update");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.upsert(address, key, Map.of("k", "v"), null)),
                "upsert");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.delete(address, key, null)),
                "delete");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.query(address, q, null)),
                "query");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.ensureDatabase(address.database())),
                "ensureDatabase");
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.ensureContainer(address)),
                "ensureContainer");
        // Schema contents are irrelevant here: checkOpen() at
        // DefaultMulticloudDbClient.provisionSchema runs *before* any
        // delegation to the SPI default, so a closed client throws
        // CLIENT_CLOSED before the SPI's empty-schema no-op
        // (MulticloudDbProviderClient.provisionSchema) is ever consulted.
        // The non-empty schema is kept only to match the shape callers
        // would normally pass.
        Map<String, List<String>> schema = Map.of(
                address.database(), List.of(address.collection()));
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> throwaway.provisionSchema(schema)),
                "provisionSchema");
    }

    private static void assertClientClosed(MulticloudDbException ex, String operation) {
        assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, ex.error().category(),
                operation + ": post-close operation must surface CLIENT_CLOSED, not "
                        + ex.error().category());
        assertFalse(ex.error().retryable(),
                operation + ": CLIENT_CLOSED must be non-retryable (terminal lifecycle state)");
        // Telemetry / diagnostics / retry layers branch on the operation name to
        // attribute post-close failures; assert it matches the caller's op so a
        // future regression renaming the OperationNames constants or wiring the
        // wrong constant into a checkOpen() call fails loudly here.
        assertEquals(operation, ex.error().operation(),
                operation + ": post-close error must attribute operation to the caller's op, "
                        + "not '" + ex.error().operation() + "'");
    }

    // ── Partial update ────────────────────────────────────────────────────────

    @Test @Order(22)
    @DisplayName("unsupported partial update fails at the shared capability gate")
    void unsupportedPartialUpdateIsCapabilityGated() {
        assumeFalse(client.capabilities().isSupported(Capability.PARTIAL_UPDATE));
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-unsupported");

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.update(getAddress(), key, Map.of("title", "not-delegated")));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        assertFalse(ex.error().retryable());
        assertEquals(client.providerId(), ex.error().provider());
        assertEquals(Capability.PARTIAL_UPDATE,
                ex.error().providerDetails().get("capability"));
        assertNull(client.read(getAddress(), key));
    }

    @Test @Order(23)
    @DisplayName("update replaces selected fields and preserves omitted fields")
    void partialUpdateReplacesSelectedFieldsAndPreservesOmittedFields() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-shapes");
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("title", "before");
        seed.put("status", "preserve-me");
        seed.put("nullField", "not-null-yet");
        seed.put("nestedObj", Map.of("old", "value"));
        seed.put("arrayField", List.of("old-a", "old-b"));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "after");
        fields.put("extra", "added-by-update");
        fields.put("nullField", null);
        fields.put("nestedObj", Map.of("new", "value"));
        fields.put("arrayField", List.of("replacement"));

        try {
            client.upsert(getAddress(), key, seed);
            client.update(getAddress(), key, fields);

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("after", doc.path("title").asText());
            assertEquals("preserve-me", doc.path("status").asText(),
                    "Omitted fields must be preserved");
            assertEquals("added-by-update", doc.path("extra").asText(),
                    "An absent field with an existing provider mapping must be added");
            assertTrue(doc.has("nullField") && doc.get("nullField").isNull(),
                    "STRING-backed null must remain a present JSON null");
            assertEquals("value", doc.path("nestedObj").path("new").asText());
            assertFalse(doc.path("nestedObj").has("old"),
                    "A map value replaces the complete top-level field");
            assertEquals(1, doc.path("arrayField").size());
            assertEquals("replacement", doc.path("arrayField").get(0).asText(),
                    "A list value replaces the complete top-level field");
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(24)
    @DisplayName("update of a missing item returns NOT_FOUND and does not create")
    void partialUpdateMissingItemReturnsNotFoundWithoutCreate() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-missing");

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.update(getAddress(), key, Map.of("title", "must-not-exist")));

        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, ex.error().category());
        assertNull(client.read(getAddress(), key),
                "A failed update must not create the missing item");
    }

    @Test @Order(25)
    @DisplayName("replaying the same partial update is idempotent")
    void partialUpdateReplayIsIdempotent() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-replay");
        Map<String, Object> fields = Map.of("status", "complete", "priority", 7);

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "preserved", "status", "pending", "priority", 1));
            client.update(getAddress(), key, fields);
            client.update(getAddress(), key, fields);

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("complete", doc.path("status").asText());
            assertEquals(7, doc.path("priority").asInt());
            assertEquals("preserved", doc.path("title").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(26)
    @DisplayName("concurrent disjoint partial updates preserve both writes")
    void disjointConcurrentPartialUpdatesPreserveBothWrites() throws Exception {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            client.upsert(getAddress(), key, Map.of("title", "before", "status", "before"));
            List<Future<Void>> updates = executor.invokeAll(List.of(
                    () -> {
                        client.update(getAddress(), key, Map.of("title", "after-title"));
                        return null;
                    },
                    () -> {
                        client.update(getAddress(), key, Map.of("status", "after-status"));
                        return null;
                    }));
            for (Future<Void> update : updates) {
                update.get();
            }

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("after-title", doc.path("title").asText());
            assertEquals("after-status", doc.path("status").asText());
        } finally {
            executor.shutdownNow();
            safeDelete(key);
        }
    }

    @Test @Order(27)
    @DisplayName("partial update rejects more than ten fields before provider delegation")
    void partialUpdateRejectsMoreThanTenFieldsWithoutMutation() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-field-count");

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "before", "status", "preserved"));
            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key, tooManyUpdateFields()));

            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
            assertFalse(ex.error().retryable());
            assertNull(ex.error().provider(),
                    "Field-count rejection must come from shared preflight");
            assertEquals("partial_update_field_count_limit",
                    ex.error().providerDetails().get("reason"));
            assertEquals("10", ex.error().providerDetails().get("maximumFields"));
            assertEquals("11", ex.error().providerDetails().get("observedFields"));
            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("before", doc.path("title").asText());
            assertEquals("preserved", doc.path("status").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(27)
    @DisplayName("partial update accepts exactly ten fields and preserves omitted data")
    void partialUpdateAcceptsExactlyTenFields() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-field-max");

        try {
            client.upsert(getAddress(), key, Map.of("preserved", "seed"));
            client.update(getAddress(), key, maximumUpdateFields());

            JsonNode document = client.read(getAddress(), key).document();
            assertEquals("seed", document.path("preserved").asText());
            for (int i = 0; i < PartialUpdateValidator.MAX_FIELDS; i++) {
                assertEquals(i, document.path("field" + i).asInt());
            }
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(27)
    @DisplayName("partial update accepts a field name at the UTF-8 byte boundary")
    void partialUpdateAcceptsMaximumFieldNameBytes() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-name-max");
        String fieldName = "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES);

        try {
            client.upsert(getAddress(), key, Map.of("preserved", "seed"));
            client.update(getAddress(), key, Map.of(fieldName, "value"));

            JsonNode document = client.read(getAddress(), key).document();
            assertEquals("seed", document.path("preserved").asText());
            assertEquals("value", document.path(fieldName).asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(28)
    @DisplayName("update TTL is rejected and leaves the existing document unchanged")
    void partialUpdateRejectsTtlWithoutMutation() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-ttl");

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "before", "status", "preserved"));

            OperationOptions options = OperationOptions.builder()
                    .ttlSeconds(7200)
                    .build();
            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key,
                            Map.of("title", "after"), options));

            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
            assertFalse(ex.error().retryable());
            assertNull(ex.error().provider(),
                    "TTL rejection must come from shared preflight");

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("before", doc.path("title").asText());
            assertEquals("preserved", doc.path("status").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(29)
    @DisplayName("invalid and reserved update fields leave the existing document unchanged")
    void partialUpdateInvalidFieldsDoNotMutateExistingDocument() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-invalid");

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "before", "status", "preserved"));

            List<Map<String, Object>> invalidFieldMaps = new ArrayList<>();
            invalidFieldMaps.add(null);
            invalidFieldMaps.add(Map.of());

            Map<String, Object> nullName = new LinkedHashMap<>();
            nullName.put(null, "null-name");
            invalidFieldMaps.add(nullName);

            invalidFieldMaps.add(Map.of("", "empty"));
            invalidFieldMaps.add(Map.of(" ", "blank"));
            invalidFieldMaps.add(Map.of("_hidden", "reserved-prefix"));
            for (String reserved : List.of("id", "ID", "partitionKey", "PARTITIONKEY",
                    "sortKey", "SORTKEY", "ttl", "TTL", "ttlExpiry", "TTLEXPIRY", "data", "DATA")) {
                invalidFieldMaps.add(Map.of(reserved, "reserved"));
            }
            for (Map<String, Object> invalidFields : invalidFieldMaps) {
                MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                        () -> client.update(getAddress(), key, invalidFields));
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        ex.error().category());
                assertFalse(ex.error().retryable());
                assertNull(ex.error().provider(),
                        "Field rejection must come from shared preflight");

                JsonNode doc = client.read(getAddress(), key).document();
                assertEquals("before", doc.path("title").asText());
                assertEquals("preserved", doc.path("status").asText());
            }
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(29)
    @DisplayName("oversized field names and binary values fail shared preflight")
    void nonPortableUpdateInputsDoNotMutate() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-nonportable");
        Map<String, Object> oversizedName = Map.of(
                "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 1), "value");
        Map<String, Object> binary = Map.of("payload", new byte[] {1, 2});

        try {
            client.upsert(getAddress(), key, Map.of("title", "before"));
            MulticloudDbException nameFailure = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key, oversizedName));
            MulticloudDbException binaryFailure = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key, binary));

            assertEquals(PartialUpdateValidator.FIELD_NAME_SIZE_LIMIT_REASON,
                    nameFailure.error().providerDetails().get("reason"));
            assertEquals(PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                    binaryFailure.error().providerDetails().get("reason"));
            for (MulticloudDbException failure : List.of(nameFailure, binaryFailure)) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertFalse(failure.error().retryable());
                assertNull(failure.error().provider());
            }
            assertEquals("before", client.read(getAddress(), key).document()
                    .path("title").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(29)
    @DisplayName("partial update serializes structured Java values without rewriting map fields")
    void partialUpdateSerializesJavaRepresentations() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("update-canonical");
        CanonicalUpdateFields fields = new CanonicalUpdateFields();
        fields.put("arrayField", new int[] {3, 4});
        fields.put("nestedObj", JSON.createObjectNode().put("city", "London"));
        fields.put("strField", new CanonicalPojo("Lin", List.of(5)));

        try {
            client.upsert(getAddress(), key, Map.of("title", "preserved"));
            client.update(getAddress(), key, fields);

            JsonNode updated = client.read(getAddress(), key).document();
            assertEquals("preserved", updated.path("title").asText());
            assertEquals(4, updated.path("arrayField").path(1).asInt());
            assertEquals("London", updated.path("nestedObj").path("city").asText());
            assertEquals("Lin", updated.path("strField").path("name").asText());
            assertFalse(updated.has("emitted0"),
                    "A Map serializer must not rewrite validated top-level fields");
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(29)
    @DisplayName("unsafe write graphs fail shared preflight for every operation")
    void unsafeWriteGraphsFailSharedPreflight() {
        MulticloudDbKey updateKey = ConformanceHarness.uniqueKey("unsafe-update");
        List<MulticloudDbKey> cleanupKeys = new ArrayList<>();
        cleanupKeys.add(updateKey);

        try {
            client.upsert(getAddress(), updateKey, Map.of("title", "before"));
            int index = 0;
            for (UnsafeValueCase unsafe : unsafeWriteValues()) {
                MulticloudDbKey createKey = ConformanceHarness.uniqueKey(
                        "unsafe-create-" + index);
                MulticloudDbKey upsertKey = ConformanceHarness.uniqueKey(
                        "unsafe-upsert-" + index++);
                cleanupKeys.add(createKey);
                cleanupKeys.add(upsertKey);
                Map<String, Object> input = Map.of("nestedObj", unsafe.value());

                MulticloudDbException createFailure = assertThrows(
                        MulticloudDbException.class,
                        () -> client.create(getAddress(), createKey, input));
                MulticloudDbException upsertFailure = assertThrows(
                        MulticloudDbException.class,
                        () -> client.upsert(getAddress(), upsertKey, input));
                MulticloudDbException updateFailure = assertThrows(
                        MulticloudDbException.class,
                        () -> client.update(getAddress(), updateKey, input));

                for (MulticloudDbException failure : List.of(
                        createFailure, upsertFailure, updateFailure)) {
                    assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                            failure.error().category());
                    assertFalse(failure.error().retryable());
                    assertNull(failure.error().provider(),
                            "Unsafe graphs must fail before provider delegation");
                }
                assertEquals(unsafe.documentReason(),
                        createFailure.error().providerDetails().get("reason"));
                assertEquals(unsafe.documentReason(),
                        upsertFailure.error().providerDetails().get("reason"));
                assertEquals(unsafe.updateReason(),
                        updateFailure.error().providerDetails().get("reason"));
                if (unsafe.expectsCause()) {
                    assertNotNull(createFailure.getCause());
                    assertNotNull(upsertFailure.getCause());
                    assertNotNull(updateFailure.getCause());
                }
                assertNull(client.read(getAddress(), createKey));
                assertNull(client.read(getAddress(), upsertKey));
            }

            JsonNode unchanged = client.read(getAddress(), updateKey).document();
            assertEquals("before", unchanged.path("title").asText());
            assertFalse(unchanged.has("nestedObj"));
        } finally {
            cleanupKeys.forEach(this::safeDelete);
        }
    }

    @Test @Order(29)
    @DisplayName("complete writes reject null and provider-reserved fields before I/O")
    void invalidCompleteDocumentsFailSharedPreflight() {
        MulticloudDbKey nullCreateKey = ConformanceHarness.uniqueKey("create-null");
        MulticloudDbKey nullUpsertKey = ConformanceHarness.uniqueKey("upsert-null");
        MulticloudDbKey dataCreateKey = ConformanceHarness.uniqueKey("create-data");
        MulticloudDbKey dataUpsertKey = ConformanceHarness.uniqueKey("upsert-data");
        MulticloudDbKey ttlCreateKey = ConformanceHarness.uniqueKey("create-ttl");
        MulticloudDbKey metadataUpsertKey = ConformanceHarness.uniqueKey("upsert-meta");

        try {
            MulticloudDbException nullCreate = assertThrows(MulticloudDbException.class,
                    () -> client.create(getAddress(), nullCreateKey,
                            (Map<String, Object>) null));
            MulticloudDbException nullUpsert = assertThrows(MulticloudDbException.class,
                    () -> client.upsert(getAddress(), nullUpsertKey,
                            (Map<String, Object>) null));
            MulticloudDbException dataCreate = assertThrows(MulticloudDbException.class,
                    () -> client.create(getAddress(), dataCreateKey,
                            Map.of("data", "reserved")));
            MulticloudDbException dataUpsert = assertThrows(MulticloudDbException.class,
                    () -> client.upsert(getAddress(), dataUpsertKey,
                            Map.of("DaTa", "reserved")));
            MulticloudDbException ttlCreate = assertThrows(MulticloudDbException.class,
                    () -> client.create(getAddress(), ttlCreateKey,
                            Map.of("ttl", 60)));
            MulticloudDbException metadataUpsert = assertThrows(
                    MulticloudDbException.class,
                    () -> client.upsert(getAddress(), metadataUpsertKey,
                            Map.of("_ts", 1)));

            for (MulticloudDbException failure : List.of(nullCreate, nullUpsert)) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertEquals("document_required",
                        failure.error().providerDetails().get("reason"));
                assertFalse(failure.error().retryable());
                assertNull(failure.error().provider());
            }
            for (MulticloudDbException failure : List.of(
                    dataCreate, dataUpsert, ttlCreate, metadataUpsert)) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertEquals("reserved_document_field",
                        failure.error().providerDetails().get("reason"));
                assertFalse(failure.error().retryable());
                assertNull(failure.error().provider());
            }
            assertNull(client.read(getAddress(), nullCreateKey));
            assertNull(client.read(getAddress(), nullUpsertKey));
            assertNull(client.read(getAddress(), dataCreateKey));
            assertNull(client.read(getAddress(), dataUpsertKey));
            assertNull(client.read(getAddress(), ttlCreateKey));
            assertNull(client.read(getAddress(), metadataUpsertKey));
        } finally {
            safeDelete(nullCreateKey);
            safeDelete(nullUpsertKey);
            safeDelete(dataCreateKey);
            safeDelete(dataUpsertKey);
            safeDelete(ttlCreateKey);
            safeDelete(metadataUpsertKey);
        }
    }

    @Test @Order(29)
    @DisplayName("complete writes serialize Jackson, POJO, and Java-array values")
    void completeWritesSerializeJavaRepresentations() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("complete-canonical");
        JsonNode tree = JSON.createObjectNode().put("city", "Seattle");

        try {
            client.create(getAddress(), key, Map.of(
                    "arrayField", new String[] {"a", "b"},
                    "nestedObj", tree,
                    "strField", new CanonicalPojo("Ada", List.of(1, 2))));

            JsonNode created = client.read(getAddress(), key).document();
            assertEquals("b", created.path("arrayField").path(1).asText());
            assertEquals("Seattle", created.path("nestedObj").path("city").asText());
            assertEquals("Ada", created.path("strField").path("name").asText());

            client.upsert(getAddress(), key, Map.of(
                    "arrayField", new int[] {3, 4},
                    "nestedObj", JSON.createObjectNode().put("city", "London"),
                    "strField", new CanonicalPojo("Lin", List.of(5))));

            JsonNode upserted = client.read(getAddress(), key).document();
            assertEquals(4, upserted.path("arrayField").path(1).asInt());
            assertEquals("London", upserted.path("nestedObj").path("city").asText());
            assertEquals("Lin", upserted.path("strField").path("name").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(29)
    @DisplayName("complete writes reject binary and oversized field-name inputs")
    void nonPortableCompleteWriteInputsFailSharedPreflight() {
        MulticloudDbKey binaryCreateKey = ConformanceHarness.uniqueKey("create-binary");
        MulticloudDbKey binaryUpsertKey = ConformanceHarness.uniqueKey("upsert-binary");
        MulticloudDbKey nameCreateKey = ConformanceHarness.uniqueKey("create-name-over");
        MulticloudDbKey nameUpsertKey = ConformanceHarness.uniqueKey("upsert-name-over");
        Map<String, Object> binary = Map.of("payload", new byte[] {1, 2});
        Map<String, Object> oversizedName = Map.of(
                "nestedObj", Map.of(
                        "a".repeat(PartialUpdateValidator.MAX_FIELD_NAME_BYTES + 1),
                        "value"));

        try {
            List<MulticloudDbException> failures = List.of(
                    assertThrows(MulticloudDbException.class,
                            () -> client.create(getAddress(), binaryCreateKey, binary)),
                    assertThrows(MulticloudDbException.class,
                            () -> client.upsert(getAddress(), binaryUpsertKey, binary)),
                    assertThrows(MulticloudDbException.class,
                            () -> client.create(getAddress(), nameCreateKey, oversizedName)),
                    assertThrows(MulticloudDbException.class,
                            () -> client.upsert(getAddress(), nameUpsertKey, oversizedName)));

            for (MulticloudDbException failure : failures) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertFalse(failure.error().retryable());
                assertNull(failure.error().provider());
            }
            assertEquals(PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                    failures.get(0).error().providerDetails().get("reason"));
            assertEquals(PartialUpdateStructureValidator.NON_PORTABLE_BINARY_REASON,
                    failures.get(1).error().providerDetails().get("reason"));
            assertEquals("document_field_name_size_limit",
                    failures.get(2).error().providerDetails().get("reason"));
            assertEquals("document_field_name_size_limit",
                    failures.get(3).error().providerDetails().get("reason"));
            assertNull(client.read(getAddress(), binaryCreateKey));
            assertNull(client.read(getAddress(), binaryUpsertKey));
            assertNull(client.read(getAddress(), nameCreateKey));
            assertNull(client.read(getAddress(), nameUpsertKey));
        } finally {
            safeDelete(binaryCreateKey);
            safeDelete(binaryUpsertKey);
            safeDelete(nameCreateKey);
            safeDelete(nameUpsertKey);
        }
    }

    @Test @Order(29)
    @DisplayName("complete writes reject Spanner-incompatible top-level names before I/O")
    void completeWriteTopLevelNamesArePortable() {
        MulticloudDbKey collisionCreateKey =
                ConformanceHarness.uniqueKey("create-case-collision");
        MulticloudDbKey collisionUpsertKey =
                ConformanceHarness.uniqueKey("upsert-case-collision");
        MulticloudDbKey longCreateKey =
                ConformanceHarness.uniqueKey("create-long-field");
        MulticloudDbKey longUpsertKey =
                ConformanceHarness.uniqueKey("upsert-long-field");
        Map<String, Object> collision = new LinkedHashMap<>();
        collision.put("foo", 1);
        collision.put("Foo", 2);
        Map<String, Object> longName = Map.of(
                "a".repeat(DocumentSizeValidator.MAX_TOP_LEVEL_FIELD_NAME_CHARACTERS + 1),
                "value");

        try {
            List<MulticloudDbException> collisionFailures = List.of(
                    assertThrows(MulticloudDbException.class,
                            () -> client.create(
                                    getAddress(), collisionCreateKey, collision)),
                    assertThrows(MulticloudDbException.class,
                            () -> client.upsert(
                                    getAddress(), collisionUpsertKey, collision)));
            List<MulticloudDbException> lengthFailures = List.of(
                    assertThrows(MulticloudDbException.class,
                            () -> client.create(getAddress(), longCreateKey, longName)),
                    assertThrows(MulticloudDbException.class,
                            () -> client.upsert(getAddress(), longUpsertKey, longName)));

            collisionFailures.forEach(failure -> {
                assertEquals("document_case_insensitive_field_name_collision",
                        failure.error().providerDetails().get("reason"));
                assertNull(failure.error().provider());
            });
            lengthFailures.forEach(failure -> {
                assertEquals("document_top_level_field_name_length_limit",
                        failure.error().providerDetails().get("reason"));
                assertNull(failure.error().provider());
            });
            assertNull(client.read(getAddress(), collisionCreateKey));
            assertNull(client.read(getAddress(), collisionUpsertKey));
            assertNull(client.read(getAddress(), longCreateKey));
            assertNull(client.read(getAddress(), longUpsertKey));
        } finally {
            safeDelete(collisionCreateKey);
            safeDelete(collisionUpsertKey);
            safeDelete(longCreateKey);
            safeDelete(longUpsertKey);
        }
    }

    @Test @Order(29)
    @DisplayName("complete writes enforce the portable map/list depth boundary")
    void completeWriteDepthBoundaryIsPortable() {
        MulticloudDbKey depthKey = ConformanceHarness.uniqueKey("complete-depth-max");
        MulticloudDbKey createOverKey = ConformanceHarness.uniqueKey("create-depth-over");
        MulticloudDbKey upsertOverKey = ConformanceHarness.uniqueKey("upsert-depth-over");

        try {
            Map<String, Object> accepted = Map.of(
                    "nestedObj", fieldsWithNestingDepth(
                            PartialUpdateStructureValidator.MAX_NESTING_DEPTH).get("profile"));
            client.create(getAddress(), depthKey, accepted);
            client.upsert(getAddress(), depthKey, accepted);
            assertNotNull(client.read(getAddress(), depthKey));

            Map<String, Object> rejected = Map.of(
                    "nestedObj", fieldsWithNestingDepth(
                            PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1)
                            .get("profile"));
            MulticloudDbException createFailure = assertThrows(
                    MulticloudDbException.class,
                    () -> client.create(getAddress(), createOverKey, rejected));
            MulticloudDbException upsertFailure = assertThrows(
                    MulticloudDbException.class,
                    () -> client.upsert(getAddress(), upsertOverKey, rejected));
            for (MulticloudDbException failure : List.of(
                    createFailure, upsertFailure)) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertEquals(PartialUpdateStructureValidator.DOCUMENT_DEPTH_LIMIT_REASON,
                        failure.error().providerDetails().get("reason"));
                assertEquals(String.valueOf(
                                PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1),
                        failure.error().providerDetails().get("actualNestingDepth"));
                assertNull(failure.error().provider());
            }
            assertNull(client.read(getAddress(), createOverKey));
            assertNull(client.read(getAddress(), upsertOverKey));
        } finally {
            safeDelete(depthKey);
            safeDelete(createOverKey);
            safeDelete(upsertOverKey);
        }
    }

    @Test @Order(29)
    @DisplayName("complete writes accept the exact portable structural footprint")
    void completeWritesAcceptExactStructuralFootprint() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("complete-footprint-max");
        Map<String, Object> exact = fieldsAtStructuralFootprint();

        try {
            client.create(getAddress(), key, exact);
            assertNotNull(client.read(getAddress(), key));
            client.upsert(getAddress(), key, exact);
            assertNotNull(client.read(getAddress(), key));
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(29)
    @DisplayName("dense create and upsert documents fail the shared structural envelope")
    void denseDocumentsFailBeforeProviderIo() {
        Map<String, Object> dense = fieldsOverStructuralFootprint();
        MulticloudDbKey createKey = ConformanceHarness.uniqueKey("create-structure-over");
        MulticloudDbKey upsertKey = ConformanceHarness.uniqueKey("upsert-structure-over");

        try {
            MulticloudDbException createFailure = assertThrows(MulticloudDbException.class,
                    () -> client.create(getAddress(), createKey, dense));
            MulticloudDbException upsertFailure = assertThrows(MulticloudDbException.class,
                    () -> client.upsert(getAddress(), upsertKey, dense));

            for (MulticloudDbException failure : List.of(createFailure, upsertFailure)) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertEquals(PartialUpdateStructureValidator.DOCUMENT_FOOTPRINT_LIMIT_REASON,
                        failure.error().providerDetails().get("reason"));
                assertNull(failure.error().provider());
            }
            assertNull(client.read(getAddress(), createKey));
            assertNull(client.read(getAddress(), upsertKey));
        } finally {
            safeDelete(createKey);
            safeDelete(upsertKey);
        }
    }

    @Test @Order(30)
    @DisplayName("create and upsert accept a document at the portable 390 KiB limit")
    void documentAtCommonLimitRoundTrips() throws Exception {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("document-size-boundary");
        Map<String, Object> created =
                fieldsOfSerializedSize("title", DocumentSizeValidator.MAX_BYTES);
        Map<String, Object> replaced =
                fieldsOfSerializedSize("status", DocumentSizeValidator.MAX_BYTES);

        try {
            client.create(getAddress(), key, created);
            JsonNode afterCreate = client.read(getAddress(), key).document();
            assertEquals(((String) created.get("title")).length(),
                    afterCreate.path("title").asText().length());

            client.upsert(getAddress(), key, replaced);
            JsonNode afterUpsert = client.read(getAddress(), key).document();
            assertFalse(afterUpsert.has("title"));
            assertEquals(((String) replaced.get("status")).length(),
                    afterUpsert.path("status").asText().length());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(30)
    @DisplayName("oversized create and upsert inputs fail shared preflight on every provider")
    void oversizedDocumentsFailBeforeProviderIo() throws Exception {
        Map<String, Object> oversized =
                fieldsOfSerializedSize("payload", DocumentSizeValidator.MAX_BYTES + 1);
        MulticloudDbKey createKey = ConformanceHarness.uniqueKey("create-size-over");
        MulticloudDbKey upsertKey = ConformanceHarness.uniqueKey("upsert-size-over");

        try {
            MulticloudDbException createFailure = assertThrows(MulticloudDbException.class,
                    () -> client.create(getAddress(), createKey, oversized));
            MulticloudDbException upsertFailure = assertThrows(MulticloudDbException.class,
                    () -> client.upsert(getAddress(), upsertKey, oversized));

            for (MulticloudDbException failure : List.of(createFailure, upsertFailure)) {
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST,
                        failure.error().category());
                assertFalse(failure.error().retryable());
                assertNull(failure.error().provider(),
                        "Size rejection must come from shared preflight");
            }
            assertNull(client.read(getAddress(), createKey));
            assertNull(client.read(getAddress(), upsertKey));
        } finally {
            safeDelete(createKey);
            safeDelete(upsertKey);
        }
    }

    @Test @Order(30)
    @DisplayName("supported partial update accepts the portable 390 KiB boundary")
    void partialUpdateAtCommonLimitRoundTrips() throws Exception {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-size-boundary");
        int emptyPayloadBytes = JSON.writeValueAsBytes(
                Map.of("title", "preserved", "payload", "")).length;
        String payload = "A".repeat(DocumentSizeValidator.MAX_BYTES - emptyPayloadBytes);
        Map<String, Object> expectedResult = Map.of(
                "title", "preserved", "payload", payload);
        assertEquals(DocumentSizeValidator.MAX_BYTES,
                JSON.writeValueAsBytes(expectedResult).length,
                "Result fixture must match the portable document envelope");

        try {
            client.upsert(getAddress(), key, Map.of("title", "preserved"));
            client.update(getAddress(), key, Map.of("payload", payload));

            JsonNode document = client.read(getAddress(), key).document();
            assertEquals("preserved", document.path("title").asText());
            assertEquals(payload.length(), document.path("payload").asText().length());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(30)
    @DisplayName("an update over the portable 390 KiB limit is rejected without mutation")
    void partialUpdateOneByteOverCommonLimitDoesNotMutateExistingDocument() throws Exception {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-size");
        Map<String, Object> fields = fieldsOfSerializedSize("title", DocumentSizeValidator.MAX_BYTES + 1);

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "before", "status", "preserved"));

            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key, fields));

            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
            assertFalse(ex.error().retryable());
            assertNull(ex.error().provider(),
                    "Size rejection must come from shared preflight");

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("before", doc.path("title").asText());
            assertEquals("preserved", doc.path("status").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(31)
    @DisplayName("supported partial update accepts 31 nested replacement containers")
    void partialUpdateAcceptsPortableNestingBoundary() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-depth-boundary");

        try {
            client.upsert(getAddress(), key, Map.of("title", "preserved"));
            client.update(getAddress(), key, fieldsWithNestingDepth(
                    PartialUpdateStructureValidator.MAX_NESTING_DEPTH));

            JsonNode document = client.read(getAddress(), key).document();
            assertEquals("preserved", document.path("title").asText());
            JsonNode nested = document.path("profile");
            for (int i = 0; i < PartialUpdateStructureValidator.MAX_NESTING_DEPTH; i++) {
                nested = nested.path("level");
            }
            assertEquals("leaf-value", nested.asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(31)
    @DisplayName("32 nested replacement containers fail shared preflight without mutation")
    void partialUpdateRejectsOverNestingDepthWithoutMutation() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-depth-over");

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "before", "status", "preserved"));

            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key, fieldsWithNestingDepth(
                            PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1)));

            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
            assertFalse(ex.error().retryable());
            assertNull(ex.error().provider(),
                    "Depth rejection must come from shared preflight");
            assertEquals(PartialUpdateStructureValidator.DEPTH_LIMIT_REASON,
                    ex.error().providerDetails().get("reason"));
            assertEquals(String.valueOf(PartialUpdateStructureValidator.MAX_NESTING_DEPTH + 1),
                    ex.error().providerDetails().get("actualNestingDepth"));
            JsonNode document = client.read(getAddress(), key).document();
            assertEquals("before", document.path("title").asText());
            assertEquals("preserved", document.path("status").asText());
            assertFalse(document.has("profile"));
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(31)
    @DisplayName("excessive map/list footprint fails shared preflight without mutation")
    void partialUpdateRejectsStructuralFootprintWithoutMutation() throws Exception {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-footprint-over");
        Map<String, Object> fields = fieldsOverStructuralFootprint();
        assertTrue(JSON.writeValueAsBytes(fields).length < DocumentSizeValidator.MAX_BYTES,
                "Fixture must pass the independent serialized-JSON limit");

        try {
            client.upsert(getAddress(), key,
                    Map.of("title", "before", "status", "preserved"));

            MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                    () -> client.update(getAddress(), key, fields));

            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
            assertFalse(ex.error().retryable());
            assertNull(ex.error().provider(),
                    "Structural-footprint rejection must come from shared preflight");
            assertEquals(PartialUpdateStructureValidator.FOOTPRINT_LIMIT_REASON,
                    ex.error().providerDetails().get("reason"));
            JsonNode document = client.read(getAddress(), key).document();
            assertEquals("before", document.path("title").asText());
            assertEquals("preserved", document.path("status").asText());
            assertFalse(document.has("x"));
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(31)
    @DisplayName("literal punctuation and surrounding spaces remain exact top-level names")
    void partialUpdatePreservesLiteralFieldNames() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-literal-names");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(".", "dot");
        fields.put("/", "slash");
        fields.put("~", "tilde");
        fields.put(" customer ", "spaced");

        try {
            client.upsert(getAddress(), key, Map.of("title", "preserved"));
            client.update(getAddress(), key, fields);

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("dot", doc.path(".").asText());
            assertEquals("slash", doc.path("/").asText());
            assertEquals("tilde", doc.path("~").asText());
            assertEquals("spaced", doc.path(" customer ").asText());
            assertEquals("preserved", doc.path("title").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(32)
    @DisplayName("case-variant update fields remain distinct")
    void partialUpdateCaseVariantFieldIdentityIsExplicit() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-case");

        try {
            client.upsert(getAddress(), key, Map.of("title", "before"));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("title", "lowercase");
            fields.put("TITLE", "uppercase");
            client.update(getAddress(), key, fields);

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("lowercase", doc.path("title").asText());
            assertEquals("uppercase", doc.path("TITLE").asText());
        } finally {
            safeDelete(key);
        }
    }

    @Test @Order(33)
    @DisplayName("case variant of an unwritten field retains exact identity")
    void partialUpdateUnwrittenSchemaFieldCaseIsExplicit() {
        assumePartialUpdateSupported();
        MulticloudDbKey key = ConformanceHarness.uniqueKey("partial-schema-case");

        try {
            client.upsert(getAddress(), key, Map.of("title", "preserved"));
            client.update(getAddress(), key, Map.of("STATUS", "uppercase"));

            JsonNode doc = client.read(getAddress(), key).document();
            assertEquals("preserved", doc.path("title").asText());
            assertEquals("uppercase", doc.path("STATUS").asText());
            assertFalse(doc.has("status"));
        } finally {
            safeDelete(key);
        }
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
        // Use a unique partition key suffix so cleanup is precise even when
        // earlier runs of this test left rows behind in long-lived emulator state.
        String pk = "inbtw-" + UUID.randomUUID().toString().substring(0, 6);
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
