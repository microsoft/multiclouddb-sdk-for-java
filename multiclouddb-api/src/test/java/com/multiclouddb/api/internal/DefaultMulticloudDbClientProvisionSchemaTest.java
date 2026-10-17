// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.*;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code provisionSchema} method of
 * {@link DefaultMulticloudDbClient}, focusing on defensive
 * {@code CompletionException} unwrapping fixes from PR #24:
 * <ul>
 *   <li>While-loop peels all nested {@code CompletionException} layers</li>
 *   <li>{@code wrapUnexpected} receives the unwrapped cause, not the wrapper</li>
 * </ul>
 *
 * <p>Uses anonymous {@link MulticloudDbProviderClient} stubs to avoid Mockito
 * limitations with interface mocking on newer JVMs.
 */
class DefaultMulticloudDbClientProvisionSchemaTest {

    private static final MulticloudDbClientConfig CONFIG = MulticloudDbClientConfig.builder()
            .provider(ProviderId.COSMOS)
            .build();

    private static final CapabilitySet EMPTY_CAPS = new CapabilitySet(Collections.emptyList());

    // -----------------------------------------------------------------------
    // Stub factories
    // -----------------------------------------------------------------------

    /** Provider whose {@code provisionSchema} throws the given exception. */
    private static MulticloudDbProviderClient providerThrowing(RuntimeException ex) {
        return new MulticloudDbProviderClient() {
            @Override public void create(ResourceAddress a, MulticloudDbKey k, com.multiclouddb.api.document.Document d, OperationOptions o) {}
            @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { return null; }
            @Override public void update(ResourceAddress a, MulticloudDbKey k, com.multiclouddb.api.document.Document d, OperationOptions o) {}
            @Override public void upsert(ResourceAddress a, MulticloudDbKey k, com.multiclouddb.api.document.Document d, OperationOptions o) {}
            @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) {}
            @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
            @Override public CapabilitySet capabilities() { return EMPTY_CAPS; }
            @Override public ProviderId providerId() { return ProviderId.COSMOS; }
            @Override public void close() {}
            @Override public void provisionSchema(Map<String, java.util.List<String>> schema) { throw ex; }
        };
    }

    /** Provider whose {@code provisionSchema} completes normally. */
    private static MulticloudDbProviderClient providerSucceeding() {
        return new MulticloudDbProviderClient() {
            @Override public void create(ResourceAddress a, MulticloudDbKey k, com.multiclouddb.api.document.Document d, OperationOptions o) {}
            @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { return null; }
            @Override public void update(ResourceAddress a, MulticloudDbKey k, com.multiclouddb.api.document.Document d, OperationOptions o) {}
            @Override public void upsert(ResourceAddress a, MulticloudDbKey k, com.multiclouddb.api.document.Document d, OperationOptions o) {}
            @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) {}
            @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
            @Override public CapabilitySet capabilities() { return EMPTY_CAPS; }
            @Override public ProviderId providerId() { return ProviderId.COSMOS; }
            @Override public void close() {}
        };
    }

    /** Wraps {@code inner} in {@code depth} levels of {@link CompletionException}. */
    private static CompletionException wrapLayers(Throwable inner, int depth) {
        Throwable current = inner;
        for (int i = 0; i < depth; i++) {
            current = new CompletionException(current);
        }
        return (CompletionException) current;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Single CompletionException layer: MulticloudDbException category preserved")
    void singleLayerCategoryPreserved() {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.AUTHENTICATION_FAILED,
                "auth", null, "provisionSchema", false, Map.of());
        MulticloudDbException original = new MulticloudDbException(error);
        CompletionException wrapped = wrapLayers(original, 1);

        DefaultMulticloudDbClient client =
                new DefaultMulticloudDbClient(providerThrowing(wrapped), CONFIG);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of("col1"))));

        assertEquals(MulticloudDbErrorCategory.AUTHENTICATION_FAILED, ex.error().category());
    }

    @Test
    @DisplayName("Double-nested CompletionException: while-loop peels both layers")
    void doubleNestedCompletionExceptionUnwrapped() {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.CONFLICT,
                "conflict", null, "provisionSchema", false, Map.of());
        MulticloudDbException original = new MulticloudDbException(error);
        CompletionException doubleWrapped = wrapLayers(original, 2);

        DefaultMulticloudDbClient client =
                new DefaultMulticloudDbClient(providerThrowing(doubleWrapped), CONFIG);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of("col1"))));

        assertEquals(MulticloudDbErrorCategory.CONFLICT, ex.error().category(),
                "While-loop must peel all CompletionException layers to reach MulticloudDbException");
    }

    @Test
    @DisplayName("Triple-nested CompletionException: while-loop peels all three layers")
    void tripleNestedCompletionExceptionUnwrapped() {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.THROTTLED,
                "throttled", null, "provisionSchema", true, Map.of());
        MulticloudDbException original = new MulticloudDbException(error);
        CompletionException tripleWrapped = wrapLayers(original, 3);

        DefaultMulticloudDbClient client =
                new DefaultMulticloudDbClient(providerThrowing(tripleWrapped), CONFIG);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of())));

        assertEquals(MulticloudDbErrorCategory.THROTTLED, ex.error().category());
    }

    @Test
    @DisplayName("Non-MulticloudDb cause: wrapUnexpected receives unwrapped cause, not CompletionException wrapper")
    void wrapUnexpectedReceivesUnwrappedCause() {
        RuntimeException rootCause = new RuntimeException("real error");
        CompletionException wrapped = wrapLayers(rootCause, 1);

        DefaultMulticloudDbClient client =
                new DefaultMulticloudDbClient(providerThrowing(wrapped), CONFIG);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of())));

        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, ex.error().category());
        String exType = ex.error().providerDetails().get("exceptionType");
        assertEquals("java.lang.RuntimeException", exType,
                "exceptionType should reflect the unwrapped cause, not the CompletionException wrapper");
    }

    @Test
    @DisplayName("Double-nested non-MulticloudDb cause: exceptionType reflects deepest cause")
    void doubleNestedNonMulticloudDbExceptionType() {
        RuntimeException rootCause = new IllegalStateException("state error");
        CompletionException doubleWrapped = wrapLayers(rootCause, 2);

        DefaultMulticloudDbClient client =
                new DefaultMulticloudDbClient(providerThrowing(doubleWrapped), CONFIG);

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of())));

        String exType = ex.error().providerDetails().get("exceptionType");
        assertEquals("java.lang.IllegalStateException", exType,
                "exceptionType must be the actual root cause class, not a CompletionException wrapper");
    }

    @Test
    @DisplayName("provisionSchema succeeds: no exception when provider does not throw")
    void successPath() {
        DefaultMulticloudDbClient client =
                new DefaultMulticloudDbClient(providerSucceeding(), CONFIG);
        assertDoesNotThrow(() -> client.provisionSchema(Map.of("db1", List.of("col1"))));
    }
}
