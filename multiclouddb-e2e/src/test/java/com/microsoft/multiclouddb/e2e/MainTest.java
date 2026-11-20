// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.e2e;

import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationOutcome;
import com.multiclouddb.api.OperationResponse;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("test", "products");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("prod-003", "prod-003");
    private final MulticloudDbClient client = mock(MulticloudDbClient.class);
    private final Main main = new Main(client, ADDRESS);

    MainTest() {
        when(client.providerId()).thenReturn(ProviderId.DYNAMO);
    }

    @Test
    void deletionWaitsForStaleReadToBecomeNotFound() {
        when(client.read(ADDRESS, KEY)).thenReturn(response(true, OperationOutcome.SUCCESS))
                .thenReturn(response(false, OperationOutcome.NOT_FOUND));

        main.awaitDeletion("prod-003", Duration.ofSeconds(10));

        verify(client, times(2)).read(ADDRESS, KEY);
    }

    @Test
    void deletionFailsWhenVisibilityBudgetIsExhausted() {
        when(client.read(ADDRESS, KEY)).thenReturn(response(true, OperationOutcome.SUCCESS));

        var error = assertThrows(AssertionError.class,
                () -> main.awaitDeletion("prod-003", Duration.ZERO));

        assertTrue(error.getMessage().contains("remained visible"));
        verify(client).read(ADDRESS, KEY);
    }

    @Test
    void deletionStillChecksNotFoundDiagnostics() {
        when(client.read(ADDRESS, KEY)).thenReturn(response(false, OperationOutcome.SUCCESS));

        var error = assertThrows(AssertionError.class,
                () -> main.awaitDeletion("prod-003", Duration.ofSeconds(10)));

        assertTrue(error.getMessage().contains("completed common diagnostics"));
        verify(client).read(ADDRESS, KEY);
    }

    @Test
    void deletionDoesNotRetryReadFailures() {
        var failure = new IllegalStateException("read failed");
        when(client.read(ADDRESS, KEY)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> main.awaitDeletion("prod-003", Duration.ofSeconds(10))));
        verify(client).read(ADDRESS, KEY);
    }

    @Test
    void deletionPreservesInterruptAndStopsWaiting() {
        when(client.read(ADDRESS, KEY)).thenReturn(response(true, OperationOutcome.SUCCESS));
        Thread.currentThread().interrupt();
        try {
            var error = assertThrows(AssertionError.class,
                    () -> main.awaitDeletion("prod-003", Duration.ofSeconds(10)));
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(client).read(ADDRESS, KEY);
        } finally {
            Thread.interrupted();
        }
    }

    private static OperationResponse<DocumentResult> response(boolean found, OperationOutcome outcome) {
        Instant now = Instant.now();
        var diagnostics = OperationDiagnostics.builder(ProviderId.DYNAMO, "read")
                .startTime(now).endTime(now).duration(Duration.ZERO).outcome(outcome).build();
        return new OperationResponse<>(found ? new DocumentResult(Map.of("id", "prod-003")) : null, diagnostics);
    }
}
