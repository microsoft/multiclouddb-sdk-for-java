// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us14;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;

/**
 * DynamoDB change-feed conformance, running against DynamoDB Local.
 * <p>
 * <b>Provisioning prerequisite</b>: the table must have DynamoDB Streams enabled
 * with view type {@code NEW_AND_OLD_IMAGES}. The {@link #ensureTable()} hook
 * re-creates the table with the correct stream specification.
 */
@Tag("dynamo")
@Tag("emulator")
@Tag("changefeed")
class DynamoChangeFeedConformanceTest extends ChangeFeedConformanceTest {

    private static final String DATABASE = "local";
    private static final String COLLECTION = "todos_cf";
    private static final String TABLE = DATABASE + "__" + COLLECTION;

    private static final String ENDPOINT = System.getProperty("dynamo.endpoint", "http://localhost:8000");
    private static final String REGION = System.getProperty("dynamo.region", "us-east-1");

    @BeforeAll
    static void ensureTable() {
        try (DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                .build()) {
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
                                    .keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder()
                                    .attributeName("sortKey")
                                    .keyType(KeyType.RANGE).build())
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("partitionKey")
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder()
                                    .attributeName("sortKey")
                                    .attributeType(ScalarAttributeType.S).build())
                    .streamSpecification(StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
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
}
