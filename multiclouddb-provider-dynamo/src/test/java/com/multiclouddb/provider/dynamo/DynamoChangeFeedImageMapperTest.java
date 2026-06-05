// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link DynamoChangeFeed#imageToJson} — the change-feed
 * NEW_IMAGE → JSON converter. Verifies that nested attribute types (M, L, B,
 * SS, NS, BS) are recursively mapped instead of falling through to
 * {@code AttributeValue.toString()}, which would silently corrupt CDC payloads
 * for any DynamoDB schema using compound types.
 */
class DynamoChangeFeedImageMapperTest {

    @Test
    @DisplayName("scalar S/N/BOOL/NULL round-trip")
    void scalars() {
        Map<String, AttributeValue> image = new LinkedHashMap<>();
        image.put("name", AttributeValue.builder().s("alice").build());
        image.put("age", AttributeValue.builder().n("30").build());
        image.put("active", AttributeValue.builder().bool(true).build());
        image.put("nickname", AttributeValue.builder().nul(true).build());

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        assertEquals("alice", out.get("name").asText());
        assertEquals(30, out.get("age").asInt());
        assertTrue(out.get("active").asBoolean());
        assertTrue(out.get("nickname").isNull());
    }

    @Test
    @DisplayName("nested Map (M) is recursively mapped, not stringified")
    void mapAttribute() {
        Map<String, AttributeValue> address = new LinkedHashMap<>();
        address.put("city", AttributeValue.builder().s("Seattle").build());
        address.put("zip", AttributeValue.builder().n("98101").build());

        Map<String, AttributeValue> image = new LinkedHashMap<>();
        image.put("address", AttributeValue.builder().m(address).build());

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        JsonNode addr = out.get("address");
        assertTrue(addr.isObject(), "M attribute must produce a JSON object, got: " + addr);
        assertEquals("Seattle", addr.get("city").asText());
        assertEquals(98101, addr.get("zip").asInt());
    }

    @Test
    @DisplayName("nested List (L) is recursively mapped, not stringified")
    void listAttribute() {
        AttributeValue items = AttributeValue.builder()
                .l(AttributeValue.builder().s("a").build(),
                        AttributeValue.builder().n("1").build(),
                        AttributeValue.builder().bool(false).build())
                .build();
        Map<String, AttributeValue> image = Map.of("items", items);

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        JsonNode arr = out.get("items");
        assertTrue(arr.isArray(), "L attribute must produce a JSON array, got: " + arr);
        assertEquals(3, arr.size());
        assertEquals("a", arr.get(0).asText());
        assertEquals(1, arr.get(1).asInt());
        assertFalse(arr.get(2).asBoolean());
    }

    @Test
    @DisplayName("Binary (B) is base64-encoded, not stringified")
    void binaryAttribute() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        AttributeValue b = AttributeValue.builder()
                .b(SdkBytes.fromByteArray(payload)).build();
        Map<String, AttributeValue> image = Map.of("blob", b);

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        assertTrue(out.get("blob").isTextual(), "B should round-trip as base64 text");
        assertEquals(Base64.getEncoder().encodeToString(payload), out.get("blob").asText());
    }

    @Test
    @DisplayName("StringSet (SS) becomes a JSON array of strings")
    void stringSetAttribute() {
        AttributeValue ss = AttributeValue.builder().ss("a", "b", "c").build();
        Map<String, AttributeValue> image = Map.of("tags", ss);

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        JsonNode arr = out.get("tags");
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
        assertEquals("a", arr.get(0).asText());
        assertEquals("c", arr.get(2).asText());
    }

    @Test
    @DisplayName("NumberSet (NS) becomes a JSON array of numbers")
    void numberSetAttribute() {
        AttributeValue ns = AttributeValue.builder().ns("1", "2", "3.5").build();
        Map<String, AttributeValue> image = Map.of("scores", ns);

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        JsonNode arr = out.get("scores");
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
        assertEquals(1, arr.get(0).asInt());
        assertEquals(2, arr.get(1).asInt());
        assertEquals(3.5, arr.get(2).asDouble(), 0.0001);
    }

    @Test
    @DisplayName("BinarySet (BS) becomes a JSON array of base64 strings")
    void binarySetAttribute() {
        AttributeValue bs = AttributeValue.builder()
                .bs(SdkBytes.fromByteArray("a".getBytes(StandardCharsets.UTF_8)),
                        SdkBytes.fromByteArray("b".getBytes(StandardCharsets.UTF_8)))
                .build();
        Map<String, AttributeValue> image = Map.of("blobs", bs);

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        JsonNode arr = out.get("blobs");
        assertTrue(arr.isArray());
        assertEquals(2, arr.size());
        assertEquals(Base64.getEncoder().encodeToString("a".getBytes(StandardCharsets.UTF_8)),
                arr.get(0).asText());
        assertEquals(Base64.getEncoder().encodeToString("b".getBytes(StandardCharsets.UTF_8)),
                arr.get(1).asText());
    }

    @Test
    @DisplayName("deeply nested M containing L containing M is recursively mapped end-to-end")
    void deeplyNested() {
        // {"order": {"items": [{"sku": "abc", "qty": 2}]}}
        Map<String, AttributeValue> innerItem = new LinkedHashMap<>();
        innerItem.put("sku", AttributeValue.builder().s("abc").build());
        innerItem.put("qty", AttributeValue.builder().n("2").build());

        AttributeValue itemsList = AttributeValue.builder()
                .l(List.of(AttributeValue.builder().m(innerItem).build()))
                .build();

        Map<String, AttributeValue> orderMap = new LinkedHashMap<>();
        orderMap.put("items", itemsList);

        Map<String, AttributeValue> image = Map.of(
                "order", AttributeValue.builder().m(orderMap).build());

        ObjectNode out = DynamoChangeFeed.imageToJson(image);

        JsonNode item = out.path("order").path("items").path(0);
        assertTrue(item.isObject(), "deeply nested M must remain a JSON object, got: " + out);
        assertEquals("abc", item.path("sku").asText());
        assertEquals(2, item.path("qty").asInt());

        // Defence-in-depth: assert no debug-style "AttributeValue(" leaks anywhere.
        assertFalse(out.toString().contains("AttributeValue("),
                "imageToJson must never emit SDK debug strings; got: " + out);
    }
}
