// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Locks in the post-close contract for {@link DynamoProviderClient}: every
 * public entry point gated by {@code checkOpen()} must throw a typed
 * {@link MulticloudDbException} with category
 * {@link MulticloudDbErrorCategory#CLIENT_CLOSED}, rather than leaking the
 * raw {@code IllegalStateException} that the AWS SDK surfaces once
 * {@code DynamoDbClient.close()} is invoked.
 *
 * <p>Uses the package-private test constructor that accepts a mock
 * {@link DynamoDbClient}, so the test never contacts a real DynamoDB account
 * (or DynamoDB Local). Each test calls {@link DynamoProviderClient#close()}
 * on a fresh client (the underlying {@link DynamoDbClient} is a mock, so
 * {@code close()} is a fast no-op) and asserts the entry point under test
 * throws {@code CLIENT_CLOSED} attributed to the caller's operation name.
 *
 * <p>This complements the conformance-level assertion in
 * {@code CrudConformanceTests.postCloseOperationsThrowClientClosed()} which
 * exercises real provider integrations.
 */
class DynamoPostCloseTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "table");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private DynamoDbClient mockDynamoClient;
    private DynamoProviderClient client;

    @BeforeEach
    void setUp() {
        mockDynamoClient = mock(DynamoDbClient.class);
        client = new DynamoProviderClient(mockDynamoClient);
        client.close();
    }

    private static void assertClientClosed(MulticloudDbException e, String expectedOperation) {
        assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, e.error().category(),
                "every post-close entry point must surface CLIENT_CLOSED, not "
                        + e.error().category());
        assertEquals(ProviderId.DYNAMO, e.error().provider());
        // The operation field must carry the caller's actual operation, not the
        // generic "checkOpen" literal — telemetry/diagnostics consumers branch
        // on this to attribute post-close failures to the failing call.
        assertEquals(expectedOperation, e.error().operation(),
                "post-close error must attribute operation to the caller's op, not 'checkOpen'");
    }

    @Test
    @DisplayName("create() after close() throws CLIENT_CLOSED")
    void createAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.create(ADDR, KEY, Map.of("k", "v"), null)),
                OperationNames.CREATE);
    }

    @Test
    @DisplayName("read() after close() throws CLIENT_CLOSED")
    void readAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.read(ADDR, KEY, null)),
                OperationNames.READ);
    }

    @Test
    @DisplayName("update() after close() throws CLIENT_CLOSED")
    void updateAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.update(ADDR, KEY, Map.of("k", "v"), null)),
                OperationNames.UPDATE);
    }

    @Test
    @DisplayName("upsert() after close() throws CLIENT_CLOSED")
    void upsertAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDR, KEY, Map.of("k", "v"), null)),
                OperationNames.UPSERT);
    }

    @Test
    @DisplayName("delete() after close() throws CLIENT_CLOSED")
    void deleteAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.delete(ADDR, KEY, null)),
                OperationNames.DELETE);
    }

    @Test
    @DisplayName("query() after close() throws CLIENT_CLOSED")
    void queryAfterClose() {
        QueryRequest q = QueryRequest.builder().build();
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.query(ADDR, q, null)),
                OperationNames.QUERY);
    }

    @Test
    @DisplayName("queryWithTranslation() after close() throws CLIENT_CLOSED")
    void queryWithTranslationAfterClose() {
        QueryRequest q = QueryRequest.builder().build();
        TranslatedQuery translated = TranslatedQuery.withNamedParameters(
                "SELECT * FROM dummy", "1=1", Map.of());
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.queryWithTranslation(ADDR, translated, q, null)),
                OperationNames.QUERY_WITH_TRANSLATION);
    }

    @Test
    @DisplayName("ensureDatabase() after close() throws CLIENT_CLOSED")
    void ensureDatabaseAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.ensureDatabase("test-db")),
                OperationNames.ENSURE_DATABASE);
    }

    @Test
    @DisplayName("ensureContainer() after close() throws CLIENT_CLOSED")
    void ensureContainerAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.ensureContainer(ADDR)),
                OperationNames.ENSURE_CONTAINER);
    }

    @Test
    @DisplayName("close() is idempotent — dynamoClient.close() invoked exactly once")
    void closeIsIdempotent() {
        DynamoDbClient localMock = mock(DynamoDbClient.class);
        DynamoProviderClient local = new DynamoProviderClient(localMock);
        local.close();
        local.close();
        // Second close must be a no-op; the underlying AWS SDK client's
        // close() must NOT be invoked twice (would leak NPE / IllegalStateException
        // from a doubly-closed SDK client).
        verify(localMock, times(1)).close();
    }
}
