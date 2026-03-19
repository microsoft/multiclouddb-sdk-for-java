// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.CapabilitySet;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.OperationOptions;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.hyperscaledb.api.query.TranslatedQuery;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Google Cloud Spanner provider client implementing CRUD + query operations.
 * <p>
 * Connection config keys:
 * <ul>
 * <li>{@code projectId} - GCP project ID</li>
 * <li>{@code instanceId} - Spanner instance ID</li>
 * <li>{@code databaseId} - Spanner database ID</li>
 * <li>{@code emulatorHost} - Optional emulator host (e.g.,
 * "localhost:9010")</li>
 * </ul>
 * <p>
 * Table conventions:
 * <ul>
 * <li>Primary key columns:
 * {@code partitionKey STRING(MAX), sortKey STRING(MAX)}</li>
 * <li>Document fields are stored as individual columns (STRING, INT64, BOOL,
 * FLOAT64)</li>
 * </ul>
 */
public class SpannerProviderClient implements HyperscaleDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerProviderClient.class);

    private final Spanner spanner;
    private final DatabaseClient databaseClient;
    private final HyperscaleDbClientConfig config;
    private final String projectId;
    private final String instanceId;
    private final String databaseId;

    /**
     * Constructs a Cloud Spanner provider client from the supplied configuration.
     * <p>
     * If {@code connection.emulatorHost} is set (e.g. {@code localhost:9010}), the
     * Spanner emulator is targeted instead of the live Cloud Spanner service.
     * Application Default Credentials are used when connecting to the live service;
     * no explicit credential config is needed when running on GCP with a service account.
     *
     * @param config client configuration carrying connection, auth, options, and
     *               feature flags
     * @throws IllegalArgumentException if {@code connection.instanceId} or
     *         {@code connection.databaseId} is missing or blank
     */
    public SpannerProviderClient(HyperscaleDbClientConfig config) {
        this.config = config;
        this.projectId = config.connection().getOrDefault("projectId", "test-project");
        this.instanceId = config.connection().get("instanceId");
        this.databaseId = config.connection().get("databaseId");
        String emulatorHost = config.connection().get("emulatorHost");

        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("Spanner connection.instanceId is required");
        }
        if (databaseId == null || databaseId.isBlank()) {
            throw new IllegalArgumentException("Spanner connection.databaseId is required");
        }

        SpannerOptions.Builder builder = SpannerOptions.newBuilder()
                .setProjectId(projectId);

        if (emulatorHost != null && !emulatorHost.isBlank()) {
            builder.setEmulatorHost(emulatorHost);
        }

        this.spanner = builder.build().getService();
        this.databaseClient = spanner.getDatabaseClient(
                DatabaseId.of(projectId, instanceId, databaseId));
        LOG.info("Spanner client created for project={}, instance={}, database={}, emulator={}",
                projectId, instanceId, databaseId, emulatorHost != null ? emulatorHost : "none");
    }

    /**
     * Inserts a new row into the Spanner table that corresponds to
     * {@code address.collection()}.
     * <p>
     * Uses a Spanner {@code INSERT} mutation. Two primary key columns are always
     * written first:
     * <ul>
     *   <li>{@code partitionKey} — set to {@code key.partitionKey()}.</li>
     *   <li>{@code sortKey} — set to {@code key.sortKey()} if present, otherwise
     *       {@code key.partitionKey()}.</li>
     * </ul>
     * All remaining document fields are written as individual columns via
     * {@link #writeMutationFields}. If the row already exists, the mutation fails
     * with {@link com.hyperscaledb.api.HyperscaleDbErrorCategory#CONFLICT}.
     *
     * @param address  the logical database + collection; the collection maps directly to
     *                 a Spanner table name
     * @param key      the document key
     * @param document the document payload; map entries become column values
     * @param options  operation options (currently unused by this provider)
     * @throws com.hyperscaledb.api.HyperscaleDbException on any Spanner error
     */
    @Override
    public void create(ResourceAddress address, com.hyperscaledb.api.Key key, Map<String, Object> document, OperationOptions options) {
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newInsertBuilder(table)
                    .set("partitionKey").to(key.partitionKey())
                    .set("sortKey").to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeMutationFields(mutation, document);
            databaseClient.write(List.of(mutation.build()));
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "create");
        }
    }

    /**
     * Replaces an existing row in Spanner (UPDATE mutation).
     * <p>
     * Uses a Spanner {@code UPDATE} mutation, which requires the row to already exist.
     * If the row is not found, Spanner throws a {@code NOT_FOUND} error which is mapped
     * to {@link com.hyperscaledb.api.HyperscaleDbErrorCategory#NOT_FOUND}.
     * The primary key columns and all document fields are written consistently with
     * {@link #create}.
     *
     * @param address  the logical database + collection
     * @param key      the document key identifying the row to update
     * @param document the new document payload; replaces all non-key columns
     * @param options  operation options (currently unused by this provider)
     * @throws com.hyperscaledb.api.HyperscaleDbException category {@code NOT_FOUND} if
     *         the row does not exist, or any other Spanner error
     */
    @Override
    public void update(ResourceAddress address, com.hyperscaledb.api.Key key, Map<String, Object> document, OperationOptions options) {
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newUpdateBuilder(table)
                    .set("partitionKey").to(key.partitionKey())
                    .set("sortKey").to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeMutationFields(mutation, document);
            databaseClient.write(List.of(mutation.build()));
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "update");
        }
    }

    /**
     * Creates or replaces a row in Spanner (INSERT_OR_UPDATE mutation / upsert semantics).
     * <p>
     * Uses a Spanner {@code INSERT_OR_UPDATE} mutation, which inserts the row if it does
     * not exist, or updates it in-place if it does.
     *
     * @param address  the logical database + collection
     * @param key      the document key
     * @param document the document payload
     * @param options  operation options (currently unused by this provider)
     * @throws com.hyperscaledb.api.HyperscaleDbException on any Spanner error
     */
    @Override
    public void upsert(ResourceAddress address, com.hyperscaledb.api.Key key, Map<String, Object> document, OperationOptions options) {
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newInsertOrUpdateBuilder(table)
                    .set("partitionKey").to(key.partitionKey())
                    .set("sortKey").to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeMutationFields(mutation, document);
            databaseClient.write(List.of(mutation.build()));
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "upsert");
        }
    }

    /**
     * Writes all document fields (except primary key columns) into a Spanner mutation.
     * <p>
     * The fields {@code partitionKey} and {@code sortKey} are skipped because they are
     * set by the caller before invoking this method. Each remaining entry is written
     * via {@link #setMutationValue}.
     *
     * @param mutation the mutation builder to populate
     * @param document the document payload; may be {@code null} (no fields written)
     */
    private void writeMutationFields(Mutation.WriteBuilder mutation, Map<String, Object> document) {
        if (document != null) {
            for (Map.Entry<String, Object> entry : document.entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();

                // Skip primary key fields — already set by caller
                if ("sortKey".equals(name) || "partitionKey".equals(name)) continue;

                setMutationValue(mutation, name, value);
            }
        }
    }

    /**
     * Reads a single row from Spanner by its composite primary key.
     * <p>
     * Executes a GoogleSQL query of the form
     * {@code SELECT * FROM <table> WHERE partitionKey = @partitionKey AND sortKey = @sortKey}.
     * Uses a {@code singleUse} read-only transaction (no session overhead).
     *
     * @param address the logical database + collection
     * @param key     the document key
     * @param options operation options (currently unused by this provider)
     * @return the row as a {@code Map<String, Object>}, or {@code null} if not found
     * @throws com.hyperscaledb.api.HyperscaleDbException on any Spanner error
     */
    @Override
    public Map<String, Object> read(ResourceAddress address, com.hyperscaledb.api.Key key, OperationOptions options) {
        try {
            String table = address.collection();
            String partitionKeyVal = key.partitionKey();
            String sortKeyVal = key.sortKey() != null ? key.sortKey() : key.partitionKey();

            Statement statement = Statement.newBuilder(
                    "SELECT * FROM " + table + " WHERE partitionKey = @partitionKey AND sortKey = @sortKey")
                    .bind("partitionKey").to(partitionKeyVal)
                    .bind("sortKey").to(sortKeyVal)
                    .build();

            try (ResultSet rs = databaseClient.singleUse().executeQuery(statement)) {
                if (rs.next()) {
                    return SpannerRowMapper.toMap(rs);
                }
                return null;
            }
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "read");
        }
    }

    /**
     * Deletes a row from Spanner by its composite primary key.
     * <p>
     * Uses a {@code DELETE} mutation. A {@code NOT_FOUND} Spanner error is silently
     * swallowed — delete is idempotent.
     *
     * @param address the logical database + collection
     * @param key     the document key identifying the row to delete
     * @param options operation options (currently unused by this provider)
     * @throws com.hyperscaledb.api.HyperscaleDbException on any non-NOT_FOUND Spanner error
     */
    @Override
    public void delete(ResourceAddress address, com.hyperscaledb.api.Key key, OperationOptions options) {
        try {
            String table = address.collection();
            String partitionKeyVal = key.partitionKey();
            String sortKeyVal = key.sortKey() != null ? key.sortKey() : key.partitionKey();

            com.google.cloud.spanner.Key spannerKey = com.google.cloud.spanner.Key.of(partitionKeyVal, sortKeyVal);
            Mutation deleteMutation = Mutation.delete(table, KeySet.singleKey(spannerKey));

            databaseClient.write(List.of(deleteMutation));
        } catch (SpannerException e) {
            // Delete is idempotent — NOT_FOUND is not an error
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return;
            }
            throw SpannerErrorMapper.map(e, "delete");
        }
    }

    /**
     * Executes a query and returns a single page of results using LIMIT/OFFSET pagination.
     * <p>
     * Query routing logic (evaluated in order):
     * <ol>
     *   <li><b>Native GoogleSQL passthrough</b> — if {@link QueryRequest#nativeExpression()}
     *       is set, it is executed as-is.</li>
     *   <li><b>Full scan</b> — if expression is null/blank or equals the Cosmos-style
     *       {@code "SELECT * FROM c"} sentinel, a {@code SELECT * FROM <table>} is
     *       executed.</li>
     *   <li><b>Legacy expression</b> — the expression is passed through to
     *       {@link #executeStatement} as-is (backward-compatible path).</li>
     * </ol>
     * If {@link QueryRequest#partitionKey()} is set, a {@code WHERE partitionKey = @_pkval}
     * (or {@code AND partitionKey = @_pkval}) condition is appended automatically.
     * <p>
     * Pagination uses integer OFFSET encoding via {@link SpannerContinuationToken}.
     * Note: OFFSET-based pagination is not ideal for large datasets — it rescans all
     * preceding rows on each call.
     *
     * @param address the logical database + collection
     * @param query   query request containing expression, parameters, page size, and
     *                optional continuation token
     * @param options operation options (currently unused by this provider)
     * @return a page of results; {@link QueryPage#continuationToken()} is non-null when
     *         more pages are available
     * @throws com.hyperscaledb.api.HyperscaleDbException on any Spanner error
     */
    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        try {
            String table = address.collection();
            long offset = SpannerContinuationToken.decode(query.continuationToken());

            // Native expression passthrough
            if (query.nativeExpression() != null && !query.nativeExpression().isBlank()) {
                String stmt = query.nativeExpression();
                Map<String, Object> params = query.parameters();
                if (query.partitionKey() != null) {
                    stmt = appendPartitionKeyConditionSQL(stmt);
                    Map<String, Object> combined = new LinkedHashMap<>();
                    if (params != null) {
                        combined.putAll(params);
                    }
                    combined.put("_pkval", query.partitionKey());
                    params = combined;
                }
                return executeStatement(stmt, params, query.pageSize(), offset);
            }

            // Expression-based query or full scan
            String expression = query.expression();
            if (expression == null || expression.isBlank()
                    || expression.trim().equalsIgnoreCase("SELECT * FROM c")) {
                if (query.partitionKey() != null) {
                    // Scope scan to items with matching partitionKey
                    return executeStatement(
                            "SELECT * FROM " + table + " WHERE partitionKey = @_pkval",
                            Map.of("_pkval", query.partitionKey()),
                            query.pageSize(), offset);
                }
                // Full scan
                return executeStatement("SELECT * FROM " + table, null, query.pageSize(), offset);
            }

            // Legacy: pass through as-is
            if (query.partitionKey() != null) {
                String stmt = appendPartitionKeyConditionSQL(expression);
                Map<String, Object> combined = new LinkedHashMap<>();
                if (query.parameters() != null) {
                    combined.putAll(query.parameters());
                }
                combined.put("_pkval", query.partitionKey());
                return executeStatement(stmt, combined, query.pageSize(), offset);
            }
            return executeStatement(expression, query.parameters(), query.pageSize(), offset);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "query");
        }
    }

    /**
     * Executes a pre-translated portable query using GoogleSQL and returns a single
     * page of results.
     * <p>
     * Called by {@link com.hyperscaledb.api.internal.DefaultHyperscaleDbClient} after
     * the portable expression has been parsed, validated, and translated into GoogleSQL
     * by {@link SpannerExpressionTranslator}. Named parameters from
     * {@link TranslatedQuery#namedParameters()} are bound; leading {@code @} prefixes
     * are stripped because Spanner's {@link Statement.Builder} expects bare names.
     * <p>
     * {@code LIMIT (pageSize + 1) OFFSET offset} is appended for pagination; if the
     * result set contains more than {@code pageSize} rows, a continuation token is
     * encoded and returned.
     *
     * @param address    the logical database + collection
     * @param translated the GoogleSQL statement and named parameters produced by the
     *                   expression translator
     * @param query      the original query request (used for page size, continuation
     *                   token, and partition key)
     * @param options    operation options (currently unused by this provider)
     * @return a page of results with an optional continuation token
     * @throws com.hyperscaledb.api.HyperscaleDbException on any Spanner error
     */
    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        try {
            long offset = SpannerContinuationToken.decode(query.continuationToken());
            int pageSize = query.pageSize() != null ? query.pageSize() : 100;

            // Inject partition key condition before pagination
            String sql = translated.queryString();
            if (query.partitionKey() != null) {
                sql = appendPartitionKeyConditionSQL(sql);
            }

            // Append LIMIT/OFFSET to the translated SQL for pagination
            String pagedSql = sql + " LIMIT " + (pageSize + 1) + " OFFSET " + offset;

            Statement.Builder stmtBuilder = Statement.newBuilder(pagedSql);

            // Bind named parameters from the translated query
            for (Map.Entry<String, Object> entry : translated.namedParameters().entrySet()) {
                String paramName = entry.getKey();
                // Strip leading @ if present — Spanner Statement expects param name without @
                if (paramName.startsWith("@")) {
                    paramName = paramName.substring(1);
                }
                bindParameter(stmtBuilder, paramName, entry.getValue());
            }

            // Bind partition key parameter if present
            if (query.partitionKey() != null) {
                stmtBuilder.bind("_pkval").to(query.partitionKey());
            }

            Statement stmt = stmtBuilder.build();

            List<Map<String, Object>> items = new ArrayList<>();
            try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
                while (rs.next() && items.size() < pageSize + 1) {
                    items.add(SpannerRowMapper.toMap(rs));
                }
            }

            // If we got more than pageSize items, there are more pages
            boolean hasMore = items.size() > pageSize;
            if (hasMore) {
                items = items.subList(0, pageSize);
            }
            String continuationToken = hasMore
                    ? SpannerContinuationToken.encode(offset + pageSize)
                    : null;
            return new QueryPage(items, continuationToken);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "query");
        }
    }

    @Override
    public CapabilitySet capabilities() {
        return SpannerCapabilities.CAPABILITIES;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T nativeClient(Class<T> clientType) {
        if (clientType.isInstance(spanner)) {
            return (T) spanner;
        }
        return null;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    public void close() {
        if (spanner != null) {
            spanner.close();
        }
    }

    // ── Provisioning ────────────────────────────────────────────────────────

    /**
     * No-op — the Spanner database is set at client construction time.
     */
    @Override
    public void ensureDatabase(String database) {
        LOG.debug("ensureDatabase is a no-op for Spanner (database={})", database);
    }

    /**
     * Ensures the Spanner table for the given address exists, creating it if absent.
     * <p>
     * Existence is detected by issuing a lightweight {@code SELECT 1 FROM <table> LIMIT 1}
     * query. If that throws {@code NOT_FOUND} or {@code INVALID_ARGUMENT} (table does not
     * exist), a DDL {@code CREATE TABLE} statement is issued via the
     * {@link DatabaseAdminClient}.
     * <p>
     * The table is always created with the standard schema:
     * <pre>
     * CREATE TABLE &lt;tableName&gt; (
     *   partitionKey STRING(MAX) NOT NULL,
     *   sortKey      STRING(MAX) NOT NULL,
     *   data         STRING(MAX)
     * ) PRIMARY KEY (partitionKey, sortKey)
     * </pre>
     * Race conditions ("Duplicate name in schema") are silently ignored.
     *
     * @param address the logical database + collection; {@code address.collection()} is
     *                used as the Spanner table name
     * @throws com.hyperscaledb.api.HyperscaleDbException on DDL errors
     * @throws RuntimeException if the DDL future completes exceptionally for a non-Spanner
     *         reason
     */
    @Override
    public void ensureContainer(ResourceAddress address) {
        String tableName = address.collection();
        try {
            // Check if table already exists by attempting a trivial query
            Statement checkStmt = Statement.of(
                    "SELECT 1 FROM " + tableName + " LIMIT 1");
            try (ResultSet rs = databaseClient.singleUse().executeQuery(checkStmt)) {
                // If we get here, table exists
                LOG.info("Spanner table already exists: {}", tableName);
                return;
            }
        } catch (SpannerException e) {
            if (e.getErrorCode() != ErrorCode.NOT_FOUND
                    && e.getErrorCode() != ErrorCode.INVALID_ARGUMENT) {
                throw SpannerErrorMapper.map(e, "ensureContainer");
            }
            // Table doesn't exist — create it
        }

        try {
            DatabaseAdminClient adminClient = spanner.getDatabaseAdminClient();
            String ddl = "CREATE TABLE " + tableName + " ("
                    + "partitionKey STRING(MAX) NOT NULL, "
                    + "sortKey STRING(MAX) NOT NULL, "
                    + "data STRING(MAX)"
                    + ") PRIMARY KEY (partitionKey, sortKey)";
            adminClient.updateDatabaseDdl(
                    instanceId, databaseId, List.of(ddl), null).get();
            LOG.info("Created Spanner table: {}", tableName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate name in schema")) {
                LOG.debug("Spanner table already exists (race): {}", tableName);
            } else if (e instanceof SpannerException se) {
                throw SpannerErrorMapper.map(se, "ensureContainer");
            } else {
                throw new RuntimeException("Failed to create Spanner table: " + tableName, e);
            }
        }
    }

    // ---- Internal helpers ----

    /**
     * Appends a partition key scoping condition to a GoogleSQL statement.
     * <p>
     * If the statement already has a {@code WHERE} clause, appends
     * {@code AND partitionKey = @_pkval}; otherwise appends
     * {@code WHERE partitionKey = @_pkval}.
     * The caller must bind the {@code @_pkval} parameter separately.
     *
     * @param sql the base SQL statement
     * @return the statement with the partition key condition appended
     */
    private String appendPartitionKeyConditionSQL(String sql) {
        if (sql.toUpperCase().contains("WHERE")) {
            return sql + " AND partitionKey = @_pkval";
        }
        return sql + " WHERE partitionKey = @_pkval";
    }

    /**
     * Executes a GoogleSQL statement with LIMIT/OFFSET pagination and returns one page.
     * <p>
     * Appends {@code LIMIT (pageSize + 1) OFFSET offset} to detect whether more pages
     * exist. Parameter names starting with {@code @} are stripped before binding.
     *
     * @param sql        the GoogleSQL statement (without LIMIT/OFFSET)
     * @param parameters named query parameters, or {@code null}
     * @param pageSize   maximum items per page; defaults to 100 if {@code null}
     * @param offset     the number of rows to skip (0 for the first page)
     * @return a page of results with an encoded continuation token if more rows exist
     */
    private QueryPage executeStatement(String sql, Map<String, Object> parameters,
            Integer pageSize, long offset) {
        int limit = pageSize != null ? pageSize : 100;

        // Append LIMIT/OFFSET for pagination
        String pagedSql = sql + " LIMIT " + (limit + 1) + " OFFSET " + offset;

        Statement.Builder stmtBuilder = Statement.newBuilder(pagedSql);

        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                String paramName = entry.getKey().startsWith("@")
                        ? entry.getKey().substring(1)
                        : entry.getKey();
                bindParameter(stmtBuilder, paramName, entry.getValue());
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();

        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmtBuilder.build())) {
            while (rs.next() && items.size() < limit + 1) {
                items.add(SpannerRowMapper.toMap(rs));
            }
        }

        // If we fetched more than pageSize, there are additional pages
        boolean hasMore = items.size() > limit;
        if (hasMore) {
            items = items.subList(0, limit);
        }
        String continuationToken = hasMore
                ? SpannerContinuationToken.encode(offset + limit)
                : null;
        return new QueryPage(items, continuationToken);
    }

    /**
     * Binds a single named parameter to a Spanner {@link Statement.Builder}.
     * <p>
     * Supported Java types: {@link String}, {@link Long}, {@link Integer},
     * {@link Boolean}, {@link Double}, {@link Float}, and {@code null} (bound as
     * {@code STRING NULL}). All other types are converted via {@link Object#toString()}.
     *
     * @param builder the statement builder to bind the parameter to
     * @param name    the bare parameter name (without the leading {@code @})
     * @param value   the parameter value; {@code null} is bound as a null STRING
     */
    private void bindParameter(Statement.Builder builder, String name, Object value) {
        if (value instanceof String s) {
            builder.bind(name).to(s);
        } else if (value instanceof Long l) {
            builder.bind(name).to(l);
        } else if (value instanceof Integer i) {
            builder.bind(name).to((long) i);
        } else if (value instanceof Boolean b) {
            builder.bind(name).to(b);
        } else if (value instanceof Double d) {
            builder.bind(name).to(d);
        } else if (value instanceof Float f) {
            builder.bind(name).to((double) f);
        } else if (value == null) {
            builder.bind(name).to((String) null);
        } else {
            // Fallback: convert to string
            builder.bind(name).to(value.toString());
        }
    }

    /**
     * Sets a single column value in a Spanner mutation builder.
     * <p>
     * Supported Java types: {@link String}, {@link Long}, {@link Integer},
     * {@link Boolean}, {@link Double}, {@link Float}, and {@code null}
     * (written as {@code NULL STRING}). Complex types (maps, lists, etc.) are
     * serialised via {@link Object#toString()}.
     *
     * @param mutation the mutation builder to write the column into
     * @param column   the Spanner column name
     * @param value    the value to write; {@code null} writes a null STRING
     */
    private void setMutationValue(Mutation.WriteBuilder mutation, String column, Object value) {
        if (value == null) {
            mutation.set(column).to((String) null);
        } else if (value instanceof String s) {
            mutation.set(column).to(s);
        } else if (value instanceof Long l) {
            mutation.set(column).to(l);
        } else if (value instanceof Integer i) {
            mutation.set(column).to((long) i);
        } else if (value instanceof Boolean b) {
            mutation.set(column).to(b);
        } else if (value instanceof Double d) {
            mutation.set(column).to(d);
        } else if (value instanceof Float f) {
            mutation.set(column).to((double) f);
        } else {
            // Complex types: serialize as JSON string
            mutation.set(column).to(value.toString());
        }
    }

}
