// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.*;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultMulticloudDbClientDiagnosticsTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("key");
    private static final OperationOptions OPTIONS = OperationOptions.defaults();

    private static DefaultMulticloudDbClient client(MulticloudDbProviderClient provider, ProviderId id) {
        return new DefaultMulticloudDbClient(provider, MulticloudDbClientConfig.builder().provider(id).build());
    }

    @Test
    void readMissHasDiagnosticsAndElapsedTimeSurvivesBackwardWallClock() {
        var provider = mock(MulticloudDbProviderClient.class);
        var now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        var nanos = new AtomicLong(100);
        var clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        var nativeMetadata = OperationDiagnostics.builder(ProviderId.DYNAMO, "native-read")
                .requestId("request").statusCode(200).statusCodeDomain(StatusCodeDomain.HTTP)
                .consumption(new ResourceConsumption(0, ResourceConsumption.Unit.DYNAMODB_CAPACITY_UNIT,
                        ResourceConsumption.Coverage.UNKNOWN)).build();
        when(provider.read(ADDRESS, KEY, OPTIONS)).thenAnswer(call -> {
            now.set(now.get().minusSeconds(10));
            nanos.addAndGet(25_000_000);
            return new OperationResponse<DocumentResult>(null, nativeMetadata);
        });
        var client = new DefaultMulticloudDbClient(provider,
                MulticloudDbClientConfig.builder().provider(ProviderId.DYNAMO).build(), clock, nanos::get);
        var response = client.read(ADDRESS, KEY, OPTIONS);
        assertNull(response.result());
        var diag = response.diagnostics();
        assertEquals(OperationOutcome.NOT_FOUND, diag.outcome());
        assertEquals(Duration.ofMillis(25), diag.duration());
        assertTrue(diag.endTime().isBefore(diag.startTime()));
        assertEquals("read", diag.operation());
        assertEquals("request", diag.requestId());
        assertEquals(200, diag.statusCode());
        assertEquals(StatusCodeDomain.HTTP, diag.statusCodeDomain());
        assertNotNull(diag.consumption());
        assertEquals(0, diag.consumption().amount());
        assertNull(nativeMetadata.startTime(), "provider snapshot must not be mutated");
        verify(provider).read(ADDRESS, KEY, OPTIONS);
        verifyNoMoreInteractions(provider);
    }

    @Test
    void emptyDocumentIsPresentAndAllWritesReturnSuccessfulNoBodyResponses() {
        var provider = mock(MulticloudDbProviderClient.class);
        var diag = OperationDiagnostics.builder(ProviderId.SPANNER, "point").build();
        var empty = new DocumentResult(Map.of(), null);
        when(provider.read(ADDRESS, KEY, OPTIONS)).thenReturn(new OperationResponse<>(empty, diag));
        when(provider.create(ADDRESS, KEY, Map.of(), OPTIONS)).thenReturn(new OperationResponse<>(null, diag));
        when(provider.update(ADDRESS, KEY, Map.of(), OPTIONS)).thenReturn(new OperationResponse<>(null, diag));
        when(provider.upsert(ADDRESS, KEY, Map.of(), OPTIONS)).thenReturn(new OperationResponse<>(null, diag));
        when(provider.delete(ADDRESS, KEY, OPTIONS)).thenReturn(new OperationResponse<>(null, diag));
        var client = client(provider, ProviderId.SPANNER);
        var read = client.read(ADDRESS, KEY, OPTIONS);
        assertSame(empty, read.result());
        assertEquals(OperationOutcome.SUCCESS, read.diagnostics().outcome());
        for (var response : List.of(client.create(ADDRESS, KEY, Map.of(), OPTIONS),
                client.update(ADDRESS, KEY, Map.of(), OPTIONS), client.upsert(ADDRESS, KEY, Map.of(), OPTIONS),
                client.delete(ADDRESS, KEY, OPTIONS))) {
            assertNull(response.result());
            assertEquals(OperationOutcome.SUCCESS, response.diagnostics().outcome());
            assertNotNull(response.diagnostics().startTime());
            assertNotNull(response.diagnostics().endTime());
            assertNull(response.diagnostics().consumption());
            assertNull(response.diagnostics().statusCode());
        }
    }

    @Test
    void typedFailureRetainsIdentityMetadataAndSuppressedFailures() {
        var provider = mock(MulticloudDbProviderClient.class);
        var cause = new IllegalStateException("native");
        var failure = new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.TRANSIENT_FAILURE, "unavailable", ProviderId.SPANNER,
                "read", true, 14, Map.of()), cause);
        failure.addSuppressed(new IllegalStateException("another task"));
        failure.withDiagnostics(OperationDiagnostics.builder(ProviderId.SPANNER, "native-read")
                .requestId("observed-id").build());
        when(provider.read(ADDRESS, KEY, OPTIONS)).thenThrow(failure);
        var thrown = assertThrows(MulticloudDbException.class,
                () -> client(provider, ProviderId.SPANNER).read(ADDRESS, KEY, OPTIONS));
        assertSame(failure, thrown);
        assertSame(cause, thrown.getCause());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals(14, thrown.diagnostics().statusCode());
        assertEquals(StatusCodeDomain.GRPC, thrown.diagnostics().statusCodeDomain());
        assertEquals("observed-id", thrown.diagnostics().requestId());
        assertEquals(OperationOutcome.FAILURE, thrown.diagnostics().outcome());
    }

    @Test
    void failureFallbackDoesNotReintroduceUnavailableRequestIds() {
        for (String id : List.of("", " ", "UNKNOWN")) {
            var provider = mock(MulticloudDbProviderClient.class);
            var failure = new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.TRANSIENT_FAILURE, "unavailable", ProviderId.DYNAMO,
                    "read", true, 503, Map.of("requestId", id)))
                    .withDiagnostics(OperationDiagnostics.builder(ProviderId.DYNAMO, "read").build());
            when(provider.read(ADDRESS, KEY, OPTIONS)).thenThrow(failure);
            var thrown = assertThrows(MulticloudDbException.class,
                    () -> client(provider, ProviderId.DYNAMO).read(ADDRESS, KEY, OPTIONS));
            assertSame(failure, thrown);
            assertEquals(id, thrown.error().providerDetails().get("requestId"), "raw error details stay intact");
            assertNull(thrown.diagnostics().requestId());
        }
    }

    @Test
    void guardsAreEnrichedWithoutWrappingOrdinaryValidationErrors() throws Exception {
        var provider = mock(MulticloudDbProviderClient.class);
        when(provider.capabilities()).thenReturn(new CapabilitySet(List.of()));
        var client = client(provider, ProviderId.COSMOS);
        assertThrows(NullPointerException.class, () -> client.listCursors(null));
        var capability = assertThrows(MulticloudDbException.class, () -> client.listCursors(ADDRESS));
        assertEquals(OperationOutcome.FAILURE, capability.diagnostics().outcome());
        assertEquals(OperationNames.LIST_CURSORS, capability.diagnostics().operation());
        client.close();
        var closed = assertThrows(MulticloudDbException.class, () -> client.listCursors(null));
        assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, closed.error().category());
        assertNotNull(closed.diagnostics().startTime());
    }

    @Test
    void customProviderStatusesArePreservedWithoutInventingTheirDomain() {
        var id = ProviderId.fromId("diagnostics-custom");
        var provider = mock(MulticloudDbProviderClient.class);
        var metadata = OperationDiagnostics.builder(id, "read").statusCode(0).build();
        var failure = new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR, "custom error", id, "read", false, 7, Map.of()));
        when(provider.read(ADDRESS, KEY, OPTIONS))
                .thenReturn(new OperationResponse<>(new DocumentResult(Map.of()), metadata)).thenThrow(failure);
        var client = client(provider, id);
        var success = client.read(ADDRESS, KEY, OPTIONS).diagnostics();
        assertEquals(0, success.statusCode());
        assertNull(success.statusCodeDomain());
        var thrown = assertThrows(MulticloudDbException.class, () -> client.read(ADDRESS, KEY, OPTIONS));
        assertSame(failure, thrown);
        assertEquals(7, thrown.diagnostics().statusCode());
        assertNull(thrown.diagnostics().statusCodeDomain());
    }

    @Test
    void cursorFailureKeepsItsSubtypeAndConcurrentReadsKeepTheirOwnMetadata() {
        var provider = mock(MulticloudDbProviderClient.class);
        when(provider.capabilities()).thenReturn(new CapabilitySet(List.of(Capability.CHANGE_FEED_CAP)));
        var expired = new CursorExpiredException(new MulticloudDbError(
                MulticloudDbErrorCategory.CURSOR_EXPIRED, "expired", ProviderId.DYNAMO,
                OperationNames.READ_CHANGES, false, Map.of("reason", "PROVIDER_TRIMMED")));
        when(provider.readChanges(any(), any(), any())).thenThrow(expired);
        var client = client(provider, ProviderId.DYNAMO);
        assertSame(expired, assertThrows(CursorExpiredException.class,
                () -> client.readChanges(ADDRESS, ChangeFeedCursor.now())));
        assertEquals(OperationOutcome.FAILURE, expired.diagnostics().outcome());
        when(provider.read(eq(ADDRESS), any(), eq(OPTIONS))).thenAnswer(call -> {
            MulticloudDbKey key = call.getArgument(1);
            return new OperationResponse<DocumentResult>(null,
                    OperationDiagnostics.builder(ProviderId.DYNAMO, "read").requestId(key.partitionKey()).build());
        });
        var responses = java.util.stream.IntStream.range(0, 16).parallel()
                .mapToObj(i -> client.read(ADDRESS, MulticloudDbKey.of("key-" + i), OPTIONS)).toList();
        for (int i = 0; i < responses.size(); i++) {
            assertEquals("key-" + i, responses.get(i).diagnostics().requestId());
            assertEquals(OperationOutcome.NOT_FOUND, responses.get(i).diagnostics().outcome());
        }
    }

    @Test
    void queryParseFailureReceivesDiagnosticsWithoutExecutingProviderQuery() {
        var provider = mock(MulticloudDbProviderClient.class);
        when(provider.capabilities()).thenReturn(new CapabilitySet(List.of(Capability.PORTABLE_QUERY_EXPRESSION_CAP)));
        var client = client(provider, ProviderId.COSMOS);
        client.setExpressionTranslator(mock(ExpressionTranslator.class));
        var failure = assertThrows(MulticloudDbException.class,
                () -> client.query(ADDRESS, QueryRequest.builder().expression("price = (").build(), OPTIONS));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, failure.error().category());
        assertEquals(OperationOutcome.FAILURE, failure.diagnostics().outcome());
        verify(provider, never()).query(any(), any(), any());
        verify(provider, never()).queryWithTranslation(any(), any(), any(), any());
    }

    @Test
    void pagesAndManagementReceiveOuterSummariesWithoutChangingPayloadsOrCalls() {
        var provider = mock(MulticloudDbProviderClient.class);
        when(provider.capabilities()).thenReturn(new CapabilitySet(List.of(Capability.CHANGE_FEED_CAP)));
        var nested = new ArrayList<>(List.of("value"));
        var metadata = OperationDiagnostics.builder(ProviderId.COSMOS, "native-query", Duration.ofDays(1))
                .requestCharge(2.5).requestId("query-id").build();
        var query = QueryRequest.builder().build();
        var nativePage = new QueryPage(List.of(Map.of("nested", nested)), "token", metadata);
        when(provider.query(ADDRESS, query, OPTIONS)).thenReturn(nativePage);
        var cursor = ChangeFeedCursor.now();
        when(provider.readChanges(ADDRESS, cursor, OPTIONS))
                .thenReturn(new ChangeFeedPage(List.of(), cursor, false, true));
        when(provider.listCursors(ADDRESS)).thenReturn(List.of(cursor));
        var client = client(provider, ProviderId.COSMOS);
        var page = client.query(ADDRESS, query, OPTIONS);
        assertSame(nativePage.items(), page.items(), "Attaching diagnostics must not copy materialized rows");
        assertSame(metadata, nativePage.diagnostics(), "The provider page must remain unchanged");
        assertSame(nested, page.items().get(0).get("nested"));
        assertEquals("token", page.continuationToken());
        assertEquals(2.5, page.diagnostics().requestCharge());
        assertEquals("query-id", page.diagnostics().requestId());
        assertNull(page.diagnostics().consumption(), "legacy numeric charge does not prove availability");
        assertEquals("query", page.diagnostics().operation());
        assertEquals(1, page.diagnostics().itemCount());
        var feed = client.readChanges(ADDRESS, cursor, OPTIONS);
        assertSame(cursor, feed.nextCursor());
        assertTrue(feed.isTerminal());
        assertFalse(feed.hasMore());
        assertNotNull(feed.diagnostics());
        assertEquals(List.of(cursor), client.listCursors(ADDRESS).result());
        for (var response : List.of(client.ensureDatabase("db"), client.ensureContainer(ADDRESS),
                client.provisionSchema(Map.of()))) {
            assertEquals(OperationOutcome.SUCCESS, response.diagnostics().outcome());
            assertNull(response.diagnostics().consumption());
            assertNull(response.diagnostics().statusCode());
        }
        verify(provider).ensureDatabase("db");
        verify(provider).ensureContainer(ADDRESS);
        verify(provider).provisionSchema(Map.of());
        verify(provider).query(ADDRESS, query, OPTIONS);
        verify(provider).readChanges(ADDRESS, cursor, OPTIONS);
        verify(provider).listCursors(ADDRESS);
        verify(provider, times(2)).capabilities();
        verifyNoMoreInteractions(provider);
    }
}
