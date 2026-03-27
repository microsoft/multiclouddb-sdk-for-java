// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryRequestTest {

    @Test
    @DisplayName("Builder defaults: all fields null / empty")
    void builderDefaults() {
        QueryRequest q = QueryRequest.builder().build();
        assertNull(q.expression());
        assertNull(q.nativeExpression());
        assertNull(q.partitionKey());
        assertTrue(q.parameters().isEmpty());
        assertNull(q.maxPageSize());
        assertNull(q.continuationToken());
    }

    @Test
    @DisplayName("Builder full: all fields set via bulk parameters()")
    void builderFull() {
        QueryRequest q = QueryRequest.builder()
                .expression("SELECT * FROM c WHERE c.status = @status")
                .parameters(Map.of("@status", "active"))
                .maxPageSize(25)
                .continuationToken("tok123")
                .partitionKey("tenant-1")
                .build();

        assertEquals("SELECT * FROM c WHERE c.status = @status", q.expression());
        assertEquals("active", q.parameters().get("@status"));
        assertEquals(25, q.maxPageSize());
        assertEquals("tok123", q.continuationToken());
        assertEquals("tenant-1", q.partitionKey());
    }

    @Test
    @DisplayName("parameter(name,value) accumulates single entries")
    void singleEntryParameter() {
        QueryRequest q = QueryRequest.builder()
                .expression("SELECT * FROM c WHERE c.a = @a AND c.b = @b")
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
        QueryRequest q = QueryRequest.builder().parameters(source).build();

        source.put("@a", "mutated");   // mutate source after build
        source.put("@b", "injected");  // add new key after build

        assertEquals("original", q.parameters().get("@a"),
                "QueryRequest must not reflect post-build mutations to the source map");
        assertFalse(q.parameters().containsKey("@b"),
                "Keys added after build must not appear in QueryRequest");
    }

    @Test
    @DisplayName("expression and nativeExpression are mutually exclusive")
    void mutuallyExclusiveExpressions() {
        assertThrows(IllegalArgumentException.class, () ->
                QueryRequest.builder()
                        .expression("SELECT * FROM c")
                        .nativeExpression("SELECT * FROM c")
                        .build());
    }

    @Test
    @DisplayName("toString contains key fields")
    void toStringContainsFields() {
        QueryRequest q = QueryRequest.builder()
                .expression("SELECT * FROM c")
                .maxPageSize(10)
                .partitionKey("pk-1")
                .build();
        String s = q.toString();
        assertTrue(s.contains("SELECT * FROM c"));
        assertTrue(s.contains("10"));
        assertTrue(s.contains("pk-1"));
    }
}
