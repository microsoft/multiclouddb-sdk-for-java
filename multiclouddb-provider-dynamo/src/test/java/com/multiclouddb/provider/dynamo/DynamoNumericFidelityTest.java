// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbResponseMetadata;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoNumericFidelityTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("testdb", "numbers");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("numeric-pk", "numeric-sk");
    private static final List<String> NUMBERS = List.of(
            "0.12345678901234567890123456789012345678",
            "1e3",
            "-1.2345678901234567890123456789012345678E+125",
            "99999999999999999999999999999999999999",
            "-99999999999999999999999999999999999999",
            "1E-130");
    private static final SdkHttpResponse HTTP_OK = SdkHttpResponse.builder().statusCode(200).build();

    private DynamoDbClient dynamo;
    private DynamoProviderClient client;
    private DynamoDbResponseMetadata metadata;

    @BeforeEach
    void setUp() {
        dynamo = mock(DynamoDbClient.class);
        client = new DynamoProviderClient(dynamo);
        metadata = mock(DynamoDbResponseMetadata.class);
        when(metadata.requestId()).thenReturn("numeric-test");
    }

    @Test
    void readRoundTripPreservesNativeNumbers() {
        GetItemResponse response = mock(GetItemResponse.class);
        when(response.hasItem()).thenReturn(true);
        when(response.item()).thenReturn(nativeItem());
        when(response.responseMetadata()).thenReturn(metadata);
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(response);

        DocumentResult result = client.read(ADDRESS, KEY, null);

        assertNotNull(result);
        assertEncodedNumbers(DynamoItemMapper.jsonNodeToAttributeMap(result.document()));
    }

    @ParameterizedTest
    @EnumSource(QueryPath.class)
    void queryAndUpsertRoundTripPreservesNativeNumbers(QueryPath path) {
        QueryRequest.Builder request = QueryRequest.builder();
        QueryPage page;
        switch (path) {
            case PARTITION, FILTERED_PARTITION -> {
                QueryResponse.Builder response = QueryResponse.builder().items(nativeItem());
                response.sdkHttpResponse(HTTP_OK);
                when(dynamo.query(any(software.amazon.awssdk.services.dynamodb.model.QueryRequest.class)))
                        .thenReturn(response.build());
                request.partitionKey(KEY.partitionKey());
                if (path == QueryPath.FILTERED_PARTITION) {
                    request.expression("n0 > :min").parameter("min", 0);
                }
                page = client.query(ADDRESS, request.build(), null);
            }
            case SCAN, FILTERED_SCAN -> {
                ScanResponse.Builder response = ScanResponse.builder().items(nativeItem());
                response.sdkHttpResponse(HTTP_OK);
                when(dynamo.scan(any(ScanRequest.class)))
                        .thenReturn(response.build());
                if (path == QueryPath.FILTERED_SCAN) {
                    request.expression("n0 > :min").parameter("min", 0);
                }
                page = client.query(ADDRESS, request.build(), null);
            }
            case NATIVE_PARTIQL, TRANSLATED_PARTIQL -> {
                ExecuteStatementResponse response = mock(ExecuteStatementResponse.class);
                when(response.items()).thenReturn(List.of(nativeItem()));
                when(response.sdkHttpResponse()).thenReturn(HTTP_OK);
                when(response.responseMetadata()).thenReturn(metadata);
                when(dynamo.executeStatement(any(ExecuteStatementRequest.class))).thenReturn(response);
                if (path == QueryPath.NATIVE_PARTIQL) {
                    page = client.query(ADDRESS,
                            request.nativeExpression("SELECT * FROM \"testdb__numbers\"").build(), null);
                } else {
                    TranslatedQuery translated = TranslatedQuery.withPositionalParameters(
                            "SELECT * FROM \"numbers\" WHERE \"n0\" > ?", "\"n0\" > ?", List.of(0));
                    page = client.queryWithTranslation(ADDRESS, translated, request.build(), null);
                }
            }
            default -> throw new AssertionError("Unhandled query path: " + path);
        }

        assertEquals(1, page.items().size());
        PutItemResponse response = mock(PutItemResponse.class);
        when(response.responseMetadata()).thenReturn(metadata);
        when(dynamo.putItem(any(PutItemRequest.class))).thenReturn(response);
        client.upsert(ADDRESS, KEY, page.items().get(0), null);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(captor.capture());
        assertEncodedNumbers(captor.getValue().item());
    }

    /**
     * Exercises native UpdateItem responses without depending on the pending portable
     * partial-update API (#105) or defining the future portable numeric domain (#111).
     */
    @Test
    @Tag("dynamo")
    @Tag("emulator")
    void nativeReadQueryAndPartialUpdateRoundTripPreservesNumbers() {
        String endpoint = System.getProperty("dynamo.endpoint", "http://localhost:8000");
        String region = System.getProperty("dynamo.region", "us-east-1");
        ResourceAddress address = new ResourceAddress("local", "numeric-" + UUID.randomUUID());
        String table = address.database() + "__" + address.collection();
        Map<String, AttributeValue> key = Map.of(
                "partitionKey", AttributeValue.fromS(KEY.partitionKey()),
                "sortKey", AttributeValue.fromS(KEY.sortKey()));
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", endpoint)
                .connection("region", region)
                .auth("accessKeyId", "fakeMyKeyId")
                .auth("secretAccessKey", "fakeSecretAccessKey")
                .build();

        try (DynamoDbClient nativeDynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(10)))
                .build();
                DynamoProviderClient nativeClient = new DynamoProviderClient(config)) {
            nativeDynamo.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .keySchema(
                            KeySchemaElement.builder().attributeName("partitionKey").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sortKey").keyType(KeyType.RANGE).build())
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("partitionKey")
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sortKey")
                                    .attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
            try {
                try (DynamoDbWaiter waiter = nativeDynamo.waiter()) {
                    waiter.waitUntilTableExists(builder -> builder.tableName(table))
                            .matched().response().orElseThrow();
                }
                nativeDynamo.putItem(PutItemRequest.builder().tableName(table).item(nativeItem()).build());

                DocumentResult read = nativeClient.read(address, KEY, null);
                assertNotNull(read);
                assertEncodedNumbers(DynamoItemMapper.jsonNodeToAttributeMap(read.document()));
                QueryRequest query = QueryRequest.builder().partitionKey(KEY.partitionKey()).build();
                QueryPage page = nativeClient.query(address, query, null);
                assertEquals(1, page.items().size());
                assertEncodedNumbers(DynamoItemMapper.mapToAttributeMap(page.items().get(0)));

                UpdateItemResponse updated = nativeDynamo.updateItem(UpdateItemRequest.builder()
                        .tableName(table)
                        .key(key)
                        .updateExpression("SET #copy = :copy")
                        .expressionAttributeNames(Map.of("#copy", "copy"))
                        .expressionAttributeValues(Map.of(":copy", AttributeValue.fromM(nativeItem())))
                        .returnValues(ReturnValue.ALL_NEW)
                        .build());
                JsonNode updatedDocument = DynamoItemMapper.attributeMapToJsonNode(updated.attributes());
                assertEncodedNumbers(DynamoItemMapper.jsonNodeToAttributeMap(updatedDocument));
                assertEncodedNumbers(DynamoItemMapper.jsonNodeToAttributeMap(updatedDocument.get("copy")));

                DocumentResult reread = nativeClient.read(address, KEY, null);
                assertNotNull(reread);
                assertEncodedNumbers(DynamoItemMapper.jsonNodeToAttributeMap(reread.document()));
                assertEncodedNumbers(DynamoItemMapper.jsonNodeToAttributeMap(reread.document().get("copy")));
                QueryPage updatedPage = nativeClient.query(address, query, null);
                assertEquals(1, updatedPage.items().size());
                nativeClient.upsert(address, KEY, updatedPage.items().get(0), null);

                Map<String, AttributeValue> stored = nativeDynamo.getItem(GetItemRequest.builder()
                        .tableName(table).key(key).consistentRead(true).build()).item();
                assertEncodedNumbers(stored);
                assertEncodedNumbers(stored.get("copy").m());
            } finally {
                nativeDynamo.deleteTable(DeleteTableRequest.builder().tableName(table).build());
            }
        }
    }

    private static Map<String, AttributeValue> nativeItem() {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("partitionKey", AttributeValue.fromS(KEY.partitionKey()));
        item.put("sortKey", AttributeValue.fromS(KEY.sortKey()));
        for (int i = 0; i < NUMBERS.size(); i++) {
            item.put("n" + i, AttributeValue.fromN(NUMBERS.get(i)));
        }
        item.put("numbers", AttributeValue.fromNs(NUMBERS));
        item.put("nested", AttributeValue.fromM(Map.of(
                "numbers", AttributeValue.fromL(NUMBERS.stream().map(AttributeValue::fromN).toList()))));
        return item;
    }

    private static void assertEncodedNumbers(Map<String, AttributeValue> item) {
        for (int i = 0; i < NUMBERS.size(); i++) {
            BigDecimal expected = new BigDecimal(NUMBERS.get(i));
            assertEquals(0, expected.compareTo(new BigDecimal(item.get("n" + i).n())));
            assertEquals(0, expected.compareTo(
                    new BigDecimal(item.get("nested").m().get("numbers").l().get(i).n())));
        }
        List<AttributeValue> numberSet = item.get("numbers").l();
        assertEquals(NUMBERS.size(), numberSet.size());
        assertEquals(
                NUMBERS.stream().map(BigDecimal::new).map(BigDecimal::stripTrailingZeros)
                        .collect(Collectors.toSet()),
                numberSet.stream().map(value -> new BigDecimal(value.n()).stripTrailingZeros())
                        .collect(Collectors.toSet()));
    }

    enum QueryPath {
        PARTITION, FILTERED_PARTITION, SCAN, FILTERED_SCAN, NATIVE_PARTIQL, TRANSLATED_PARTIQL
    }
}
