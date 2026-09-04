// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamoDB conformance test running against DynamoDB Local.
 * <p>
 * Prerequisites: DynamoDB Local running on http://localhost:8000.
 * The table "local__todos" is auto-created if absent.
 * <p>
 * DynamoProviderClient resolves {@code ResourceAddress("local", "todos")}
 * to the physical table name {@code "local__todos"} because DynamoDB has
 * no native database concept.
 */
@Tag("dynamo")
@Tag("emulator")
class DynamoConformanceTest extends CrudConformanceTests {

        private static final String DATABASE = "local";
        private static final String COLLECTION = "todos";
        /** Physical table name: database__collection (DynamoDB convention). */
        private static final String TABLE = DATABASE + "__" + COLLECTION;

        private static final String ENDPOINT = System.getProperty(
                        "dynamo.endpoint", "http://localhost:8000");
        private static final String REGION = System.getProperty(
                        "dynamo.region", "us-east-1");

        @BeforeAll
        static void ensureTable() {
                try (DynamoDbClient ddb = DynamoDbClient.builder()
                                .endpointOverride(URI.create(ENDPOINT))
                                .region(Region.of(REGION))
                                .credentialsProvider(StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                                .build()) {
                        // Drop existing table to ensure correct key schema
                        try {
                                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
                        } catch (ResourceNotFoundException ignored) {
                                // table didn't exist
                        }
                        ddb.createTable(CreateTableRequest.builder()
                                        .tableName(TABLE)
                                        .keySchema(
                                                        KeySchemaElement.builder()
                                                                        .attributeName("partitionKey")
                                                                        .keyType(KeyType.HASH)
                                                                        .build(),
                                                        KeySchemaElement.builder()
                                                                        .attributeName("sortKey").keyType(KeyType.RANGE)
                                                                        .build())
                                        .attributeDefinitions(
                                                        AttributeDefinition.builder()
                                                                        .attributeName("partitionKey")
                                                                        .attributeType(ScalarAttributeType.S).build(),
                                                        AttributeDefinition.builder()
                                                                        .attributeName("sortKey")
                                                                        .attributeType(ScalarAttributeType.S).build())
                                        .billingMode(BillingMode.PAY_PER_REQUEST)
                                        .build());
                }
        }

        @Override
        protected MulticloudDbClient createClient() {
                MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                                .provider(ProviderId.DYNAMO)
                                .connection("endpoint", ENDPOINT)
                                .connection("region", REGION)
                                .auth("accessKeyId", "fakeMyKeyId")
                                .auth("secretAccessKey", "fakeSecretAccessKey")
                                .build();
                return MulticloudDbClientFactory.create(config);
        }

        @Override
        protected ResourceAddress getAddress() {
                return new ResourceAddress(DATABASE, COLLECTION);
        }

        @Test
        @Order(27)
        @DisplayName("Dynamo result-item size overflow is normalized and atomic")
        void resultItemSizeOverflowIsNormalizedAndLeavesItemUnchanged() throws Exception {
                MulticloudDbKey key = ConformanceHarness.uniqueKey("dynamo-result-size");
                String itemId = key.partitionKey();
                Map<String, AttributeValue> item = new LinkedHashMap<>();
                item.put("partitionKey", AttributeValue.fromS(itemId));
                item.put("sortKey", AttributeValue.fromS(itemId));
                item.put("payload", AttributeValue.fromS(""));

                int targetSeedBytes = 409_000;
                int fixedBytes = stringItemBytes(item);
                String payload = "A".repeat(targetSeedBytes - fixedBytes);
                item.put("payload", AttributeValue.fromS(payload));

                String smallUpdate = "B".repeat(1_024);
                assertEquals(targetSeedBytes, stringItemBytes(item));
                assertTrue(targetSeedBytes + utf8Length("title") + utf8Length(smallUpdate)
                                > 409_600,
                        "The small update must push the result above DynamoDB's 400 KiB limit");

                try (DynamoDbClient ddb = DynamoDbClient.builder()
                                .endpointOverride(URI.create(ENDPOINT))
                                .region(Region.of(REGION))
                                .credentialsProvider(StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create(
                                                                "fakeMyKeyId",
                                                                "fakeSecretAccessKey")))
                                .build()) {
                        try {
                                ddb.putItem(PutItemRequest.builder()
                                                .tableName(TABLE)
                                                .item(item)
                                                .build());

                                try (MulticloudDbClient portableClient = createClient()) {
                                        MulticloudDbException ex = assertThrows(
                                                        MulticloudDbException.class,
                                                        () -> portableClient.update(
                                                                        getAddress(),
                                                                        key,
                                                                        Map.of("title", smallUpdate)));

                                        assertEquals(
                                                        MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                                                        ex.error().category(),
                                                        ex.error().toString());
                                        assertEquals("update", ex.error().operation());
                                        assertFalse(ex.error().retryable());
                                        assertEquals("partial_update_extended_payload",
                                                        ex.error().providerDetails()
                                                                        .get("capability"));
                                        assertEquals("dynamodb_result_item_size_limit",
                                                        ex.error().providerDetails().get("reason"));
                                        assertEquals("409600",
                                                        ex.error().providerDetails()
                                                                        .get("maximumResultBytes"));
                                }

                                Map<String, AttributeValue> stored = ddb.getItem(
                                                GetItemRequest.builder()
                                                                .tableName(TABLE)
                                                                .key(Map.of(
                                                                                "partitionKey",
                                                                                AttributeValue.fromS(itemId),
                                                                                "sortKey",
                                                                                AttributeValue.fromS(itemId)))
                                                                .consistentRead(true)
                                                                .build())
                                                .item();
                                assertEquals(payload, stored.get("payload").s());
                                assertFalse(stored.containsKey("title"),
                                                "Rejected UpdateItem must leave the stored item unchanged");
                        } finally {
                                ddb.deleteItem(DeleteItemRequest.builder()
                                                .tableName(TABLE)
                                                .key(Map.of(
                                                                "partitionKey",
                                                                AttributeValue.fromS(itemId),
                                                                "sortKey",
                                                                AttributeValue.fromS(itemId)))
                                                .build());
                        }
                }
        }

        private static int stringItemBytes(Map<String, AttributeValue> item) {
                return item.entrySet().stream()
                                .mapToInt(entry -> utf8Length(entry.getKey())
                                                + utf8Length(entry.getValue().s()))
                                .sum();
        }

        private static int utf8Length(String value) {
                return value.getBytes(StandardCharsets.UTF_8).length;
        }
}
