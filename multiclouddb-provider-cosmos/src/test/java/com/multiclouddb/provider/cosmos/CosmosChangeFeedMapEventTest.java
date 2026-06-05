// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link CosmosChangeFeed#mapEvent}'s partition-key
 * resolution. The original implementation hardcoded {@code "partitionKey"}
 * which would silently drop DELETE events for any container with a non-default
 * partition-key path.
 */
class CosmosChangeFeedMapEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResourceAddress ADDR = new ResourceAddress("db", "col");

    @Test
    @DisplayName("DELETE event with custom PK path 'customerId' is mapped (not silently dropped)")
    void deleteEventWithCustomPkPath() throws Exception {
        // AVAD DELETE shape: current is empty, previous holds the pre-image.
        // The container's PK path is /customerId — not the default /partitionKey.
        ObjectNode raw = (ObjectNode) MAPPER.readTree(
                "{"
                        + "\"metadata\":{\"operationType\":\"delete\",\"_lsn\":\"42\"},"
                        + "\"current\":{},"
                        + "\"previous\":{\"customerId\":\"cust-1\",\"id\":\"order-7\","
                        + "\"_ts\":1700000000}"
                        + "}");

        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        ChangeEvent ev = CosmosChangeFeed.mapEvent(raw, req, "customerId");

        assertNotNull(ev, "DELETE event with custom PK path must NOT be dropped");
        assertEquals(ChangeType.DELETE, ev.eventType());
        assertEquals("cust-1", ev.key().partitionKey());
        assertEquals("order-7", ev.key().sortKey());
        assertEquals("42", ev.eventId());
    }

    @Test
    @DisplayName("CREATE event uses pkField from current image")
    void createEventWithCustomPkPath() throws Exception {
        ObjectNode raw = (ObjectNode) MAPPER.readTree(
                "{"
                        + "\"metadata\":{\"operationType\":\"create\",\"_lsn\":\"100\"},"
                        + "\"current\":{\"userId\":\"u-1\",\"id\":\"i-1\",\"score\":42,\"_ts\":1700000000}"
                        + "}");
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        ChangeEvent ev = CosmosChangeFeed.mapEvent(raw, req, "userId");

        assertNotNull(ev);
        assertEquals(ChangeType.CREATE, ev.eventType());
        assertEquals("u-1", ev.key().partitionKey());
        assertEquals("i-1", ev.key().sortKey());
        assertNotNull(ev.data());
        assertEquals(42, ev.data().get("score").asInt());
    }

    @Test
    @DisplayName("falls back to system-field '_pk' when configured pkField missing")
    void fallsBackToSystemPkAlias() throws Exception {
        // Some AVAD payloads expose the PK under the system alias `_pk`.
        ObjectNode raw = (ObjectNode) MAPPER.readTree(
                "{"
                        + "\"metadata\":{\"operationType\":\"update\",\"_lsn\":\"5\"},"
                        + "\"current\":{\"_pk\":\"pk-via-alias\",\"id\":\"i-1\",\"_ts\":1700000000}"
                        + "}");
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        ChangeEvent ev = CosmosChangeFeed.mapEvent(raw, req, "userId");

        assertNotNull(ev, "must fall back to _pk system alias when configured pkField is missing");
        assertEquals("pk-via-alias", ev.key().partitionKey());
    }

    @Test
    @DisplayName("record without pkField, _pk, or any usable key is dropped (logged at debug)")
    void dropsRecordWithoutAnyKey() throws Exception {
        ObjectNode raw = (ObjectNode) MAPPER.readTree(
                "{"
                        + "\"metadata\":{\"operationType\":\"delete\",\"_lsn\":\"5\"},"
                        + "\"current\":{},"
                        + "\"previous\":{\"unrelated\":\"x\",\"_ts\":1700000000}"
                        + "}");
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        ChangeEvent ev = CosmosChangeFeed.mapEvent(raw, req, "customerId");
        assertNull(ev, "records without any extractable PK must be dropped, not coerced");
    }

    @Test
    @DisplayName("hierarchical PK path 'address/city' walks nested object")
    void hierarchicalPkPathReadsNestedValue() throws Exception {
        ObjectNode raw = (ObjectNode) MAPPER.readTree(
                "{"
                        + "\"metadata\":{\"operationType\":\"create\",\"_lsn\":\"9\"},"
                        + "\"current\":{\"address\":{\"city\":\"Seattle\",\"state\":\"WA\"},"
                        + "\"id\":\"i-9\",\"_ts\":1700000000}"
                        + "}");
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        ChangeEvent ev = CosmosChangeFeed.mapEvent(raw, req, "address/city");

        assertNotNull(ev, "hierarchical PK path must resolve to nested scalar, not be dropped");
        assertEquals("Seattle", ev.key().partitionKey());
    }

    @Test
    @DisplayName("hierarchical PK with missing intermediate node falls back to _pk")
    void hierarchicalPkPathFallsBackToSystemPk() throws Exception {
        ObjectNode raw = (ObjectNode) MAPPER.readTree(
                "{"
                        + "\"metadata\":{\"operationType\":\"delete\",\"_lsn\":\"10\"},"
                        + "\"current\":{},"
                        + "\"previous\":{\"_pk\":\"Seattle\",\"id\":\"i-10\",\"_ts\":1700000000}"
                        + "}");
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        ChangeEvent ev = CosmosChangeFeed.mapEvent(raw, req, "address/city");

        assertNotNull(ev);
        assertEquals("Seattle", ev.key().partitionKey());
    }

    @Test
    @DisplayName("readPkValue returns null for non-scalar leaf")
    void readPkValueRejectsNonScalarLeaf() throws Exception {
        ObjectNode src = (ObjectNode) MAPPER.readTree(
                "{\"address\":{\"city\":{\"name\":\"Seattle\"}}}");
        assertNull(CosmosChangeFeed.readPkValue(src, "address/city"),
                "non-scalar leaf must return null so caller can fall back");
    }
}
