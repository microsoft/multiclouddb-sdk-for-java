// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.spanner.*;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClientBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * End-to-end unit tests: actual factory, client wrapper and provider mappings;
 * only native SDK boundaries are mocked. These are not live persistence tests.
 */
class CustomerObjectMappingTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("database", "documents");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("tenant", "record");
    private static final TypeReference<Batch<Customer>> BATCH = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    record Customer(String displayName) {}

    // The application, not the adapter, chooses how provider root metadata is handled.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Batch<T>(String batchName, LocalDate createdOn, List<T> items, int quantity, double ratio) {}


    @Test
    void dynamoCustomerMappingThroughActualClientAndProvider() throws Exception {
        DynamoDbClient nativeClient = mock(DynamoDbClient.class);
        DynamoDbStreamsClient streams = mock(DynamoDbStreamsClient.class);
        DynamoDbClientBuilder builder = mock(DynamoDbClientBuilder.class, RETURNS_SELF);
        DynamoDbStreamsClientBuilder streamsBuilder = mock(DynamoDbStreamsClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(nativeClient);
        when(streamsBuilder.build()).thenReturn(streams);
        List<PutItemRequest> writes = new ArrayList<>();
        AtomicReference<Map<String, AttributeValue>> readFixture = new AtomicReference<>();
        DynamoDbResponseMetadata metadata = mock(DynamoDbResponseMetadata.class);
        PutItemResponse putResponse = mock(PutItemResponse.class);
        GetItemResponse getResponse = mock(GetItemResponse.class);
        when(putResponse.responseMetadata()).thenReturn(metadata);
        when(getResponse.responseMetadata()).thenReturn(metadata);
        when(getResponse.item()).thenAnswer(call -> readFixture.get());
        when(getResponse.hasItem()).thenReturn(true);
        when(nativeClient.putItem(any(PutItemRequest.class))).thenAnswer(call -> {
            writes.add(call.getArgument(0));
            return putResponse;
        });
        when(nativeClient.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            assertEquals(KEY.partitionKey(), request.key().get("partitionKey").s());
            assertEquals(KEY.sortKey(), request.key().get("sortKey").s());
            return getResponse;
        });
        try (MockedStatic<DynamoDbClient> clients = mockStatic(DynamoDbClient.class);
             MockedStatic<DynamoDbStreamsClient> streamClients = mockStatic(DynamoDbStreamsClient.class)) {
            clients.when(DynamoDbClient::builder).thenReturn(builder);
            streamClients.when(DynamoDbStreamsClient::builder).thenReturn(streamsBuilder);
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.DYNAMO).connection(Map.of("region", "us-east-1")).build())) {
                Supplier<PutItemRequest> last = () -> writes.get(writes.size() - 1);
                exercise(client, last, request -> readFixture.set(request.item()), Function.identity());
                assertNotNull(writes.get(0).conditionExpression());
                assertNull(writes.get(2).conditionExpression());
                assertEquals(KEY.partitionKey(), readFixture.get().get("partitionKey").s());
                assertLiteralMapParity(client, last, Function.identity());
                assertEquals("1", last.get().item().get("decimal_value").n());
                assertEquals("123456789012345678901234567890", last.get().item().get("integer_value").n());
                AttributeValue nested = AttributeValue.builder().s("private-value").build();
                for (int i = 0; i < 128; i++) nested = AttributeValue.builder().l(nested).build();
                readFixture.set(Map.of("deep", nested));
                assertNotNull(client.read(ADDRESS, KEY).document().get("deep"));
                readFixture.set(Map.of("legacyBinary", AttributeValue.builder()
                        .b(software.amazon.awssdk.core.SdkBytes.fromByteArray(new byte[]{1, 2})).build()));
                assertNull(client.read(ADDRESS, KEY).document().get("legacyBinary"),
                        "existing native B fallback happens before neutral conversion and is unchanged");
                assertValidationBeforeNativeOperation(client, nativeClient);
            }
        }
        verify(nativeClient).close();
        verify(streams).close();
    }

    @Test
    void spannerCustomerMappingUsesCapturedMutationIncludingFieldDataForRead() throws Exception {
        SpannerOptions.Builder builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        SpannerOptions options = mock(SpannerOptions.class);
        Spanner spanner = mock(Spanner.class);
        DatabaseClient nativeClient = mock(DatabaseClient.class);
        ReadContext read = mock(ReadContext.class);
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(spanner);
        when(spanner.getDatabaseClient(any(DatabaseId.class))).thenReturn(nativeClient);
        when(nativeClient.singleUse()).thenReturn(read);
        List<Mutation> writes = new ArrayList<>();
        AtomicReference<Mutation> readFixture = new AtomicReference<>();
        when(nativeClient.write(any())).thenAnswer(call -> {
            Iterable<Mutation> mutations = call.getArgument(0);
            mutations.forEach(writes::add);
            return com.google.cloud.Timestamp.ofTimeSecondsAndNanos(0, 0);
        });
        when(read.executeQuery(any(Statement.class))).thenAnswer(call -> {
            Struct.Builder row = Struct.newBuilder();
            readFixture.get().asMap().forEach((name, value) -> row.set(name).to(value));
            Struct capturedRow = row.build();
            return ResultSets.forRows(capturedRow.getType(), List.of(capturedRow));
        });
        try (MockedStatic<SpannerOptions> factories = mockStatic(SpannerOptions.class)) {
            factories.when(SpannerOptions::newBuilder).thenReturn(builder);
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.SPANNER)
                    .connection(Map.of("projectId", "project", "instanceId", "instance", "databaseId", "database"))
                    .build())) {
                Supplier<Mutation> last = () -> writes.get(writes.size() - 1);
                exercise(client, last, readFixture::set, Mutation::asMap);
                assertEquals(Mutation.Op.INSERT, writes.get(0).getOperation());
                assertEquals(Mutation.Op.INSERT_OR_UPDATE, writes.get(2).getOperation());
                assertTrue(readFixture.get().asMap().containsKey("data"));
                assertEquals(KEY.partitionKey(), readFixture.get().asMap().get("partitionKey").getString());
                assertLiteralMapParity(client, last, Mutation::asMap);
                assertEquals(Value.string("1.00"), last.get().asMap().get("decimal_value"),
                        "retain the existing Spanner top-level decimal STRING fallback");
                assertEquals(Value.string("123456789012345678901234567890"), last.get().asMap().get("integer_value"));
                assertEquals(Value.float64((double) 0.1F), last.get().asMap().get("float_value"),
                        "retain Float-to-FLOAT64 mapping, not decimal-to-STRING mapping");
                readFixture.set(Mutation.newInsertBuilder(ADDRESS.collection()).set("private-field")
                        .to(Value.json("[".repeat(128) + "null" + "]".repeat(128))).build());
                assertNotNull(client.read(ADDRESS, KEY).document().get("private-field"));
                readFixture.set(Mutation.newInsertBuilder(ADDRESS.collection()).set("legacyBinary")
                        .to(com.google.cloud.ByteArray.copyFrom(new byte[]{1, 2})).build());
                assertEquals("AQI=", client.read(ADDRESS, KEY).document().get("legacyBinary"),
                        "existing native BYTES-to-base64 mapping is not new Map<String, Object> binary support");
                assertValidationBeforeNativeOperation(client, nativeClient);
                Map<String, Object> reserved = Map.of("Data", 1);
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, assertThrows(MulticloudDbException.class,
                        () -> client.upsert(ADDRESS, KEY, reserved)).error().category());
                verifyNoInteractions(nativeClient);
            }
        }
        verify(spanner).close();
    }

    private static <R> void exercise(MulticloudDbClient client, Supplier<R> captured, Consumer<R> readFixture,
                                    Function<R, ?> comparable) {
        AtomicInteger serialized = new AtomicInteger();
        AtomicInteger deserialized = new AtomicInteger();
        ObjectMapper mapper = configuredMapper(serialized, deserialized);
        Batch<Customer> source = new Batch<>("customers", LocalDate.of(2026, 10, 6),
                List.of(new Customer("Ada")), 3, 1.5);
        Map<String, Object> encoded = mapper.convertValue(source, MAP);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("batch_name", "customers");
        baseline.put("created_on", "2026/10/06");
        baseline.put("items", List.of(Map.of("display_name", "Ada")));
        baseline.put("quantity", 3);
        baseline.put("ratio", 1.5);
        assertEquals(baseline, encoded);

        client.create(ADDRESS, KEY, encoded);
        R create = captured.get();
        readFixture.accept(create);
        Map<String, Object> createRead = client.read(ADDRESS, KEY).document();
        assertEquals(source, mapper.convertValue(createRead, BATCH));
        client.create(ADDRESS, KEY, baseline);
        assertEquals(comparable.apply(create), comparable.apply(captured.get()));

        client.upsert(ADDRESS, KEY, encoded);
        R upsert = captured.get();
        readFixture.accept(upsert);
        Map<String, Object> upsertRead = client.read(ADDRESS, KEY).document();
        Map<String, Object> before = upsertRead;
        assertEquals(source, mapper.convertValue(upsertRead, BATCH));
        assertEquals(before, upsertRead);
        client.upsert(ADDRESS, KEY, baseline);
        assertEquals(comparable.apply(upsert), comparable.apply(captured.get()));

        assertEquals(1, serialized.get(), "internal provider mappers must not re-run customer DTO serialization");
        assertEquals(2, deserialized.get());
        assertEquals(baseline, encoded, "client/provider must not mutate encoded input");
        assertEquals("Ada", source.items().get(0).displayName());

        PlainCustomerMapping plainCodec = new PlainCustomerMapping();
        PlainCustomerMapping.Customer plain = new PlainCustomerMapping.Customer("Grace", 7);
        Map<String, Object> independent = plainCodec.toMap(plain);
        client.create(ADDRESS, KEY, independent);
        readFixture.accept(captured.get());
        assertEquals(plain, plainCodec.fromMap(client.read(ADDRESS, KEY).document()));
        client.upsert(ADDRESS, KEY, independent);
        readFixture.accept(captured.get());
        assertEquals(plain, plainCodec.fromMap(client.read(ADDRESS, KEY).document()));

        Map<String, Object> sixKinds = new LinkedHashMap<>();
        sixKinds.put("nothing", null);
        sixKinds.put("flag", true);
        sixKinds.put("text", "AQI=");
        sixKinds.put("number", 7);
        sixKinds.put("object", Map.of("inner", false));
        sixKinds.put("array", Arrays.asList(null, true, "text", 2, Map.of()));
        client.create(ADDRESS, KEY, sixKinds);
        readFixture.accept(captured.get());
        Map<String, Object> readSixKinds = client.read(ADDRESS, KEY).document();
        assertNeutralFields(sixKinds, readSixKinds);
        client.upsert(ADDRESS, KEY, sixKinds);
        readFixture.accept(captured.get());
        Map<String, Object> upsertSixKinds = client.read(ADDRESS, KEY).document();
        assertNeutralFields(sixKinds, upsertSixKinds);

        List<Object> callerList = new ArrayList<>(List.of("original"));
        Map<String, Object> callerInput = new LinkedHashMap<>(Map.of("nested", callerList));
        client.create(ADDRESS, KEY, callerInput);
        readFixture.accept(captured.get());
        callerList.clear();
        callerInput.clear();
        Map<String, Object> detached = client.read(ADDRESS, KEY).document();
        assertEquals(List.of("original"), detached.get("nested"), "write must not retain caller containers");
        assertInstanceOf(List.class, detached.get("nested")).clear();
        detached.clear();
        assertEquals(List.of("original"), client.read(ADDRESS, KEY).document().get("nested"),
                "returned containers must be mutable and detached from the native response");
    }

    private static void assertNeutralFields(Map<String, Object> expected, Map<String, Object> actual) {
        expected.forEach((name, value) -> {
            assertTrue(actual.containsKey(name));
            if (value instanceof Number number) {
                assertEquals(number.longValue(), ((Number) actual.get(name)).longValue());
            } else {
                assertEquals(value, actual.get(name));
            }
        });
    }


    private static <R> void assertLiteralMapParity(MulticloudDbClient client, Supplier<R> captured,
                                                  Function<R, ?> comparable) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("partitionKey", "business");
        baseline.put("sortKey", "business");
        baseline.put("id", "business");
        baseline.put("float_value", 0.1f);
        baseline.put("decimal_value", new BigDecimal("1.00"));
        baseline.put("integer_value", new BigInteger("123456789012345678901234567890"));
        baseline.put("nested", Map.of("decimal", new BigDecimal("1.00")));
        baseline.put("nullable", null);
        Map<String, Object> encoded = new LinkedHashMap<>(baseline);
        client.upsert(ADDRESS, KEY, encoded);
        Object actual = comparable.apply(captured.get());
        client.upsert(ADDRESS, KEY, baseline);
        assertEquals(actual, comparable.apply(captured.get()),
                "application-owned Map and literal Map must have the same native representation");
        assertEquals(baseline, encoded);
        assertEquals("business", baseline.get("partitionKey"));
    }

    private static void assertValidationBeforeNativeOperation(MulticloudDbClient client, Object nativeBoundary) {
        clearInvocations(nativeBoundary);
        Map<String, Object> oversized = Map.of("body", "x".repeat(400 * 1024));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDRESS, KEY, oversized)).error().category());
        verifyNoInteractions(nativeBoundary);
        for (Object unsupported : List.of(Double.NaN, new byte[]{1}, java.nio.ByteBuffer.wrap(new byte[]{1}),
                Set.of("value"), new Object(), new java.util.concurrent.atomic.AtomicInteger(1))) {
            MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                    () -> client.upsert(ADDRESS, KEY, Map.of("nested", List.of(unsupported))));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
            verifyNoInteractions(nativeBoundary);
        }
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThrows(MulticloudDbException.class, () -> client.create(ADDRESS, KEY, cycle));
        verifyNoInteractions(nativeBoundary);
    }

    private static ObjectMapper configuredMapper(AtomicInteger serialized, AtomicInteger deserialized) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate date, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                serialized.incrementAndGet();
                generator.writeString(date.toString().replace("-", "/"));
            }
        });
        module.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override public LocalDate deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                deserialized.incrementAndGet();
                return LocalDate.parse(parser.getText().replace("/", "-"));
            }
        });
        return new ObjectMapper().registerModule(module)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
