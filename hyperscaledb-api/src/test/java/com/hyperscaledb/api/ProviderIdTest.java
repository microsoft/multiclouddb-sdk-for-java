package com.hyperscaledb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderIdTest {

    @Test
    void fromIdCaseInsensitive() {
        assertEquals(ProviderId.COSMOS, ProviderId.fromId("cosmos"));
        assertEquals(ProviderId.COSMOS, ProviderId.fromId("COSMOS"));
        assertEquals(ProviderId.COSMOS, ProviderId.fromId("Cosmos"));
    }

    @Test
    void fromIdDynamo() {
        assertEquals(ProviderId.DYNAMO, ProviderId.fromId("dynamo"));
    }

    @Test
    void fromIdSpanner() {
        assertEquals(ProviderId.SPANNER, ProviderId.fromId("spanner"));
    }

    @Test
    void fromIdUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProviderId.fromId("unknown"));
    }

    @Test
    void idAndDisplayName() {
        assertEquals("cosmos", ProviderId.COSMOS.id());
        assertEquals("Azure Cosmos DB", ProviderId.COSMOS.displayName());
        assertEquals("dynamo", ProviderId.DYNAMO.id());
        assertEquals("AWS DynamoDB", ProviderId.DYNAMO.displayName());
    }
}
