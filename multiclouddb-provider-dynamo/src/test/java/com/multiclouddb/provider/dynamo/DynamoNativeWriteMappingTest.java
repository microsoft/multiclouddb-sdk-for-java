// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClientBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dynamo-native request checks with mocked SDK calls and package-local mapper access. */
class DynamoNativeWriteMappingTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("database", "documents");
    private static final List<MulticloudDbKey> KEYS = List.of(
            MulticloudDbKey.of("tenant", "record"), MulticloudDbKey.of("tenant"));
    private static final List<OperationOptions> OPTIONS = List.of(
            OperationOptions.defaults(), OperationOptions.builder().ttlSeconds(120).build());

    private static List<Map<String, Object>> fixtures() {
        var numbers = List.<Number>of((byte) -2, (short) 3, 4, Long.MIN_VALUE, Long.MAX_VALUE,
                0.1F, Float.MIN_VALUE, Float.MAX_VALUE, -0.0F, -0.0D, Double.MIN_VALUE, Double.MAX_VALUE,
                new BigInteger("123456789012345678901234567890"),
                new BigDecimal("1.00"), new BigDecimal("1E+10"),
                new BigDecimal("0.000"), new BigDecimal("1E-1024"));
        var fields = new LinkedHashMap<String, Object>();
        for (int i = 0; i < numbers.size(); i++) fields.put("n" + i, numbers.get(i));
        fields.put("strings", List.of("", "\"\\\b\f\n\r\t\u0001", "\u00e9\u4e2d",
                "\ud83d\ude80", "\ud800", "\udfff", "\u0001mcdb:json:{}", "{\"literal\":true}"));
        fields.put("fieldNames", Map.of("quote\"\\\n\u0001\u4e2d\ud800", "literal"));
        fields.put("nested", List.of(new LinkedHashMap<>(fields), Map.of(), List.of()));
        fields.put("nullValue", null);
        fields.put("flag", true);
        fields.put("partitionKey", "payload-pk");
        fields.put("sortKey", "payload-sk");
        fields.put("id", "payload-id");
        fields.put("ttl", 7);
        fields.put("ttlExpiry", 8L);
        fields.put("escaped", "\u0001mcdb:json:[]");
        var wide = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 256; i++) wide.put("f" + i, List.of(i, "v" + i));
        Object deep = "leaf";
        for (int i = 0; i < 31; i++) deep = i % 2 == 0 ? List.of(deep) : Map.of("child", deep);
        return List.of(Map.of(), fields, wide,
                Map.of("deep", deep), Map.of("deep", List.of(deep)),
                Map.of("large", "x".repeat(409600)));
    }

    private static void write(MulticloudDbClient client, String operation, MulticloudDbKey key,
                              Map<String, Object> document, OperationOptions options) {
        switch (operation) {
            case "create" -> client.create(ADDRESS, key, document, options);
            case "update" -> client.update(ADDRESS, key, document, options);
            case "upsert" -> client.upsert(ADDRESS, key, document, options);
            default -> throw new AssertionError(operation);
        }
    }

    @Test
    void dynamoAllWriteOperationsMatchLegacyNativeRequests() throws Exception {
        DynamoDbClient sdk = mock(DynamoDbClient.class);
        DynamoDbStreamsClient streams = mock(DynamoDbStreamsClient.class);
        DynamoDbClientBuilder builder = mock(DynamoDbClientBuilder.class, RETURNS_SELF);
        DynamoDbStreamsClientBuilder streamBuilder = mock(DynamoDbStreamsClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(sdk);
        when(streamBuilder.build()).thenReturn(streams);
        PutItemResponse response = mock(PutItemResponse.class);
        when(response.responseMetadata()).thenReturn(mock(DynamoDbResponseMetadata.class));
        when(sdk.putItem(any(PutItemRequest.class))).thenReturn(response);
        try (MockedStatic<DynamoDbClient> clients = mockStatic(DynamoDbClient.class);
             MockedStatic<DynamoDbStreamsClient> streamClients = mockStatic(DynamoDbStreamsClient.class)) {
            clients.when(DynamoDbClient::builder).thenReturn(builder);
            streamClients.when(DynamoDbStreamsClient::builder).thenReturn(streamBuilder);
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.DYNAMO).connection(Map.of("region", "us-east-1")).build())) {
                for (Map<String, Object> input : fixtures()) {
                    Map<String, Object> before = new LinkedHashMap<>(input);
                    for (var key : KEYS) for (var options : OPTIONS) {
                        for (String op : List.of("create", "update", "upsert")) {
                            clearInvocations(sdk);
                            long start = Instant.now().getEpochSecond();
                            write(client, op, key, input, options);
                            long end = Instant.now().getEpochSecond();
                            var captor = org.mockito.ArgumentCaptor.forClass(PutItemRequest.class);
                            verify(sdk).putItem(captor.capture());
                            verifyNoMoreInteractions(sdk);
                            PutItemRequest actual = captor.getValue();
                            if (input.containsKey("deep")) {
                                assertEquals(input.get("deep"),
                                        DynamoItemMapper.attributeMapToMap(actual.item()).get("deep"));
                                assertTrue(nativeDepth(actual.item().get("deep")) == 31
                                        || nativeDepth(actual.item().get("deep")) == 32);
                                assertEquals(documentDepth(input.get("deep")),
                                        nativeDepth(actual.item().get("deep")));
                            }
                            // Native envelope and operation policy are checked independently of the mapper.
                            var expectedItem = DynamoItemMapper.mapToAttributeMap(before);
                            expectedItem.put("partitionKey", AttributeValue.fromS(key.partitionKey()));
                            expectedItem.put("sortKey", AttributeValue.fromS(
                                    key.sortKey() == null ? key.partitionKey() : key.sortKey()));
                            if (options.ttlSeconds() != null) {
                                long expiry = Long.parseLong(actual.item().get("ttlExpiry").n());
                                assertTrue(expiry >= start + 120 && expiry <= end + 120);
                                expectedItem.put("ttlExpiry", AttributeValue.fromN(Long.toString(expiry)));
                            }
                            assertEquals(PutItemRequest.builder().tableName("database__documents")
                                    .item(expectedItem).returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                                    .conditionExpression(switch (op) {
                                        case "create" -> "attribute_not_exists(partitionKey)";
                                        case "update" -> "attribute_exists(partitionKey)";
                                        default -> null;
                                    }).build(), actual);
                            assertEquals(before, new LinkedHashMap<>(input));
                        }
                    }
                }
            }
        }
    }

    private static int nativeDepth(AttributeValue value) {
        if (value.type() == AttributeValue.Type.M) {
            return 1 + value.m().values().stream().mapToInt(DynamoNativeWriteMappingTest::nativeDepth).max().orElse(0);
        }
        if (value.type() == AttributeValue.Type.L) {
            return 1 + value.l().stream().mapToInt(DynamoNativeWriteMappingTest::nativeDepth).max().orElse(0);
        }
        return 0;
    }

    private static int documentDepth(Object value) {
        if (value instanceof Map<?, ?> map) {
            return 1 + map.values().stream().mapToInt(DynamoNativeWriteMappingTest::documentDepth).max().orElse(0);
        }
        if (value instanceof List<?> list) {
            return 1 + list.stream().mapToInt(DynamoNativeWriteMappingTest::documentDepth).max().orElse(0);
        }
        return 0;
    }
}
