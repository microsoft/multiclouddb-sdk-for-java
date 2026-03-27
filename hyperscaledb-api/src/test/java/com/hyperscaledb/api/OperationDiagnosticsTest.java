// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OperationDiagnosticsTest {

    @Test
    void builder_requiredFields_areMandatory() {
        assertThrows(NullPointerException.class,
                () -> OperationDiagnostics.builder(null, "read", Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> OperationDiagnostics.builder(ProviderId.COSMOS, null, Duration.ZERO));
        assertThrows(NullPointerException.class,
                () -> OperationDiagnostics.builder(ProviderId.COSMOS, "read", null));
    }

    @Test
    void builder_optionalFields_defaultToNullOrZero() {
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.COSMOS, OperationNames.READ, Duration.ofMillis(50))
                .build();

        assertEquals(ProviderId.COSMOS, diag.provider());
        assertEquals(OperationNames.READ, diag.operation());
        assertEquals(Duration.ofMillis(50), diag.duration());
        assertNull(diag.requestId());
        assertNull(diag.statusCode());
        assertEquals(0.0, diag.requestCharge(), 1e-9);
        assertNull(diag.etag());
        assertNull(diag.sessionToken());
        assertEquals(0, diag.itemCount());
    }

    @Test
    void builder_allFields_arePopulated() {
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.DYNAMO, OperationNames.QUERY, Duration.ofMillis(120))
                .requestId("req-123")
                .statusCode(200)
                .requestCharge(4.5)
                .etag("etag-abc")
                .sessionToken("session-xyz")
                .itemCount(10)
                .build();

        assertEquals(ProviderId.DYNAMO, diag.provider());
        assertEquals(OperationNames.QUERY, diag.operation());
        assertEquals(Duration.ofMillis(120), diag.duration());
        assertEquals("req-123", diag.requestId());
        assertEquals(Integer.valueOf(200), diag.statusCode());
        assertEquals(4.5, diag.requestCharge(), 1e-9);
        assertEquals("etag-abc", diag.etag());
        assertEquals("session-xyz", diag.sessionToken());
        assertEquals(10, diag.itemCount());
    }

    @Test
    void queryPage_withDiagnostics_isReturned() {
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.COSMOS, OperationNames.QUERY, Duration.ofMillis(30))
                .requestCharge(2.0)
                .itemCount(3)
                .build();

        QueryPage page = new QueryPage(null, null, diag);

        assertNotNull(page.diagnostics());
        assertEquals(2.0, page.diagnostics().requestCharge(), 1e-9);
        assertEquals(3, page.diagnostics().itemCount());
    }

    @Test
    void queryPage_withoutDiagnostics_returnsNull() {
        QueryPage page = new QueryPage(null, null);
        assertNull(page.diagnostics());
        assertFalse(page.hasMore());
    }

    @Test
    void queryPage_hasMore_isTrueWhenTokenPresent() {
        QueryPage page = new QueryPage(null, "token-abc");
        assertTrue(page.hasMore());
        assertEquals("token-abc", page.continuationToken());
    }

    @Test
    void queryPage_emptyToken_normalisedToNull() {
        QueryPage page = new QueryPage(null, "");
        assertFalse(page.hasMore());
        assertNull(page.continuationToken());
    }
}
