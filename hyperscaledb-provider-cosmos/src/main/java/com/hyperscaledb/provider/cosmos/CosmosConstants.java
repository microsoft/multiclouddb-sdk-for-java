// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

import com.azure.cosmos.ConsistencyLevel;
import java.util.Set;

/**
 * All hardcoded string keys, values, and numeric constants used by the
 * Cosmos DB provider in one place.
 * <p>
 * Using this class avoids magic strings scattered across the implementation
 * and makes every tunable value easy to find and change.
 */
public final class CosmosConstants {

    private CosmosConstants() {}

    // ── Config property keys (match properties file / System property names) ─

    /** Connection config key for the Cosmos DB endpoint URL. */
    public static final String CONFIG_ENDPOINT = "endpoint";

    /** Connection config key for the Cosmos DB master key (optional — omit for Entra ID). */
    public static final String CONFIG_KEY = "key";

    /** Connection config key for the Azure tenant ID (optional — used with DefaultAzureCredential). */
    public static final String CONFIG_TENANT_ID = "tenantId";


    /** Connection config key for the connection mode ({@code direct} or {@code gateway}). */
    public static final String CONFIG_CONNECTION_MODE = "connectionMode";

    // ── Connection mode values ────────────────────────────────────────────────

    /** Gateway connection mode — recommended for emulator and restricted networks. */
    public static final String CONNECTION_MODE_GATEWAY = "gateway";

    /** Direct connection mode — lower latency, recommended for production. */
    public static final String CONNECTION_MODE_DIRECT = "direct";

    /** Default connection mode applied when {@code connectionMode} is not configured. */
    public static final String CONNECTION_MODE_DEFAULT = CONNECTION_MODE_GATEWAY;

    // ── Consistency ───────────────────────────────────────────────────────────

    /**
     * Default consistency level.
     * SESSION guarantees read-your-own-writes within a session and is the
     * recommended default for most application workloads.
     */
    public static final ConsistencyLevel CONSISTENCY_LEVEL_DEFAULT = ConsistencyLevel.SESSION;

    // ── Document field names ──────────────────────────────────────────────────

    /** The {@code id} field required by every Cosmos DB document. */
    public static final String FIELD_ID = "id";

    /** The partition key field name used in every document and container definition. */
    public static final String FIELD_PARTITION_KEY = "partitionKey";

    /**
     * Cosmos DB document-level TTL field. When set to a positive integer, Cosmos DB deletes
     * the document after that many seconds, provided the container has TTL enabled
     * (container default TTL must be set to {@code -1} or a positive value in the portal/ARM).
     * Setting to {@code -1} disables TTL on a per-document basis.
     * <p>
     * Note: this is {@code "ttl"} (no underscore), not {@code "_ttl"}.
     * {@code _ts}, {@code _etag}, {@code _rid} use underscores because they are
     * read-only system properties; {@code ttl} is user-settable and has no underscore prefix.
     */
    public static final String FIELD_TTL = "ttl";

    // ── Partition key ─────────────────────────────────────────────────────────

    /** JSON path of the partition key field in every Cosmos container. */
    public static final String PARTITION_KEY_PATH = "/" + FIELD_PARTITION_KEY;

    // ── Paging ────────────────────────────────────────────────────────────────

    /** Default page size used when the caller does not specify one. */
    public static final int PAGE_SIZE_DEFAULT = 100;

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Fallback SQL expression returned when no filter is specified. */
    public static final String QUERY_SELECT_ALL = "SELECT * FROM c";

    /** Prefix for named query parameters in Cosmos SQL. */
    public static final String QUERY_PARAM_PREFIX = "@";

    // ── System-property field names (read-only, injected by Cosmos DB) ────────

    /** Unix epoch timestamp of the last write ({@code _ts}). Used to populate {@code lastModified}. */
    public static final String SYS_TIMESTAMP = "_ts";
    /** ETag / session token ({@code _etag}). Used to populate {@code version}. */
    public static final String SYS_ETAG = "_etag";
    /** Resource ID ({@code _rid}). */
    public static final String SYS_RID = "_rid";
    /** Self-link ({@code _self}). */
    public static final String SYS_SELF = "_self";
    /** Attachments link ({@code _attachments}). */
    public static final String SYS_ATTACHMENTS = "_attachments";

    /** All system-property field names that must be stripped before returning a portable document. */
    public static final Set<String> SYSTEM_FIELDS = Set.of(
            SYS_TIMESTAMP, SYS_ETAG, SYS_RID, SYS_SELF, SYS_ATTACHMENTS,
            FIELD_ID, FIELD_PARTITION_KEY);

    // ── Error / validation messages ───────────────────────────────────────────

    /** Error message thrown when the endpoint config key is missing or blank. */
    public static final String ERR_ENDPOINT_REQUIRED = "Cosmos connection.endpoint is required";

    // ── Diagnostics thresholds (ms) ───────────────────────────────────────────
    // Mirrors the thresholds recommended by the Azure Cosmos DB Java SDK v4
    // troubleshooting guide:
    // https://learn.microsoft.com/en-us/azure/cosmos-db/troubleshoot-java-sdk-v4

    /**
     * WARN threshold for point-read / write latency (ms).
     * Operations slower than this emit a WARN log with full diagnostics.
     *
     * <p>Set to 100 ms to account for typical client-to-Azure network latency
     * (~30–60 ms round-trip from outside the datacenter). The Cosmos DB SLA for
     * point reads within the same region is ~10 ms, but that only applies to
     * co-located compute (e.g., Azure VMs in the same region). Callers running
     * from outside Azure will always exceed 10 ms; 100 ms provides a meaningful
     * signal for genuine slowness (throttling, large documents, retry storms)
     * without flooding logs with false positives.
     */
    public static final long DIAG_THRESHOLD_POINT_MS = 100L;

    /**
     * WARN threshold for query latency (ms).
     * Single-partition queries slower than this emit a WARN log with full diagnostics.
     */
    public static final long DIAG_THRESHOLD_QUERY_MS = 500L;

    /**
     * ERROR threshold for query latency (ms).
     * Queries slower than this emit an ERROR log — indicative of cross-partition
     * fan-out, missing index, or throttling.
     */
    public static final long DIAG_THRESHOLD_QUERY_ERROR_MS = 1000L;

    /**
     * Maximum RU charge for a single point-read/write before a WARN is emitted.
     *
     * <p>Typical RU costs for a small document (~300 bytes, default indexing):
     * <ul>
     *   <li>Point-read: ~1 RU</li>
     *   <li>Create (upsert, new): ~14–15 RU</li>
     *   <li>Replace (upsert, existing): ~22–25 RU (read + write + index rebuild)</li>
     * </ul>
     * Set to 30 RU to allow normal create and replace operations through while
     * still flagging genuinely expensive writes caused by large documents or
     * overly broad indexing policies.
     */
    public static final double DIAG_THRESHOLD_POINT_RU = 30.0;

    /**
     * Maximum RU charge for a single query page before a WARN is emitted.
     */
    public static final double DIAG_THRESHOLD_QUERY_RU = 100.0;
}

