// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.cosmos.CosmosDiagnostics;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.FeedResponse;
import com.hyperscaledb.api.ResourceAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CosmosDiagnosticsLogger}.
 * <p>
 * Each test uses Logback's {@link ListAppender} to capture log events emitted
 * to {@code CosmosProviderClient}'s logger, and Mockito to stub the Cosmos SDK
 * response objects without a live Cosmos connection.
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li>{@code logItem} — DEBUG always; WARN on slow latency; WARN on high RU;
 *       no WARN when fast and low-RU</li>
 *   <li>{@code logFeed} — DEBUG always; WARN on slow latency; WARN on high RU;
 *       ERROR on very slow latency; no WARN when fast</li>
 *   <li>{@code logException} — always ERROR with all fields present</li>
 *   <li>{@code durationMs} — null-safe, returns -1 for null diagnostics/duration</li>
 *   <li>{@link CosmosConstants} — threshold value regression tests</li>
 * </ul>
 */
class CosmosDiagnosticsLogTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger cosmosLogger;

    private static final ResourceAddress ADDR = new ResourceAddress("testdb", "testcol");

    @BeforeEach
    void attachAppender() {
        cosmosLogger = (Logger) LoggerFactory.getLogger(CosmosProviderClient.class);
        cosmosLogger.setLevel(Level.DEBUG);   // ensure DEBUG events are captured
        appender = new ListAppender<>();
        appender.start();
        cosmosLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        cosmosLogger.detachAppender(appender);
        appender.stop();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<ILoggingEvent> logs() {
        return appender.list;
    }

    private boolean hasLevel(Level level) {
        return logs().stream().anyMatch(e -> e.getLevel() == level);
    }

    private boolean messageContains(Level level, String fragment) {
        return logs().stream()
                .filter(e -> e.getLevel() == level)
                .anyMatch(e -> e.getFormattedMessage().contains(fragment));
    }

    /** Build a mock CosmosItemResponse with given latency, RU, activityId, statusCode. */
    @SuppressWarnings("unchecked")
    private CosmosItemResponse<Object> mockItemResponse(long latencyMs, double ru,
            String activityId, int statusCode) {
        CosmosItemResponse<Object> resp = mock(CosmosItemResponse.class);
        CosmosDiagnostics diag = mock(CosmosDiagnostics.class);
        when(diag.getDuration()).thenReturn(Duration.ofMillis(latencyMs));
        when(diag.toString()).thenReturn("DIAG[latency=" + latencyMs + "ms]");
        when(resp.getDiagnostics()).thenReturn(diag);
        when(resp.getRequestCharge()).thenReturn(ru);
        when(resp.getActivityId()).thenReturn(activityId);
        when(resp.getStatusCode()).thenReturn(statusCode);
        return resp;
    }

    /** Build a mock FeedResponse with given latency, RU, itemCount, and continuation. */
    @SuppressWarnings("unchecked")
    private FeedResponse<Object> mockFeedResponse(long latencyMs, double ru,
            String continuationToken) {
        FeedResponse<Object> page = mock(FeedResponse.class);
        CosmosDiagnostics diag = mock(CosmosDiagnostics.class);
        when(diag.getDuration()).thenReturn(Duration.ofMillis(latencyMs));
        when(diag.toString()).thenReturn("FEEDDIAG[latency=" + latencyMs + "ms]");
        when(page.getCosmosDiagnostics()).thenReturn(diag);
        when(page.getRequestCharge()).thenReturn(ru);
        when(page.getContinuationToken()).thenReturn(continuationToken);
        return page;
    }

    /** Build a mock CosmosException with given status, sub-status, activityId. */
    private CosmosException mockException(int statusCode, int subStatus, String activityId) {
        CosmosException ex = mock(CosmosException.class);
        CosmosDiagnostics diag = mock(CosmosDiagnostics.class);
        when(diag.toString()).thenReturn("EXDIAG[status=" + statusCode + "]");
        when(ex.getStatusCode()).thenReturn(statusCode);
        when(ex.getSubStatusCode()).thenReturn(subStatus);
        when(ex.getActivityId()).thenReturn(activityId);
        when(ex.getMessage()).thenReturn("Simulated Cosmos error " + statusCode);
        when(ex.getDiagnostics()).thenReturn(diag);
        return ex;
    }

    // ── durationMs ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("durationMs returns -1 for null CosmosDiagnostics")
    void durationMs_nullDiagnostics() {
        assertEquals(-1L, CosmosDiagnosticsLogger.durationMs(null));
    }

    @Test
    @DisplayName("durationMs returns -1 when getDuration() is null")
    void durationMs_nullDuration() {
        CosmosDiagnostics diag = mock(CosmosDiagnostics.class);
        when(diag.getDuration()).thenReturn(null);
        assertEquals(-1L, CosmosDiagnosticsLogger.durationMs(diag));
    }

    @Test
    @DisplayName("durationMs returns correct millis from Duration")
    void durationMs_correctMillis() {
        CosmosDiagnostics diag = mock(CosmosDiagnostics.class);
        when(diag.getDuration()).thenReturn(Duration.ofMillis(42));
        assertEquals(42L, CosmosDiagnosticsLogger.durationMs(diag));
    }

    // ── logItem — fast, normal operation ─────────────────────────────────────

    @Test
    @DisplayName("logItem: always emits DEBUG with activityId and statusCode")
    void logItem_alwaysDebug() {
        var resp = mockItemResponse(1, 1.0, "act-1", 201);
        CosmosDiagnosticsLogger.logItem("create", ADDR, resp);

        assertTrue(hasLevel(Level.DEBUG), "Expected at least one DEBUG event");
        assertTrue(messageContains(Level.DEBUG, "act-1"), "DEBUG should include activityId");
        assertTrue(messageContains(Level.DEBUG, "201"), "DEBUG should include statusCode");
    }

    @Test
    @DisplayName("logItem: no WARN when latency and RU are both below threshold")
    void logItem_noWarnWhenFastAndLowRu() {
        // 1 ms latency, 1 RU — well below both thresholds
        var resp = mockItemResponse(1, 1.0, "act-fast", 200);
        CosmosDiagnosticsLogger.logItem("read", ADDR, resp);

        assertFalse(hasLevel(Level.WARN), "No WARN expected for fast, low-RU operation");
    }

    @Test
    @DisplayName("logItem: WARN emitted when latency exceeds DIAG_THRESHOLD_POINT_MS")
    void logItem_warnOnSlowLatency() {
        long slowMs = CosmosConstants.DIAG_THRESHOLD_POINT_MS + 1;
        var resp = mockItemResponse(slowMs, 1.0, "act-slow", 200);
        CosmosDiagnosticsLogger.logItem("read", ADDR, resp);

        assertTrue(hasLevel(Level.WARN), "Expected WARN for slow point read");
        assertTrue(messageContains(Level.WARN, "DIAG["),
                "WARN should include full CosmosDiagnostics string");
    }

    @Test
    @DisplayName("logItem: WARN emitted when RU exceeds DIAG_THRESHOLD_POINT_RU")
    void logItem_warnOnHighRu() {
        double highRu = CosmosConstants.DIAG_THRESHOLD_POINT_RU + 1;
        var resp = mockItemResponse(1, highRu, "act-ru", 200);
        CosmosDiagnosticsLogger.logItem("upsert", ADDR, resp);

        assertTrue(hasLevel(Level.WARN), "Expected WARN for high-RU point op");
        assertTrue(messageContains(Level.WARN, "DIAG["),
                "WARN should include full CosmosDiagnostics string");
    }

    @Test
    @DisplayName("logItem: WARN message contains operation name, db, and collection")
    void logItem_warnContainsOpAndAddress() {
        long slowMs = CosmosConstants.DIAG_THRESHOLD_POINT_MS + 5;
        var resp = mockItemResponse(slowMs, 1.0, "act-x", 200);
        CosmosDiagnosticsLogger.logItem("delete", ADDR, resp);

        assertTrue(messageContains(Level.WARN, "delete"), "WARN should contain operation name");
        assertTrue(messageContains(Level.WARN, "testdb"), "WARN should contain database name");
        assertTrue(messageContains(Level.WARN, "testcol"), "WARN should contain collection name");
    }

    // ── logFeed — query diagnostics ───────────────────────────────────────────

    @Test
    @DisplayName("logFeed: always emits DEBUG")
    void logFeed_alwaysDebug() {
        var page = mockFeedResponse(5, 2.0, null);
        CosmosDiagnosticsLogger.logFeed("query", ADDR, page, 10);

        assertTrue(hasLevel(Level.DEBUG), "Expected DEBUG for every query page");
    }

    @Test
    @DisplayName("logFeed: no WARN or ERROR when fast and low-RU")
    void logFeed_noWarnWhenFast() {
        var page = mockFeedResponse(5, 2.0, null);
        CosmosDiagnosticsLogger.logFeed("query", ADDR, page, 3);

        assertFalse(hasLevel(Level.WARN), "No WARN for fast, low-RU query");
        assertFalse(hasLevel(Level.ERROR), "No ERROR for fast query");
    }

    @Test
    @DisplayName("logFeed: WARN when latency exceeds DIAG_THRESHOLD_QUERY_MS")
    void logFeed_warnOnSlowQuery() {
        long slowMs = CosmosConstants.DIAG_THRESHOLD_QUERY_MS + 1;
        var page = mockFeedResponse(slowMs, 2.0, null);
        CosmosDiagnosticsLogger.logFeed("query", ADDR, page, 5);

        assertTrue(hasLevel(Level.WARN), "Expected WARN for slow query");
        assertFalse(hasLevel(Level.ERROR), "Should be WARN not ERROR at this latency");
        assertTrue(messageContains(Level.WARN, "FEEDDIAG["),
                "WARN should include full CosmosDiagnostics string");
    }

    @Test
    @DisplayName("logFeed: WARN when RU exceeds DIAG_THRESHOLD_QUERY_RU")
    void logFeed_warnOnHighRuQuery() {
        double highRu = CosmosConstants.DIAG_THRESHOLD_QUERY_RU + 1;
        var page = mockFeedResponse(5, highRu, null);
        CosmosDiagnosticsLogger.logFeed("query", ADDR, page, 50);

        assertTrue(hasLevel(Level.WARN), "Expected WARN for high-RU query page");
        assertFalse(hasLevel(Level.ERROR), "Should be WARN not ERROR for high RU alone");
    }

    @Test
    @DisplayName("logFeed: ERROR (not WARN) when latency exceeds DIAG_THRESHOLD_QUERY_ERROR_MS")
    void logFeed_errorOnVerySlowQuery() {
        long verySlowMs = CosmosConstants.DIAG_THRESHOLD_QUERY_ERROR_MS + 1;
        var page = mockFeedResponse(verySlowMs, 2.0, null);
        CosmosDiagnosticsLogger.logFeed("query", ADDR, page, 2);

        assertTrue(hasLevel(Level.ERROR), "Expected ERROR for very slow query");
        // The very-slow path should take the ERROR branch, not emit a separate WARN
        assertFalse(hasLevel(Level.WARN), "Should emit ERROR not WARN for very slow query");
        assertTrue(messageContains(Level.ERROR, "FEEDDIAG["),
                "ERROR should include full CosmosDiagnostics string");
    }

    @Test
    @DisplayName("logFeed: DEBUG message includes itemCount and hasMore")
    void logFeed_debugContainsItemCountAndHasMore() {
        var page = mockFeedResponse(5, 1.0, "token-abc");
        CosmosDiagnosticsLogger.logFeed("query", ADDR, page, 7);

        assertTrue(messageContains(Level.DEBUG, "7"), "DEBUG should mention itemCount");
        assertTrue(messageContains(Level.DEBUG, "true"), "DEBUG should show hasMore=true when token present");
    }

    // ── logException ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("logException: always emits ERROR")
    void logException_alwaysError() {
        var ex = mockException(429, 0, "act-throttle");
        CosmosDiagnosticsLogger.logException("upsert", ADDR, ex);

        assertTrue(hasLevel(Level.ERROR), "Expected ERROR for every caught CosmosException");
    }

    @Test
    @DisplayName("logException: ERROR message includes statusCode")
    void logException_includesStatusCode() {
        var ex = mockException(429, 0, "act-429");
        CosmosDiagnosticsLogger.logException("upsert", ADDR, ex);

        assertTrue(messageContains(Level.ERROR, "429"), "ERROR should include statusCode 429");
    }

    @Test
    @DisplayName("logException: ERROR message includes subStatusCode")
    void logException_includesSubStatusCode() {
        var ex = mockException(408, 20001, "act-timeout");
        CosmosDiagnosticsLogger.logException("read", ADDR, ex);

        assertTrue(messageContains(Level.ERROR, "20001"), "ERROR should include subStatus 20001");
    }

    @Test
    @DisplayName("logException: ERROR message includes activityId")
    void logException_includesActivityId() {
        var ex = mockException(503, 0, "act-service-unavailable");
        CosmosDiagnosticsLogger.logException("create", ADDR, ex);

        assertTrue(messageContains(Level.ERROR, "act-service-unavailable"),
                "ERROR should include activityId");
    }

    @Test
    @DisplayName("logException: ERROR message includes operation name, db, and collection")
    void logException_includesOpAndAddress() {
        var ex = mockException(500, 0, "act-err");
        CosmosDiagnosticsLogger.logException("delete", ADDR, ex);

        assertTrue(messageContains(Level.ERROR, "delete"), "ERROR should include operation name");
        assertTrue(messageContains(Level.ERROR, "testdb"), "ERROR should include database name");
        assertTrue(messageContains(Level.ERROR, "testcol"), "ERROR should include collection name");
    }

    @Test
    @DisplayName("logException: ERROR message includes full CosmosDiagnostics string")
    void logException_includesDiagnosticsString() {
        var ex = mockException(503, 0, "act-diag");
        CosmosDiagnosticsLogger.logException("query", ADDR, ex);

        assertTrue(messageContains(Level.ERROR, "EXDIAG["),
                "ERROR should include full CosmosDiagnostics toString()");
    }

    // ── CosmosConstants — threshold value regression ──────────────────────────

    @Test
    @DisplayName("DIAG_THRESHOLD_POINT_MS is 10 ms")
    void thresholdPointMs() {
        assertEquals(10L, CosmosConstants.DIAG_THRESHOLD_POINT_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_MS is 100 ms")
    void thresholdQueryMs() {
        assertEquals(100L, CosmosConstants.DIAG_THRESHOLD_QUERY_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_ERROR_MS is 1000 ms")
    void thresholdQueryErrorMs() {
        assertEquals(1000L, CosmosConstants.DIAG_THRESHOLD_QUERY_ERROR_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_POINT_RU is 10.0")
    void thresholdPointRu() {
        assertEquals(10.0, CosmosConstants.DIAG_THRESHOLD_POINT_RU);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_RU is 100.0")
    void thresholdQueryRu() {
        assertEquals(100.0, CosmosConstants.DIAG_THRESHOLD_QUERY_RU);
    }

    @Test
    @DisplayName("Point MS threshold is strictly less than query MS threshold")
    void pointThresholdLessThanQueryThreshold() {
        assertTrue(CosmosConstants.DIAG_THRESHOLD_POINT_MS
                        < CosmosConstants.DIAG_THRESHOLD_QUERY_MS,
                "Point-read threshold must be stricter (lower) than query threshold");
    }

    @Test
    @DisplayName("Query MS threshold is strictly less than query ERROR MS threshold")
    void queryWarnThresholdLessThanErrorThreshold() {
        assertTrue(CosmosConstants.DIAG_THRESHOLD_QUERY_MS
                        < CosmosConstants.DIAG_THRESHOLD_QUERY_ERROR_MS,
                "Query WARN threshold must be lower than query ERROR threshold");
    }
}

