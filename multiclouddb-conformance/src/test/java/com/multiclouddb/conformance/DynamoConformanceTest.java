// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DynamoDB conformance test running against DynamoDB Local. */
@Tag("dynamo")
@Tag("emulator")
class DynamoConformanceTest extends CrudConformanceTests
        implements PartialUpdateResultLimitConformanceTest {

    private static final String DATABASE = "local";
    private static final String COLLECTION = "todos";
    private static final String TABLE = DATABASE + "__" + COLLECTION;

    private static final String ENDPOINT = System.getProperty(
            "dynamo.endpoint", "http://localhost:8000");
    private static final String REGION = System.getProperty(
            "dynamo.region", "us-east-1");

    @BeforeAll
    static void ensureTable() {
        try (DynamoDbClient ddb = nativeClient()) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
            } catch (ResourceNotFoundException ignored) {
                // The table did not exist.
            }
            ddb.createTable(CreateTableRequest.builder()
                    .tableName(TABLE)
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("partitionKey")
                                    .keyType(KeyType.HASH)
                                    .build(),
                            KeySchemaElement.builder()
                                    .attributeName("sortKey")
                                    .keyType(KeyType.RANGE)
                                    .build())
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("partitionKey")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("sortKey")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
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

    @Override
    public MulticloudDbClient createResultLimitClient() {
        return createClient();
    }

    @Override
    public ResourceAddress resultLimitAddress() {
        return getAddress();
    }

    @Override
    public PartialUpdateResultLimitScenario resultLimitScenario() {
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
                "The update must push the result above DynamoDB's 400 KiB limit");

        Map<String, AttributeValue> nativeKey = Map.of(
                "partitionKey", AttributeValue.fromS(itemId),
                "sortKey", AttributeValue.fromS(itemId));

        return new PartialUpdateResultLimitScenario(
                key,
                Map.of("title", smallUpdate),
                "dynamodb_result_item_size_limit",
                "409600",
                () -> {
                    try (DynamoDbClient ddb = nativeClient()) {
                        ddb.putItem(PutItemRequest.builder()
                                .tableName(TABLE)
                                .item(item)
                                .build());
                    }
                },
                () -> {
                    try (DynamoDbClient ddb = nativeClient()) {
                        Map<String, AttributeValue> stored = ddb.getItem(
                                GetItemRequest.builder()
                                        .tableName(TABLE)
                                        .key(nativeKey)
                                        .consistentRead(true)
                                        .build())
                                .item();
                        assertEquals(payload, stored.get("payload").s());
                        assertFalse(stored.containsKey("title"),
                                "Rejected update must leave the stored item unchanged");
                    }
                },
                () -> {
                    try (DynamoDbClient ddb = nativeClient()) {
                        ddb.deleteItem(DeleteItemRequest.builder()
                                .tableName(TABLE)
                                .key(nativeKey)
                                .build());
                    }
                });
    }

    private static DynamoDbClient nativeClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                "fakeMyKeyId", "fakeSecretAccessKey")))
                .build();
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