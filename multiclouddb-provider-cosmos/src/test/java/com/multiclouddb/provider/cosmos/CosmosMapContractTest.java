// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.*;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.multiclouddb.api.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
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

/** Native boundary tests run before provider-local shading. */
class CosmosMapContractTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("database", "documents");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("tenant", "record");
    private static final TypeReference<Batch<Customer>> BATCH = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    record Customer(String displayName) {}

    // The application, not the adapter, chooses how provider root metadata is handled.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Batch<T>(String batchName, LocalDate createdOn, List<T> items, int quantity, double ratio) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<MulticloudDbKey> KEYS = List.of(
            MulticloudDbKey.of("tenant", "record"), MulticloudDbKey.of("tenant"));
    private static final List<OperationOptions> OPTIONS = List.of(
            OperationOptions.defaults(), OperationOptions.builder().ttlSeconds(120).build());

    @Test
    void cosmosCustomerMappingThroughActualClientAndProvider() throws Exception {
        CosmosClient nativeClient = mock(CosmosClient.class);
        CosmosDatabase database = mock(CosmosDatabase.class);
        CosmosContainer container = mock(CosmosContainer.class);
        @SuppressWarnings("unchecked")
        CosmosItemResponse<Map> response = mock(CosmosItemResponse.class);
        AtomicReference<Map<String, Object>> written = new AtomicReference<>();
        AtomicReference<Map<String, Object>> readFixture = new AtomicReference<>();
        when(nativeClient.getDatabase(ADDRESS.database())).thenReturn(database);
        when(database.getContainer(ADDRESS.collection())).thenReturn(container);
        when(container.createItem(any(Map.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> {
                    written.set(new LinkedHashMap<>(call.<Map<String, Object>>getArgument(0)));
                    assertEquals(new PartitionKey(KEY.partitionKey()), call.getArgument(1));
                    return response;
                });
        when(container.upsertItem(any(Map.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> { written.set(new LinkedHashMap<>(call.<Map<String, Object>>getArgument(0))); return response; });
        when(container.readItem(eq(KEY.sortKey()), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(Map.class))).thenReturn(response);
        when(response.getItem()).thenAnswer(call -> readFixture.get());

        try (MockedConstruction<CosmosClientBuilder> ignored = mockConstruction(CosmosClientBuilder.class,
                withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.buildClient()).thenReturn(nativeClient));
             MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                     .provider(ProviderId.COSMOS)
                     .connection(Map.of("endpoint", "https://example.documents.azure.com:443/", "key", "dGVzdA=="))
                     .build())) {
            exercise(client, written::get, readFixture::set, Function.identity());
            assertEquals(KEY.sortKey(), readFixture.get().get("id"));
            assertEquals(KEY.partitionKey(), readFixture.get().get("partitionKey"));
            verify(container, atLeast(2)).createItem(any(Map.class), any(PartitionKey.class),
                    any(CosmosItemRequestOptions.class));
            assertLiteralMapParity(client, written::get, Function.identity());
            assertEquals("1", written.get().get("decimal_value").toString(),
                    "the existing default native tree conversion strips decimal trailing zeros");
            assertEquals("123456789012345678901234567890", written.get().get("integer_value").toString());

            readFixture.get().put("_etag", "provider-value");
            assertFalse(client.read(ADDRESS, KEY).document().containsKey("_etag"));
            assertTrue(readFixture.get().containsKey("_etag"), "provider read must not mutate the fixture");
            for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                    Map.of("private-field", new Object()), Map.of("private-field", new byte[]{1, 2}))) {
                readFixture.set(invalid);
                assertReadPayloadFailure(client, ProviderId.COSMOS);
            }
            assertValidationBeforeNativeOperation(client, container);
        }
        verify(nativeClient).close();
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

    private static void assertReadPayloadFailure(MulticloudDbClient client, ProviderId provider) {
        MulticloudDbException failure = assertThrows(MulticloudDbException.class, () -> client.read(ADDRESS, KEY));
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, failure.error().category());
        assertEquals(provider, failure.error().provider());
        assertEquals("read", failure.error().operation());
        assertFalse(failure.error().retryable());
        assertEquals(Map.of("reason", "invalid_document_payload"), failure.error().providerDetails());
        assertEquals("Provider response cannot be represented as neutral data.", failure.error().message());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
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
        for (int i = 0; i < 127; i++) deep = List.of(deep);
        return List.of(Map.of(), fields, wide,
                Map.of("deep", deep), Map.of("large", "x".repeat(408500)));
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
    void cosmosAllWriteOperationsMatchLegacyNativeTreesAndReadFiltersBeforeValidation() throws Exception {
        CosmosClient sdk = mock(CosmosClient.class);
        CosmosDatabase database = mock(CosmosDatabase.class);
        CosmosContainer container = mock(CosmosContainer.class);
        @SuppressWarnings("unchecked")
        CosmosItemResponse<Map> response = mock(CosmosItemResponse.class);
        var captured = new AtomicReference<Map<String, Object>>();
        when(sdk.getDatabase(ADDRESS.database())).thenReturn(database);
        when(database.getContainer(ADDRESS.collection())).thenReturn(container);
        when(container.createItem(any(Map.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> { captured.set(call.getArgument(0)); return response; });
        when(container.replaceItem(any(Map.class), anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> { captured.set(call.getArgument(0)); return response; });
        when(container.upsertItem(any(Map.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> { captured.set(call.getArgument(0)); return response; });
        try (MockedConstruction<CosmosClientBuilder> ignored = mockConstruction(CosmosClientBuilder.class,
                withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.buildClient()).thenReturn(sdk));
             MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                     .provider(ProviderId.COSMOS)
                     .connection(Map.of("endpoint", "https://example.documents.azure.com:443/", "key", "dGVzdA=="))
                     .build())) {
            for (Map<String, Object> input : fixtures()) {
                Map<String, Object> before = new LinkedHashMap<>(input);
                for (var key : KEYS) for (var options : OPTIONS) {
                    ObjectNode expected = MAPPER.convertValue(before, ObjectNode.class);
                    expected.put("id", key.sortKey() == null ? key.partitionKey() : key.sortKey());
                    expected.put("partitionKey", key.partitionKey());
                    if (options.ttlSeconds() != null) expected.put("ttl", options.ttlSeconds());
                    for (String op : List.of("create", "update", "upsert")) {
                        clearInvocations(container);
                        write(client, op, key, input, options);
                        assertNativeTree(expected, MAPPER.valueToTree(captured.get()));
                        assertEquals(before, new LinkedHashMap<>(input));
                        assertEquals(1, mockingDetails(container).getInvocations().size());
                        var invocation = mockingDetails(container).getInvocations().iterator().next();
                        assertEquals(switch (op) {
                            case "create" -> "createItem";
                            case "update" -> "replaceItem";
                            default -> "upsertItem";
                        }, invocation.getMethod().getName());
                        assertEquals(new PartitionKey(key.partitionKey()),
                                invocation.getArgument(op.equals("update") ? 2 : 1));
                        if (op.equals("update")) assertEquals(expected.get("id").textValue(), invocation.getArgument(1));
                    }
                }
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            // These invalid model values must be skipped before conversion, not copied or validated.
            CosmosConstants.SYSTEM_FIELDS.forEach(name -> raw.put(name, new byte[]{1, 2}));
            raw.put("_ts", 123L);
            raw.put("nested", Map.of("_etag", "user-field"));
            when(container.readItem(anyString(), any(PartitionKey.class),
                    any(CosmosItemRequestOptions.class), eq(Map.class))).thenReturn(response);
            when(response.getItem()).thenReturn(raw);
            when(response.getETag()).thenReturn("version");
            ObjectNode before = MAPPER.valueToTree(raw);
            DocumentResult result = client.read(ADDRESS, KEYS.get(0),
                    OperationOptions.builder().includeMetadata(true).build());
            assertEquals(Map.of("nested", Map.of("_etag", "user-field")), result.document());
            assertEquals(Instant.ofEpochSecond(123), result.metadata().lastModified());
            assertEquals("version", result.metadata().version());
            assertNativeTree(before, MAPPER.valueToTree(raw));
        }
    }

    private static void assertNativeTree(JsonNode expected, JsonNode actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected, actual);
        if (expected.isNumber()) {
            assertEquals(expected.numberValue().getClass(), actual.numberValue().getClass());
            assertEquals(expected.numberValue().toString(), actual.numberValue().toString());
        } else if (expected.isObject()) {
            assertEquals(toNames(expected), toNames(actual));
            expected.fields().forEachRemaining(field -> assertNativeTree(field.getValue(), actual.get(field.getKey())));
        } else if (expected.isArray()) {
            for (int i = 0; i < expected.size(); i++) assertNativeTree(expected.get(i), actual.get(i));
        }
    }

    private static List<String> toNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static final class PlainCustomerMapping {
        public record Customer(String name, int quantity) {}

        public Map<String, Object> toMap(Customer customer) {
            return Map.of("customer_name", customer.name(), "quantity", customer.quantity());
        }

        public Customer fromMap(Map<String, Object> document) {
            if (!(document.get("customer_name") instanceof String name)
                    || !(document.get("quantity") instanceof Number quantity)) {
                throw new IllegalArgumentException("Invalid customer fields.");
            }
            return new Customer(name, new BigDecimal(quantity.toString()).intValueExact());
        }
    }
}
