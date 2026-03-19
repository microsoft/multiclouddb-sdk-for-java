// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

import com.azure.cosmos.CosmosDiagnostics;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.FeedResponse;
import com.hyperscaledb.api.ResourceAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Centralised diagnostics logging for the Cosmos DB provider, following the
 * recommendations of the
 * <a href="https://learn.microsoft.com/en-us/azure/cosmos-db/troubleshoot-java-sdk-v4">
 * Azure Cosmos DB Java SDK v4 troubleshooting guide</a>.
 * <p>
 * Extracted from {@link CosmosProviderClient} as a package-private helper so
 * the three logging strategies can be unit-tested without a live Cosmos client.
 *
 * <h3>Logging strategy summary</h3>
 * <table border="1">
 * <tr><th>Condition</th><th>Level</th><th>Content</th></tr>
 * <tr><td>Every point op (create/read/update/upsert/delete)</td>
 *     <td>DEBUG</td><td>activityId, statusCode, ruCharge, latencyMs</td></tr>
 * <tr><td>Point op latency &gt; {@link CosmosConstants#DIAG_THRESHOLD_POINT_MS} ms
 *         OR ruCharge &gt; {@link CosmosConstants#DIAG_THRESHOLD_POINT_RU}</td>
 *     <td>WARN</td><td>+ full {@code CosmosDiagnostics.toString()}</td></tr>
 * <tr><td>Every query page</td>
 *     <td>DEBUG</td><td>ruCharge, itemCount, hasMore, latencyMs</td></tr>
 * <tr><td>Query latency &gt; {@link CosmosConstants#DIAG_THRESHOLD_QUERY_MS} ms
 *         OR ruCharge &gt; {@link CosmosConstants#DIAG_THRESHOLD_QUERY_RU}</td>
 *     <td>WARN</td><td>+ full {@code CosmosDiagnostics.toString()}</td></tr>
 * <tr><td>Query latency &gt; {@link CosmosConstants#DIAG_THRESHOLD_QUERY_ERROR_MS} ms</td>
 *     <td>ERROR</td><td>+ full {@code CosmosDiagnostics.toString()}</td></tr>
 * <tr><td>Every caught {@link CosmosException}</td>
 *     <td>ERROR</td><td>statusCode, subStatusCode, activityId, message,
 *                       full {@code CosmosDiagnostics.toString()}</td></tr>
 * </table>
 */
final class CosmosDiagnosticsLogger {

    static final Logger LOG = LoggerFactory.getLogger(CosmosProviderClient.class);

    private CosmosDiagnosticsLogger() {}

    // ── Point-operation diagnostics ───────────────────────────────────────────

    /**
     * Logs diagnostics from a {@link CosmosItemResponse}.
     * <p>
     * Always logs at DEBUG. Emits WARN with the full
     * {@link CosmosDiagnostics} string when the client-side latency exceeds
     * {@link CosmosConstants#DIAG_THRESHOLD_POINT_MS} or the RU charge exceeds
     * {@link CosmosConstants#DIAG_THRESHOLD_POINT_RU}.
     *
     * @param operation human-readable operation name (e.g. {@code "create"})
     * @param address   the target database + container
     * @param response  the Cosmos SDK item response
     */
    static void logItem(String operation, ResourceAddress address,
            CosmosItemResponse<?> response) {
        CosmosDiagnostics diag = response.getDiagnostics();
        double ruCharge = response.getRequestCharge();
        String activityId = response.getActivityId();
        int statusCode = response.getStatusCode();
        long latencyMs = durationMs(diag);

        LOG.debug("cosmos.op={} db={} col={} activityId={} statusCode={} ruCharge={} latencyMs={}",
                operation, address.database(), address.collection(),
                activityId, statusCode, ruCharge, latencyMs);

        if ((latencyMs > CosmosConstants.DIAG_THRESHOLD_POINT_MS
                || ruCharge > CosmosConstants.DIAG_THRESHOLD_POINT_RU)
                && diag != null) {
            LOG.warn("cosmos.slow op={} db={} col={} activityId={} latencyMs={} ruCharge={} diagnostics={}",
                    operation, address.database(), address.collection(),
                    activityId, latencyMs, ruCharge, diag);
        }
    }

    // ── Query/feed diagnostics ────────────────────────────────────────────────

    /**
     * Logs diagnostics from a {@link FeedResponse} (query page).
     * <p>
     * Always logs at DEBUG. Emits WARN when latency exceeds
     * {@link CosmosConstants#DIAG_THRESHOLD_QUERY_MS} or RU charge exceeds
     * {@link CosmosConstants#DIAG_THRESHOLD_QUERY_RU}. Emits ERROR when latency
     * exceeds {@link CosmosConstants#DIAG_THRESHOLD_QUERY_ERROR_MS}.
     *
     * @param operation human-readable operation name
     * @param address   the target database + container
     * @param page      the Cosmos SDK feed response
     * @param itemCount number of items in this page
     */
    static void logFeed(String operation, ResourceAddress address,
            FeedResponse<?> page, int itemCount) {
        CosmosDiagnostics diag = page.getCosmosDiagnostics();
        double ruCharge = page.getRequestCharge();
        long latencyMs = durationMs(diag);
        boolean hasMore = page.getContinuationToken() != null;

        LOG.debug("cosmos.op={} db={} col={} ruCharge={} itemCount={} hasMore={} latencyMs={}",
                operation, address.database(), address.collection(),
                ruCharge, itemCount, hasMore, latencyMs);

        if (latencyMs > CosmosConstants.DIAG_THRESHOLD_QUERY_ERROR_MS && diag != null) {
            LOG.error("cosmos.very-slow-query op={} db={} col={} latencyMs={} ruCharge={} itemCount={} diagnostics={}",
                    operation, address.database(), address.collection(),
                    latencyMs, ruCharge, itemCount, diag);
        } else if ((latencyMs > CosmosConstants.DIAG_THRESHOLD_QUERY_MS
                || ruCharge > CosmosConstants.DIAG_THRESHOLD_QUERY_RU)
                && diag != null) {
            LOG.warn("cosmos.slow-query op={} db={} col={} latencyMs={} ruCharge={} itemCount={} diagnostics={}",
                    operation, address.database(), address.collection(),
                    latencyMs, ruCharge, itemCount, diag);
        }
    }

    // ── Exception diagnostics ─────────────────────────────────────────────────

    /**
     * Logs diagnostics from a caught {@link CosmosException} at ERROR level.
     * <p>
     * Includes HTTP status, sub-status, activity ID, the exception message, and
     * the full {@link CosmosDiagnostics} string — the primary troubleshooting
     * artefact recommended by the SDK v4 guide.
     *
     * @param operation human-readable operation name
     * @param address   the target database + container
     * @param e         the caught exception
     */
    static void logException(String operation, ResourceAddress address,
            CosmosException e) {
        LOG.error("cosmos.error op={} db={} col={} statusCode={} subStatus={} activityId={} message={} diagnostics={}",
                operation,
                address.database(),
                address.collection(),
                e.getStatusCode(),
                e.getSubStatusCode(),
                e.getActivityId(),
                e.getMessage(),
                e.getDiagnostics());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Extracts the client-side duration in milliseconds from a
     * {@link CosmosDiagnostics} object, returning {@code -1} if the diagnostics
     * object is {@code null} or carries no duration.
     */
    static long durationMs(CosmosDiagnostics diag) {
        if (diag == null) return -1L;
        Duration d = diag.getDuration();
        return d != null ? d.toMillis() : -1L;
    }
}

