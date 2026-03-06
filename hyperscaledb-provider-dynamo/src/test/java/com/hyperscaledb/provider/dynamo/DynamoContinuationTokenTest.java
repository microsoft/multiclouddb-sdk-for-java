package com.hyperscaledb.provider.dynamo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamoContinuationTokenTest {

    @Test
    void encodeDecodeStringKey() {
        Map<String, AttributeValue> original = new LinkedHashMap<>();
        original.put("id", AttributeValue.fromS("item-42"));

        String token = DynamoContinuationToken.encode(original);
        assertNotNull(token);
        assertFalse(token.contains("item-42"), "Token should be opaque");

        Map<String, AttributeValue> decoded = DynamoContinuationToken.decode(token);
        assertEquals("item-42", decoded.get("id").s());
    }

    @Test
    void encodeDecodeNumericKey() {
        Map<String, AttributeValue> original = new LinkedHashMap<>();
        original.put("partitionKey", AttributeValue.fromS("pk1"));
        original.put("id", AttributeValue.fromN("100"));

        String token = DynamoContinuationToken.encode(original);
        Map<String, AttributeValue> decoded = DynamoContinuationToken.decode(token);

        assertEquals("pk1", decoded.get("partitionKey").s());
        assertEquals("100", decoded.get("id").n());
    }

    @Test
    void encodeNullReturnsNull() {
        assertNull(DynamoContinuationToken.encode(null));
        assertNull(DynamoContinuationToken.encode(Map.of()));
    }

    @Test
    void decodeNullReturnsNull() {
        assertNull(DynamoContinuationToken.decode(null));
        assertNull(DynamoContinuationToken.decode(""));
        assertNull(DynamoContinuationToken.decode("  "));
    }
}
