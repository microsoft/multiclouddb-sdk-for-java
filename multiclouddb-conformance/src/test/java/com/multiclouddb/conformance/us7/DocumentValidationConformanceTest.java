// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.conformance.us7;

import com.multiclouddb.api.*;
import com.multiclouddb.api.internal.DefaultMulticloudDbClient;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Common write validation with explicitly mocked SPI boundaries; no native connections. */
class DocumentValidationConformanceTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("database", "documents");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("tenant", "id");
    private static final OperationOptions OPTIONS = OperationOptions.defaults();

    @Test
    void numericPolicyIsIdenticalAcrossProvidersWritesAndNesting() throws Exception {
        List<Number> allowed = List.of(Integer.MIN_VALUE, Integer.MAX_VALUE,
                -9_007_199_254_740_991L, 9_007_199_254_740_991L,
                0.1D, 0.0D, -0.0D, 1E-130, -1E-130, 9_007_199_254_740_991D);
        List<Number> rejected = List.of((byte) 1, (short) 1, 0.1F,
                java.math.BigInteger.ONE, java.math.BigDecimal.ONE,
                new java.util.concurrent.atomic.AtomicInteger(1),
                new java.util.concurrent.atomic.AtomicLong(1),
                -9_007_199_254_740_992L, 9_007_199_254_740_992L,
                Math.nextDown(1E-130), -Math.nextDown(1E-130),
                Math.nextUp(9_007_199_254_740_991D), -Math.nextUp(9_007_199_254_740_991D),
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (ProviderId provider : List.of(ProviderId.COSMOS, ProviderId.DYNAMO, ProviderId.SPANNER)) {
            var boundary = mock(MulticloudDbProviderClient.class);
            try (var client = new DefaultMulticloudDbClient(boundary,
                    MulticloudDbClientConfig.builder().provider(provider).build())) {
                for (String operation : List.of("create", "update", "upsert")) {
                    for (boolean valid : List.of(true, false)) {
                        for (Number number : valid ? allowed : rejected) {
                            for (Object value : List.of(number, Map.of("n", number), List.of(number))) {
                                Map<String, Object> input = Map.of("private-field", value);
                                clearInvocations(boundary);
                                if (valid) {
                                    write(client, operation, input);
                                    verifyWrite(boundary, operation, input);
                                } else {
                                    var failure = assertThrows(MulticloudDbException.class,
                                            () -> write(client, operation, input));
                                    assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
                                    assertEquals(operation, failure.error().operation());
                                    assertFalse(failure.error().retryable());
                                    assertFalse(failure.getMessage().contains("private-field"));
                                    verifyNoInteractions(boundary);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void existingReadAndQueryValuesAreNotSubjectToWriteBounds() throws Exception {
        for (ProviderId provider : List.of(ProviderId.COSMOS, ProviderId.DYNAMO, ProviderId.SPANNER)) {
            var boundary = mock(MulticloudDbProviderClient.class);
            try (var client = new DefaultMulticloudDbClient(boundary,
                    MulticloudDbClientConfig.builder().provider(provider).build())) {
                Map<String, Object> legacy = Map.of("n", 9_007_199_254_740_992L);
                var result = new DocumentResult(legacy, null);
                when(boundary.read(eq(ADDRESS), eq(KEY), any())).thenReturn(result);
                assertEquals(legacy, client.read(ADDRESS, KEY).document());
                var request = QueryRequest.builder().partitionKey(KEY.partitionKey())
                        .expression("n = :n").parameters(Map.of(":n", java.math.BigDecimal.ONE)).build();
                when(boundary.query(eq(ADDRESS), same(request), same(OPTIONS)))
                        .thenReturn(new QueryPage(List.of(legacy), null));
                assertEquals(List.of(legacy), client.query(ADDRESS, request, OPTIONS).items());
                verify(boundary).query(eq(ADDRESS), same(request), same(OPTIONS));
                clearInvocations(boundary);
                var failure = assertThrows(MulticloudDbException.class,
                        () -> client.upsert(ADDRESS, KEY, legacy, OPTIONS));
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
                verifyNoInteractions(boundary);
            }
        }
    }

    @Test
    void depthAndNativeSizeDelegationAreConsistentAcrossProvidersAndWrites() throws Exception {
        for (ProviderId provider : List.of(ProviderId.COSMOS, ProviderId.DYNAMO, ProviderId.SPANNER)) {
            var boundary = mock(MulticloudDbProviderClient.class);
            try (var client = new DefaultMulticloudDbClient(boundary,
                    MulticloudDbClientConfig.builder().provider(provider).build())) {
                for (String operation : List.of("create", "update", "upsert")) {
                    for (int depth : new int[]{0, 31, 32, 33}) {
                        Object nested = "leaf";
                        for (int i = 0; i < depth; i++) nested = List.of(nested);
                        Map<String, Object> input = Map.of("field", nested);
                        clearInvocations(boundary);
                        if (depth == 33) {
                            var failure = assertThrows(MulticloudDbException.class,
                                    () -> write(client, operation, input));
                            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
                            assertEquals(operation, failure.error().operation());
                            assertFalse(failure.error().retryable());
                            verifyNoInteractions(boundary);
                        } else {
                            write(client, operation, input);
                            verifyWrite(boundary, operation, input);
                        }
                    }
                    for (int logicalBytes : new int[]{408575, 408576, 408577, 2_000_000}) {
                        var input = Map.<String, Object>of("body", "x".repeat(logicalBytes - 11));
                        clearInvocations(boundary);
                        write(client, operation, input);
                        verifyWrite(boundary, operation, input);
                    }
                }
            }
        }
    }

    @Test
    void closedClientStillPrecedesInputValidation() throws Exception {
        var boundary = mock(MulticloudDbProviderClient.class);
        var client = new DefaultMulticloudDbClient(boundary,
                MulticloudDbClientConfig.builder().provider(ProviderId.DYNAMO).build());
        client.close();
        clearInvocations(boundary);
        for (String operation : List.of("create", "update", "upsert")) {
            var failure = assertThrows(MulticloudDbException.class, () -> write(client, operation, null));
            assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, failure.error().category());
            assertEquals(operation, failure.error().operation());
        }
        verifyNoInteractions(boundary);
    }

    private static void write(MulticloudDbClient client, String operation, Map<String, Object> input) {
        switch (operation) {
            case "create" -> client.create(ADDRESS, KEY, input, OPTIONS);
            case "update" -> client.update(ADDRESS, KEY, input, OPTIONS);
            case "upsert" -> client.upsert(ADDRESS, KEY, input, OPTIONS);
            default -> throw new AssertionError(operation);
        }
    }

    private static void verifyWrite(MulticloudDbProviderClient boundary, String operation, Map<String, Object> input) {
        switch (operation) {
            case "create" -> verify(boundary).create(eq(ADDRESS), eq(KEY), same(input), same(OPTIONS));
            case "update" -> verify(boundary).update(eq(ADDRESS), eq(KEY), same(input), same(OPTIONS));
            case "upsert" -> verify(boundary).upsert(eq(ADDRESS), eq(KEY), same(input), same(OPTIONS));
            default -> throw new AssertionError(operation);
        }
        verifyNoMoreInteractions(boundary);
    }
}
