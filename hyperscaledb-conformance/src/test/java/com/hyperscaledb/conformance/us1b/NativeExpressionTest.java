// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us1b;

import com.hyperscaledb.api.QueryRequest;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for native expression passthrough support (T067-T068).
 * <p>
 * Verifies that {@code nativeExpression} field on {@link QueryRequest} is
 * mutually exclusive with {@code expression}, and that native expressions
 * can be constructed correctly for each provider.
 */
@DisplayName("Native Expression")
class NativeExpressionTest {

    // ---- T067: Native expression passthrough ----

    @Test
    @DisplayName("QueryRequest with nativeExpression only builds successfully")
    void nativeExpressionOnly() {
        QueryRequest request = QueryRequest.builder()
                .nativeExpression("SELECT * FROM c WHERE c.status = @status")
                .parameters(Map.of("status", "active"))
                .build();

        assertEquals("SELECT * FROM c WHERE c.status = @status", request.nativeExpression());
        assertNull(request.expression());
    }

    @Test
    @DisplayName("QueryRequest with portable expression only builds successfully")
    void portableExpressionOnly() {
        QueryRequest request = QueryRequest.builder()
                .expression("status = @status")
                .parameters(Map.of("status", "active"))
                .build();

        assertEquals("status = @status", request.expression());
        assertNull(request.nativeExpression());
    }

    @Test
    @DisplayName("QueryRequest with neither expression builds successfully")
    void noExpression() {
        QueryRequest request = QueryRequest.builder().build();
        assertNull(request.expression());
        assertNull(request.nativeExpression());
    }

    // ---- T068: Mutual exclusivity ----

    @Test
    @DisplayName("setting both expression and nativeExpression throws IllegalArgumentException")
    void mutuallyExclusiveThrows() {
        assertThrows(IllegalArgumentException.class, () -> QueryRequest.builder()
                .expression("status = @status")
                .nativeExpression("SELECT * FROM c WHERE c.status = @status")
                .build());
    }

    // ---- Native expression format per provider ----

    @Test
    @DisplayName("Cosmos-style native expression can be set")
    void cosmosNativeExpression() {
        QueryRequest request = QueryRequest.builder()
                .nativeExpression("SELECT * FROM c WHERE c.category = @cat AND ARRAY_LENGTH(c.tags) > @min")
                .parameters(Map.of("cat", "books", "min", 3))
                .build();

        assertNotNull(request.nativeExpression());
        assertEquals("books", request.parameters().get("cat"));
        assertEquals(3, request.parameters().get("min"));
    }

    @Test
    @DisplayName("DynamoDB PartiQL native expression can be set")
    void dynamoNativeExpression() {
        QueryRequest request = QueryRequest.builder()
                .nativeExpression("SELECT * FROM \"items\" WHERE status = ? AND begins_with(name, ?)")
                .parameters(Map.of("p1", "active", "p2", "abc"))
                .build();

        assertNotNull(request.nativeExpression());
    }

    @Test
    @DisplayName("Spanner GoogleSQL native expression can be set")
    void spannerNativeExpression() {
        QueryRequest request = QueryRequest.builder()
                .nativeExpression("SELECT * FROM items WHERE status = @status AND STARTS_WITH(name, @prefix)")
                .parameters(Map.of("status", "active", "prefix", "abc"))
                .build();

        assertNotNull(request.nativeExpression());
    }

    // ---- Parameters work with native expressions ----

    @Test
    @DisplayName("parameters are accessible on native expression request")
    void parametersAccessible() {
        QueryRequest request = QueryRequest.builder()
                .nativeExpression("custom query")
                .parameters(Map.of("key1", "value1", "key2", 42))
                .build();

        assertEquals(Map.of("key1", "value1", "key2", 42), request.parameters());
    }
}
