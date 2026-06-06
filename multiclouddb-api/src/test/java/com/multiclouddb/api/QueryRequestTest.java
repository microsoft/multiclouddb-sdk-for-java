// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryRequestTest {

    @Test
    @DisplayName("Builder defaults: required partitionKey, otherwise minimal")
    void builderDefaults() {
        QueryRequest q = QueryRequest.builder()
                .partitionKey("pk-1")
                .build();
        assertNull(q.expression());
        assertEquals("pk-1", q.partitionKey());
        assertTrue(q.parameters().isEmpty());
        assertNull(q.maxPageSize());
        assertNull(q.maxResults());
        assertNull(q.continuationToken());
        assertTrue(q.orderBy().isEmpty());
    }

    @Test
    @DisplayName("partitionKey is required — null throws")
    void partitionKeyRequiredNull() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequest.builder().build(),
                "QueryRequest must reject construction without a partitionKey");
    }

    @Test
    @DisplayName("partitionKey is required — blank throws")
    void partitionKeyRequiredBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryRequest.builder().partitionKey("   ").build());
    }

    @Test
    @DisplayName("Builder full: all fields set via bulk parameters()")
    void builderFull() {
        QueryRequest q = QueryRequest.builder()
                .expression("status = @status")
                .parameters(Map.of("@status", "active"))
                .maxPageSize(25)
                .maxResults(100)
                .continuationToken("tok123")
                .partitionKey("tenant-1")
                .orderBy("sortKey", SortDirection.ASC)
                .build();

        assertEquals("status = @status", q.expression());
        assertEquals("active", q.parameters().get("@status"));
        assertEquals(25, q.maxPageSize());
        assertEquals(100, q.maxResults());
        assertEquals("tok123", q.continuationToken());
        assertEquals("tenant-1", q.partitionKey());
        assertEquals(1, q.orderBy().size());
        assertEquals("sortKey", q.orderBy().get(0).field());
        assertEquals(SortDirection.ASC, q.orderBy().get(0).direction());
    }

    @Test
    @DisplayName("parameter(name,value) accumulates single entries")
    void singleEntryParameter() {
        QueryRequest q = QueryRequest.builder()
                .partitionKey("p")
                .expression("a = @a AND b = @b")
                .parameter("@a", "foo")
                .parameter("@b", 42)
                .build();

        assertEquals("foo", q.parameters().get("@a"));
        assertEquals(42,    q.parameters().get("@b"));
        assertEquals(2,     q.parameters().size());
    }

    @Test
    @DisplayName("parameter(name,value) and parameters(Map) can be mixed")
    void mixedParameterBuilding() {
        QueryRequest q = QueryRequest.builder()
                .partitionKey("p")
                .parameters(Map.of("@x", "first"))
                .parameter("@y", "second")
                .build();

        assertEquals("first",  q.parameters().get("@x"));
        assertEquals("second", q.parameters().get("@y"));
    }

    @Test
    @DisplayName("parameters() is unmodifiable — mutations throw")
    void parametersMapIsImmutable() {
        QueryRequest q = QueryRequest.builder()
                .partitionKey("p")
                .parameter("@a", "1")
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> q.parameters().put("@b", "2"));
    }

    @Test
    @DisplayName("Mutating the source map after build() does not affect QueryRequest")
    void bulkParametersDefensiveCopy() {
        Map<String, Object> source = new HashMap<>();
        source.put("@a", "original");
        QueryRequest q = QueryRequest.builder()
                .partitionKey("p")
                .parameters(source).build();

        source.put("@a", "mutated");
        source.put("@b", "injected");

        assertEquals("original", q.parameters().get("@a"));
        assertFalse(q.parameters().containsKey("@b"));
    }

    @Test
    @DisplayName("orderBy is restricted to the 'sortKey' field — other fields throw")
    void orderByRestrictedToSortKey() {
        assertThrows(IllegalArgumentException.class, () ->
                QueryRequest.builder()
                        .partitionKey("p")
                        .orderBy("name", SortDirection.ASC)
                        .build(),
                "orderBy must reject non-sortKey fields under strict LCD");
    }

    @Test
    @DisplayName("maxResults must be >= 1")
    void maxResultsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () ->
                QueryRequest.builder()
                        .partitionKey("p")
                        .maxResults(0)
                        .build());
    }

    @Test
    @DisplayName("toString contains key fields")
    void toStringContainsFields() {
        QueryRequest q = QueryRequest.builder()
                .partitionKey("pk-1")
                .expression("a = @a")
                .maxPageSize(10)
                .build();
        String s = q.toString();
        assertTrue(s.contains("a = @a"));
        assertTrue(s.contains("10"));
        assertTrue(s.contains("pk-1"));
    }
}
