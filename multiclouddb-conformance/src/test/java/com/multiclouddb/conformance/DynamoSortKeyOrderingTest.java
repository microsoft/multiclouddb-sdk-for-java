// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;

/**
 * DynamoDB sort-key ordering conformance test.
 * <p>
 * Verifies that DynamoDB queries and scans return items in ascending sort-key
 * order by default — both within a partition (implicit DynamoDB Query ordering)
 * and across partitions (post-retrieval sort on Scan results).
 * <p>
 * By default targets DynamoDB Local on http://localhost:8000. Override via
 * system properties:
 * <ul>
 *   <li>{@code -Ddynamo.endpoint=http://localhost:8000} (omit for real AWS)</li>
 *   <li>{@code -Ddynamo.region=eu-north-1}</li>
 *   <li>{@code -Ddynamo.database=local} and {@code -Ddynamo.collection=todos}</li>
 * </ul>
 */
@Tag("dynamo")
class DynamoSortKeyOrderingTest extends SortKeyOrderingConformanceTest {

    private static final String DATABASE = System.getProperty("dynamo.database", "local");
    private static final String COLLECTION = System.getProperty("dynamo.collection", "todos");
    private static final String TABLE = DATABASE + "__" + COLLECTION;

    private static final String ENDPOINT = System.getProperty(
            "dynamo.endpoint", "http://localhost:8000");
    private static final String REGION = System.getProperty(
            "dynamo.region", "us-east-1");

    @BeforeAll
    static void ensureTable() {
        // Only create/reset the table when targeting DynamoDB Local
        if (!ENDPOINT.contains("localhost") && !ENDPOINT.contains("127.0.0.1")) {
            return;
        }
        try (DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                .build()) {
            try {
                ddb.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
            } catch (ResourceNotFoundException ignored) {
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
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("region", REGION);

        // For DynamoDB Local, override endpoint and use fake credentials
        if (ENDPOINT.contains("localhost") || ENDPOINT.contains("127.0.0.1")) {
            builder.connection("endpoint", ENDPOINT)
                   .auth("accessKeyId", "fakeMyKeyId")
                   .auth("secretAccessKey", "fakeSecretAccessKey");
        }
        // For cloud DynamoDB, use default AWS credential chain (no explicit creds)

        return MulticloudDbClientFactory.create(builder.build());
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, COLLECTION);
    }

    @Override
    protected String sortKeyFieldName() {
        // DynamoDB stores the sort key as the "sortKey" attribute
        return "sortKey";
    }
}
