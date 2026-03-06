package com.hyperscaledb.conformance;

import com.hyperscaledb.api.*;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;

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
        protected HyperscaleDbClient createClient() {
                HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                                .provider(ProviderId.DYNAMO)
                                .connection("endpoint", ENDPOINT)
                                .connection("region", REGION)
                                .auth("accessKeyId", "fakeMyKeyId")
                                .auth("secretAccessKey", "fakeSecretAccessKey")
                                .build();
                return HyperscaleDbClientFactory.create(config);
        }

        @Override
        protected ResourceAddress getAddress() {
                return new ResourceAddress(DATABASE, COLLECTION);
        }
}
