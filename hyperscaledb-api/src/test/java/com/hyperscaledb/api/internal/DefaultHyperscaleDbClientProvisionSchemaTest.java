package com.hyperscaledb.api.internal;

import com.hyperscaledb.api.*;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code provisionSchema} method of
 * {@link DefaultHyperscaleDbClient}, focusing on defensive
 * {@code CompletionException} unwrapping fixes from PR #24:
 * <ul>
 *   <li>While-loop peels all nested {@code CompletionException} layers</li>
 *   <li>{@code wrapUnexpected} receives the unwrapped cause, not the wrapper</li>
 * </ul>
 *
 * <p>Uses anonymous {@link HyperscaleDbProviderClient} stubs to avoid Mockito
 * limitations with interface mocking on newer JVMs.
 */
class DefaultHyperscaleDbClientProvisionSchemaTest {

    private static final HyperscaleDbClientConfig CONFIG = HyperscaleDbClientConfig.builder()
            .provider(ProviderId.COSMOS)
            .build();

    private static final CapabilitySet EMPTY_CAPS = new CapabilitySet(Collections.emptyList());

    // -----------------------------------------------------------------------
    // Stub factories
    // -----------------------------------------------------------------------

    /** Provider whose {@code provisionSchema} throws the given exception. */
    private static HyperscaleDbProviderClient providerThrowing(RuntimeException ex) {
        return new HyperscaleDbProviderClient() {
            @Override public void create(ResourceAddress a, HyperscaleDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public Map<String, Object> read(ResourceAddress a, HyperscaleDbKey k, OperationOptions o) { return null; }
            @Override public void update(ResourceAddress a, HyperscaleDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public void upsert(ResourceAddress a, HyperscaleDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public void delete(ResourceAddress a, HyperscaleDbKey k, OperationOptions o) {}
            @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
            @Override public CapabilitySet capabilities() { return EMPTY_CAPS; }
            @Override public ProviderId providerId() { return ProviderId.COSMOS; }
            @Override public void close() {}
            @Override public void provisionSchema(Map<String, java.util.List<String>> schema) { throw ex; }
        };
    }

    /** Provider whose {@code provisionSchema} completes normally. */
    private static HyperscaleDbProviderClient providerSucceeding() {
        return new HyperscaleDbProviderClient() {
            @Override public void create(ResourceAddress a, HyperscaleDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public Map<String, Object> read(ResourceAddress a, HyperscaleDbKey k, OperationOptions o) { return null; }
            @Override public void update(ResourceAddress a, HyperscaleDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public void upsert(ResourceAddress a, HyperscaleDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public void delete(ResourceAddress a, HyperscaleDbKey k, OperationOptions o) {}
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
    @DisplayName("Single CompletionException layer: HyperscaleDbException category preserved")
    void singleLayerCategoryPreserved() {
        HyperscaleDbError error = new HyperscaleDbError(
                HyperscaleDbErrorCategory.AUTHENTICATION_FAILED,
                "auth", null, "provisionSchema", false, Map.of());
        HyperscaleDbException original = new HyperscaleDbException(error);
        CompletionException wrapped = wrapLayers(original, 1);

        DefaultHyperscaleDbClient client =
                new DefaultHyperscaleDbClient(providerThrowing(wrapped), CONFIG);

        HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of("col1"))));

        assertEquals(HyperscaleDbErrorCategory.AUTHENTICATION_FAILED, ex.error().category());
    }

    @Test
    @DisplayName("Double-nested CompletionException: while-loop peels both layers")
    void doubleNestedCompletionExceptionUnwrapped() {
        HyperscaleDbError error = new HyperscaleDbError(
                HyperscaleDbErrorCategory.CONFLICT,
                "conflict", null, "provisionSchema", false, Map.of());
        HyperscaleDbException original = new HyperscaleDbException(error);
        CompletionException doubleWrapped = wrapLayers(original, 2);

        DefaultHyperscaleDbClient client =
                new DefaultHyperscaleDbClient(providerThrowing(doubleWrapped), CONFIG);

        HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of("col1"))));

        assertEquals(HyperscaleDbErrorCategory.CONFLICT, ex.error().category(),
                "While-loop must peel all CompletionException layers to reach HyperscaleDbException");
    }

    @Test
    @DisplayName("Triple-nested CompletionException: while-loop peels all three layers")
    void tripleNestedCompletionExceptionUnwrapped() {
        HyperscaleDbError error = new HyperscaleDbError(
                HyperscaleDbErrorCategory.THROTTLED,
                "throttled", null, "provisionSchema", true, Map.of());
        HyperscaleDbException original = new HyperscaleDbException(error);
        CompletionException tripleWrapped = wrapLayers(original, 3);

        DefaultHyperscaleDbClient client =
                new DefaultHyperscaleDbClient(providerThrowing(tripleWrapped), CONFIG);

        HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of())));

        assertEquals(HyperscaleDbErrorCategory.THROTTLED, ex.error().category());
    }

    @Test
    @DisplayName("Non-HyperscaleDb cause: wrapUnexpected receives unwrapped cause, not CompletionException wrapper")
    void wrapUnexpectedReceivesUnwrappedCause() {
        RuntimeException rootCause = new RuntimeException("real error");
        CompletionException wrapped = wrapLayers(rootCause, 1);

        DefaultHyperscaleDbClient client =
                new DefaultHyperscaleDbClient(providerThrowing(wrapped), CONFIG);

        HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of())));

        assertEquals(HyperscaleDbErrorCategory.PROVIDER_ERROR, ex.error().category());
        String exType = ex.error().providerDetails().get("exceptionType");
        assertEquals("java.lang.RuntimeException", exType,
                "exceptionType should reflect the unwrapped cause, not the CompletionException wrapper");
    }

    @Test
    @DisplayName("Double-nested non-HyperscaleDb cause: exceptionType reflects deepest cause")
    void doubleNestedNonHyperscaleDbExceptionType() {
        RuntimeException rootCause = new IllegalStateException("state error");
        CompletionException doubleWrapped = wrapLayers(rootCause, 2);

        DefaultHyperscaleDbClient client =
                new DefaultHyperscaleDbClient(providerThrowing(doubleWrapped), CONFIG);

        HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                () -> client.provisionSchema(Map.of("db1", List.of())));

        String exType = ex.error().providerDetails().get("exceptionType");
        assertEquals("java.lang.IllegalStateException", exType,
                "exceptionType must be the actual root cause class, not a CompletionException wrapper");
    }

    @Test
    @DisplayName("provisionSchema succeeds: no exception when provider does not throw")
    void successPath() {
        DefaultHyperscaleDbClient client =
                new DefaultHyperscaleDbClient(providerSucceeding(), CONFIG);
        assertDoesNotThrow(() -> client.provisionSchema(Map.of("db1", List.of("col1"))));
    }
}
