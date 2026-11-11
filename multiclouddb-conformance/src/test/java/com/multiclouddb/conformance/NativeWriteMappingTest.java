// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spanner.*;
import com.multiclouddb.api.*;
import com.multiclouddb.provider.spanner.SpannerConstants;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy conversion oracles live only in tests; native SDK calls are mocked. */
class NativeWriteMappingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    void spannerAllWriteOperationsMatchLegacyBindingsAndMetadataTransactions() throws Exception {
        SpannerOptions.Builder builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        SpannerOptions options = mock(SpannerOptions.class);
        Spanner spanner = mock(Spanner.class);
        DatabaseClient sdk = mock(DatabaseClient.class);
        TransactionRunner runner = mock(TransactionRunner.class);
        TransactionContext txn = mock(TransactionContext.class);
        var prior = new AtomicReference<Struct>();
        var captured = new AtomicReference<Mutation>();
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(spanner);
        when(spanner.getDatabaseClient(any(DatabaseId.class))).thenReturn(sdk);
        when(sdk.readWriteTransaction()).thenReturn(runner);
        when(txn.readRow(anyString(), any(Key.class), any())).thenAnswer(call -> prior.get());
        when(runner.run(any())).thenAnswer(call -> {
            TransactionRunner.TransactionCallable<?> body = call.getArgument(0);
            return body.run(txn);
        });
        doAnswer(call -> { captured.set(call.getArgument(0)); return null; }).when(txn).buffer(any(Mutation.class));
        when(sdk.write(any())).thenAnswer(call -> {
            Iterable<Mutation> mutations = call.getArgument(0);
            var list = new ArrayList<Mutation>();
            mutations.forEach(list::add);
            assertEquals(1, list.size());
            captured.set(list.get(0));
            return com.google.cloud.Timestamp.ofTimeSecondsAndNanos(0, 0);
        });
        try (MockedStatic<SpannerOptions> factories = mockStatic(SpannerOptions.class)) {
            factories.when(SpannerOptions::newBuilder).thenReturn(builder);
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.SPANNER)
                    .connection(Map.of("projectId", "project", "instanceId", "instance", "databaseId", "database"))
                    .build())) {
                verify(builder, never()).setInterceptorProvider(any());
                for (Map<String, Object> input : fixtures()) {
                    var before = new LinkedHashMap<>(input);
                    for (var key : KEYS) for (var operationOptions : OPTIONS) {
                        for (String op : List.of("create", "update", "upsert")) {
                            for (String metadata : Arrays.asList("[\"prior\",\"n0\"]", "[]", "malformed",
                                    "[\"prior\"," + "1".repeat(1001) + "]", "", "  ", "null", null)) {
                                clearInvocations(sdk, runner, txn);
                                prior.set(Struct.newBuilder().set("data").to(metadata).build());
                                write(client, op, key, input, operationOptions);
                                Mutation expected = legacyMutation(before, key, op, metadata);
                                assertEquals(expected.getOperation(), captured.get().getOperation());
                                assertEquals(expected.getColumns(), captured.get().getColumns());
                                assertEquals(expected.asMap(), captured.get().asMap());
                                if (input.containsKey("deep")) {
                                    String encoded = captured.get().asMap().get("deep").getString();
                                    assertTrue(encoded.startsWith(SpannerConstants.JSON_VALUE_MARKER));
                                    var parsed = MAPPER.readTree(encoded.substring(SpannerConstants.JSON_VALUE_MARKER.length()));
                                    assertEquals(MAPPER.valueToTree(input.get("deep")), parsed);
                                    assertTrue(jsonDepth(parsed) == 31 || jsonDepth(parsed) == 32);
                                }
                                assertEquals(before, new LinkedHashMap<>(input));
                                if (op.equals("update")) {
                                    verify(sdk).readWriteTransaction();
                                    verify(runner).run(any());
                                    verify(txn).readRow(ADDRESS.collection(),
                                            Key.of(key.partitionKey(), key.sortKey() == null ? key.partitionKey() : key.sortKey()),
                                            List.of("data"));
                                    verify(txn).buffer(any(Mutation.class));
                                } else {
                                    verify(sdk).write(any());
                                }
                                verifyNoMoreInteractions(sdk, runner, txn);
                            }
                        }
                    }
                }
                clearInvocations(sdk, runner, txn);
                prior.set(Struct.newBuilder().set("data").to("null").build());
                assertDoesNotThrow(() -> client.update(ADDRESS, KEYS.get(0), Map.of("newField", "new")));
                Mutation update = captured.get();
                assertEquals(Mutation.Op.UPDATE, update.getOperation());
                assertEquals(Set.of("partitionKey", "sortKey", "newField"), update.asMap().keySet(),
                        "JSON null metadata must not stamp FIELD_DATA or overwrite omitted columns");
                var stored = new LinkedHashMap<String, Value>();
                stored.put("data", Value.string("null"));
                stored.put("oldField", Value.string("preserved"));
                stored.put("oldNull", Value.string(null));
                stored.putAll(update.asMap());
                Struct.Builder rowBuilder = Struct.newBuilder();
                stored.forEach((name, value) -> rowBuilder.set(name).to(value));
                Struct row = rowBuilder.build();
                ReadContext read = mock(ReadContext.class);
                when(sdk.singleUse()).thenReturn(read);
                when(read.executeQuery(any(Statement.class)))
                        .thenAnswer(call -> ResultSets.forRows(row.getType(), List.of(row)));
                Map<String, Object> restored = client.read(ADDRESS, KEYS.get(0)).document();
                assertEquals("preserved", restored.get("oldField"));
                assertEquals("new", restored.get("newField"));
                assertTrue(restored.containsKey("oldNull"));
                assertNull(restored.get("oldNull"));
                assertFalse(restored.containsKey("data"));
                clearInvocations(sdk, runner, txn);
                prior.set(null);
                assertEquals(MulticloudDbErrorCategory.NOT_FOUND, assertThrows(MulticloudDbException.class,
                        () -> client.update(ADDRESS, KEYS.get(0), Map.of())).error().category());
                verify(txn, never()).buffer(any(Mutation.class));
                for (String op : List.of("create", "update", "upsert")) {
                    clearInvocations(sdk, runner, txn);
                    assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, assertThrows(MulticloudDbException.class,
                            () -> write(client, op, KEYS.get(0), Map.of("DaTa", 1),
                                    OperationOptions.defaults())).error().category());
                    verifyNoInteractions(sdk, runner, txn);
                }
            }
        }
    }

    private static int jsonDepth(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isContainerNode()) return 0;
        int maximum = 0;
        for (var child : value) maximum = Math.max(maximum, jsonDepth(child));
        return 1 + maximum;
    }

    private static Mutation legacyMutation(Map<String, Object> input, MulticloudDbKey key,
                                           String operation, String metadata) throws Exception {
        Mutation.WriteBuilder mutation = switch (operation) {
            case "create" -> Mutation.newInsertBuilder(ADDRESS.collection());
            case "update" -> Mutation.newUpdateBuilder(ADDRESS.collection());
            default -> Mutation.newInsertOrUpdateBuilder(ADDRESS.collection());
        };
        mutation.set("partitionKey").to(key.partitionKey());
        mutation.set("sortKey").to(key.sortKey() == null ? key.partitionKey() : key.sortKey());
        var fields = new LinkedHashSet<String>();
        boolean populatedPrior = "[\"prior\",\"n0\"]".equals(metadata);
        boolean validPrior = populatedPrior || "[]".equals(metadata);
        if (operation.equals("update") && populatedPrior) fields.addAll(List.of("prior", "n0"));
        for (var entry : input.entrySet()) {
            String column = entry.getKey();
            if (column.equals("partitionKey") || column.equals("sortKey")) continue;
            Object value = entry.getValue();
            if (value == null) mutation.set(column).to((String) null);
            else if (value instanceof String s) mutation.set(column).to(
                    !s.isEmpty() && s.charAt(0) == '\u0001' ? '\u0001' + s : s);
            else if (value instanceof Long l) mutation.set(column).to(l);
            else if (value instanceof Integer i) mutation.set(column).to((long) i);
            else if (value instanceof Boolean b) mutation.set(column).to(b);
            else if (value instanceof Double d) mutation.set(column).to(d);
            else if (value instanceof Float f) mutation.set(column).to((double) f);
            else if (value instanceof Map<?, ?> || value instanceof Collection<?>)
                mutation.set(column).to(SpannerConstants.JSON_VALUE_MARKER + MAPPER.writeValueAsString(value));
            else mutation.set(column).to(value.toString());
            fields.add(column);
        }
        if (!operation.equals("update") || validPrior) mutation.set("data").to(MAPPER.writeValueAsString(fields));
        return mutation.build();
    }
}
