package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.CapabilitySet;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.OperationDiagnostics;
import com.hyperscaledb.api.OperationNames;
import com.hyperscaledb.api.OperationOptions;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.hyperscaledb.api.query.TranslatedQuery;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
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

    @Override
    public void create(ResourceAddress address, com.hyperscaledb.api.Key key, JsonNode document, OperationOptions options) {
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

    @Override
    public void update(ResourceAddress address, com.hyperscaledb.api.Key key, JsonNode document, OperationOptions options) {
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

    @Override
    public void upsert(ResourceAddress address, com.hyperscaledb.api.Key key, JsonNode document, OperationOptions options) {
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

    /** Writes document fields into a mutation, skipping PK columns. */
    private void writeMutationFields(Mutation.WriteBuilder mutation, JsonNode document) {
        if (document != null && document.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = document.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();

                // Skip primary key fields — already set by caller
                if ("sortKey".equals(name) || "partitionKey".equals(name))
                    continue;

                setMutationValue(mutation, name, value);
            }
        }
    }

    @Override
    public JsonNode read(ResourceAddress address, com.hyperscaledb.api.Key key, OperationOptions options) {
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
                    return SpannerRowMapper.toJsonNode(rs);
                }
                return null;
            }
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, "read");
        }
    }

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

            List<JsonNode> items = new ArrayList<>();
            try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
                while (rs.next() && items.size() < pageSize + 1) {
                    items.add(SpannerRowMapper.toJsonNode(rs));
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

            OperationDiagnostics diag = OperationDiagnostics
                    .builder(ProviderId.SPANNER, OperationNames.QUERY_WITH_TRANSLATION, java.time.Duration.ZERO)
                    .itemCount(items.size())
                    .build();
            LOG.debug("spanner.diagnostics op={} itemCount={} hasMore={}", OperationNames.QUERY_WITH_TRANSLATION, items.size(), hasMore);
            return new QueryPage(items, continuationToken, null, diag);
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
     * Appends {@code AND partitionKey = @_pkval} (or
     * {@code WHERE partitionKey = @_pkval})
     * to a SQL statement so the query is scoped to a single partition key value.
     */
    private String appendPartitionKeyConditionSQL(String sql) {
        if (sql.toUpperCase().contains("WHERE")) {
            return sql + " AND partitionKey = @_pkval";
        }
        return sql + " WHERE partitionKey = @_pkval";
    }

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

        List<JsonNode> items = new ArrayList<>();

        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmtBuilder.build())) {
            while (rs.next() && items.size() < limit + 1) {
                items.add(SpannerRowMapper.toJsonNode(rs));
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

        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.SPANNER, OperationNames.QUERY, java.time.Duration.ZERO)
                .itemCount(items.size())
                .build();
        LOG.debug("spanner.diagnostics op=query itemCount={} hasMore={}", items.size(), hasMore);
        return new QueryPage(items, continuationToken, null, diag);
    }

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

    private void setMutationValue(Mutation.WriteBuilder mutation, String column, JsonNode value) {
        if (value == null || value.isNull()) {
            mutation.set(column).to((String) null);
        } else if (value.isTextual()) {
            mutation.set(column).to(value.asText());
        } else if (value.isInt() || value.isLong()) {
            mutation.set(column).to(value.asLong());
        } else if (value.isBoolean()) {
            mutation.set(column).to(value.asBoolean());
        } else if (value.isDouble() || value.isFloat()) {
            mutation.set(column).to(value.asDouble());
        } else {
            // Complex types: serialize as JSON string
            mutation.set(column).to(value.toString());
        }
    }

}
