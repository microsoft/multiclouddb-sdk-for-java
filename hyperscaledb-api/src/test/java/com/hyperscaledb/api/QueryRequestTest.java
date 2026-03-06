package com.hyperscaledb.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryRequestTest {

    @Test
    void builderDefaults() {
        QueryRequest q = QueryRequest.builder().build();
        assertNull(q.expression());
        assertTrue(q.parameters().isEmpty());
        assertNull(q.pageSize());
        assertNull(q.continuationToken());
    }

    @Test
    void builderFull() {
        QueryRequest q = QueryRequest.builder()
                .expression("SELECT * FROM c WHERE c.status = @status")
                .parameters(Map.of("status", "active"))
                .pageSize(25)
                .continuationToken("tok123")
                .build();

        assertEquals("SELECT * FROM c WHERE c.status = @status", q.expression());
        assertEquals("active", q.parameters().get("status"));
        assertEquals(25, q.pageSize());
        assertEquals("tok123", q.continuationToken());
    }

    @Test
    void parametersMapIsImmutable() {
        QueryRequest q = QueryRequest.builder()
                .parameters(Map.of("a", "1"))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> q.parameters().put("b", "2"));
    }
}
