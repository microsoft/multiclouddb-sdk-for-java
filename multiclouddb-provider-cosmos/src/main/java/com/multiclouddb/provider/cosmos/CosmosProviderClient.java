// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.multiclouddb.api.*;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.SdkUserAgent;
import com.multiclouddb.api.query.TranslatedQuery;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Azure Cosmos DB provider client implementing CRUD + query with continuation
 * token paging.
 * <p>
 * Connection config keys:
 * <ul>
 * <li>{@code endpoint} - Cosmos account endpoint URL (required)</li>
 * <li>{@code key} - Cosmos account key (optional — when absent, the SDK
 * transparently uses {@link DefaultAzureCredentialBuilder} which supports
 * Managed Identity, Azure CLI, environment variables, and other credential
 * types in the DefaultAzureCredential chain)</li>
 * </ul>
 */
public class CosmosProviderClient implements MulticloudDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosProviderClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CosmosClient cosmosClient;
    private final MulticloudDbClientConfig config;


    /**
     * Constructs a Cosmos DB provider client from the supplied configuration.
     * <p>
     * Authentication is selected automatically:
     * <ul>
     *   <li>If {@code connection.key} is present, key-based authentication is used.</li>
     *   <li>Otherwise {@link DefaultAzureCredentialBuilder} is used, supporting
     *       Managed Identity, Azure CLI, environment variables, and the full
     *       DefaultAzureCredential chain.</li>
     * </ul>
     *
     * @param config client configuration carrying connection, auth, and options
     * @throws IllegalArgumentException if {@code connection.endpoint} is missing or blank
     */
    public CosmosProviderClient(MulticloudDbClientConfig config) {
        this.config = config;
        String endpoint = config.connection().get(CosmosConstants.CONFIG_ENDPOINT);
        String key      = config.connection().get(CosmosConstants.CONFIG_KEY);

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(CosmosConstants.ERR_ENDPOINT_REQUIRED);
        }

        CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(endpoint)
                .consistencyLevel(CosmosConstants.CONSISTENCY_LEVEL_DEFAULT)
                .contentResponseOnWriteEnabled(true);

        if (key != null && !key.isBlank()) {
            builder.key(key);
            LOG.info("Cosmos client using key-based authentication");
        } else {
            String tenantId = config.connection().get(CosmosConstants.CONFIG_TENANT_ID);
            DefaultAzureCredentialBuilder credentialBuilder = new DefaultAzureCredentialBuilder();
            if (tenantId != null && !tenantId.isBlank()) {
                credentialBuilder.tenantId(tenantId);
            }
            TokenCredential credential = credentialBuilder.build();
            builder.credential(credential);
            LOG.info("Cosmos client using DefaultAzureCredential (supports Managed Identity, Azure CLI, environment variables)");
        }

        String connectionMode = config.connection().getOrDefault(
                CosmosConstants.CONFIG_CONNECTION_MODE, CosmosConstants.CONNECTION_MODE_DEFAULT);
        if (CosmosConstants.CONNECTION_MODE_DIRECT.equalsIgnoreCase(connectionMode)) {
            builder.directMode();
        } else {
            builder.gatewayMode();
        }

        builder.userAgentSuffix(SdkUserAgent.userAgent(config));

        this.cosmosClient = builder.buildClient();
        LOG.info("Cosmos client created for endpoint: {}", endpoint);
    }

    /**
     * Inserts a new document into the specified container.
     * <p>
     * Before writing, two system fields are injected into the document:
     * <ul>
     *   <li>{@code id} — set to {@code key.sortKey()} if present, otherwise {@code key.partitionKey()}.
     *       This is the Cosmos DB item identifier required by the SDK.</li>
     *   <li>{@code partitionKey} — set to {@code key.partitionKey()}, matching the
     *       container's partition key path ({@code /partitionKey}).</li>
     * </ul>
     * Uses a {@code createItem} call with no pre-condition, so the operation fails with
     * {@link com.multiclouddb.api.MulticloudDbErrorCategory#CONFLICT} if an item with
     * the same {@code id} already exists in the partition.
     *
     * @param address the logical database + container to write to
     * @param key     the document key; {@code partitionKey} is required, {@code sortKey} is optional
     * @param document the document payload as a flat or nested map
     * @param options  operation options (currently unused by this provider; reserved for timeout support)
     * @throws com.multiclouddb.api.MulticloudDbException mapped from {@link CosmosException} —
     *         category {@code CONFLICT} (409) if the key already exists
     */
    @Override
    public void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = toObjectNode(document);
            doc.put(CosmosConstants.FIELD_ID, key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            if (options != null && options.ttlSeconds() != null) {
                doc.put(CosmosConstants.FIELD_TTL, options.ttlSeconds());
            }
            PartitionKey pk = resolvePartitionKey(key);
            CosmosItemResponse<ObjectNode> response = container.createItem(doc, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.CREATE, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.CREATE, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.CREATE);
        }
    }

    /**
     * Reads a single document by its composite key.
     * <p>
     * Performs a direct point-read using the Cosmos DB {@code readItem} API, which is
     * the lowest-latency read path. The item is looked up by the Cosmos {@code id}
     * (derived from {@code key.sortKey()} or {@code key.partitionKey()}) within the
     * specified logical partition.
     *
     * @param address the logical database + container to read from
     * @param key     the document key; {@code partitionKey} is required, {@code sortKey} is optional
     * @param options operation options (currently unused by this provider)
     * @return the document as a {@code Map<String, Object>}, or {@code null} if not found (HTTP 404)
     * @throws com.multiclouddb.api.MulticloudDbException for any non-404 Cosmos error
     */
    @Override
    public DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            CosmosItemResponse<ObjectNode> response = container.readItem(cosmosId, pk, ObjectNode.class);
            logItemDiagnostics(OperationNames.READ, address, response);
            ObjectNode raw = response.getItem();
            if (raw == null) return null;

            // Strip Cosmos system properties so the returned document is portable
            // across providers (Cosmos-only fields like _rid, _self, _ts, etc. must
            // not appear in a DocumentResult that callers compare across providers).
            ObjectNode item = raw.deepCopy();
            CosmosConstants.SYSTEM_FIELDS.forEach(item::remove);

            DocumentMetadata metadata = null;
            if (options != null && options.includeMetadata()) {
                DocumentMetadata.Builder metaBuilder = DocumentMetadata.builder();
                if (response.getETag() != null) {
                    metaBuilder.version(response.getETag());
                }
                // _ts is a Unix epoch second — expose as lastModified
                JsonNode tsNode = raw.get(CosmosConstants.SYS_TIMESTAMP);
                if (tsNode != null && tsNode.isNumber()) {
                    metaBuilder.lastModified(Instant.ofEpochSecond(tsNode.longValue()));
                }
                metadata = metaBuilder.build();
            }
            return new DocumentResult(item, metadata);
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            logExceptionDiagnostics(OperationNames.READ, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.READ);
        }
    }

    /**
     * Replaces an existing document in the specified container.
     * <p>
     * Uses the Cosmos DB {@code replaceItem} API, which requires the item to already
     * exist — the operation fails with {@link com.multiclouddb.api.MulticloudDbErrorCategory#NOT_FOUND}
     * if no matching item is found. The system fields {@code id} and {@code partitionKey}
     * are injected before the write, consistent with {@link #create}.
     *
     * @param address  the logical database + container
     * @param key      the document key identifying the item to replace
     * @param document the new document payload; replaces the entire stored document
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException category {@code NOT_FOUND} (404) if the item
     *         does not exist
     */
    @Override
    public void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = toObjectNode(document);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            doc.put(CosmosConstants.FIELD_ID, cosmosId);
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            if (options != null && options.ttlSeconds() != null) {
                doc.put(CosmosConstants.FIELD_TTL, options.ttlSeconds());
            }
            PartitionKey pk = resolvePartitionKey(key);
            CosmosItemResponse<ObjectNode> response = container.replaceItem(doc, cosmosId, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.UPDATE, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.UPDATE, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    /**
     * Creates or replaces a document (upsert semantics).
     * <p>
     * Uses the Cosmos DB {@code upsertItem} API, which inserts the item if it does not
     * exist, or replaces it completely if it does. The system fields {@code id} and
     * {@code partitionKey} are injected before the write.
     *
     * @param address  the logical database + container
     * @param key      the document key
     * @param document the document payload
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any Cosmos error
     */
    @Override
    public void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = toObjectNode(document);
            doc.put(CosmosConstants.FIELD_ID, key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            if (options != null && options.ttlSeconds() != null) {
                doc.put(CosmosConstants.FIELD_TTL, options.ttlSeconds());
            }
            PartitionKey pk = resolvePartitionKey(key);
            CosmosItemResponse<ObjectNode> response = container.upsertItem(doc, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.UPSERT, address, response);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.UPSERT, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.UPSERT);
        }
    }

    /**
     * Deletes a document by its composite key.
     * <p>
     * A 404 (item not found) response is treated as a success — delete is idempotent.
     * All other Cosmos errors are mapped to a {@link com.multiclouddb.api.MulticloudDbException}.
     *
     * @param address the logical database + container
     * @param key     the document key identifying the item to delete
     * @param options operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any non-404 Cosmos error
     */
    @Override
    public void delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            CosmosItemResponse<Object> response = container.deleteItem(cosmosId, pk, new CosmosItemRequestOptions());
            logItemDiagnostics(OperationNames.DELETE, address, response);
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return;
            }
            logExceptionDiagnostics(OperationNames.DELETE, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.DELETE);
        }
    }

    /**
     * Executes a query and returns a single page of results.
     * <p>
     * Query routing logic (evaluated in order):
     * <ol>
     *   <li>If {@link QueryRequest#nativeExpression()} is set, it is used as-is as the
     *       Cosmos SQL string (native passthrough).</li>
     *   <li>If {@link QueryRequest#expression()} is set, it is used as the Cosmos SQL
     *       WHERE expression.</li>
     *   <li>If neither is set, {@code SELECT * FROM c} is used (full container scan).</li>
     * </ol>
     * Named parameters ({@code @name} syntax) from {@link QueryRequest#parameters()} are
     * bound as {@link SqlParameter} values. Parameter names that do not already start with
     * {@code @} are prefixed automatically.
     * <p>
     * If {@link QueryRequest#partitionKey()} is set, the query is scoped to a single
     * logical partition via {@link CosmosQueryRequestOptions#setPartitionKey}, avoiding
     * a cross-partition fan-out.
     * <p>
     * Only the first page of results is returned; pass the returned
     * {@link QueryPage#continuationToken()} in the next request to page forward.
     *
     * @param address the logical database + container to query
     * @param query   query request containing expression, parameters, page size, and
     *                optional continuation token
     * @param options operation options (currently unused by this provider)
     * @return a page of results; {@link QueryPage#continuationToken()} is non-null when
     *         more pages are available
     * @throws com.multiclouddb.api.MulticloudDbException on any Cosmos query error
     */
    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            CosmosQueryRequestOptions queryOptions = new CosmosQueryRequestOptions();
            if (query.partitionKey() != null) {
                queryOptions.setPartitionKey(new PartitionKey(query.partitionKey()));
            }
            if (query.maxPageSize() != null) {
                queryOptions.setMaxBufferedItemCount(query.maxPageSize());
            }

            String expression = query.nativeExpression() != null ? query.nativeExpression() : query.expression();
            if (expression == null || expression.isBlank()) {
                expression = CosmosConstants.QUERY_SELECT_ALL;
            }

            // Apply TOP N and ORDER BY for non-native expressions
            if (query.nativeExpression() == null) {
                expression = applyResultSetControl(expression, query);
            }

            List<SqlParameter> sqlParams = new ArrayList<>();
            if (query.parameters() != null) {
                for (Map.Entry<String, Object> entry : query.parameters().entrySet()) {
                    String paramName = entry.getKey().startsWith(CosmosConstants.QUERY_PARAM_PREFIX)
                            ? entry.getKey()
                            : CosmosConstants.QUERY_PARAM_PREFIX + entry.getKey();
                    sqlParams.add(new SqlParameter(paramName, entry.getValue()));
                }
            }

            SqlQuerySpec sqlQuery = new SqlQuerySpec(expression, sqlParams);
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : CosmosConstants.PAGE_SIZE_DEFAULT;
            List<Map<String, Object>> items = new ArrayList<>();
            String continuationToken = null;
            java.time.Instant queryStart = java.time.Instant.now();

            Iterable<FeedResponse<JsonNode>> pages;
            if (query.continuationToken() != null) {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(query.continuationToken(), pageSize);
            } else {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(pageSize);
            }

            for (FeedResponse<JsonNode> page : pages) {
                for (JsonNode item : page.getResults()) {
                    items.add(toMap(item));
                }
                continuationToken = page.getContinuationToken();
                OperationDiagnostics diag = buildFeedDiagnostics(OperationNames.QUERY, address, page,
                        items.size(), java.time.Duration.between(queryStart, java.time.Instant.now()));
                return new QueryPage(items, continuationToken, diag);
            }

            OperationDiagnostics emptyDiag = OperationDiagnostics
                    .builder(ProviderId.COSMOS, OperationNames.QUERY,
                            java.time.Duration.between(queryStart, java.time.Instant.now()))
                    .itemCount(0).build();
            return new QueryPage(items, continuationToken, emptyDiag);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.QUERY, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    /**
     * Executes a pre-translated portable query and returns a single page of results.
     * <p>
     * Called by {@link com.multiclouddb.api.internal.DefaultMulticloudDbClient} after
     * the portable expression has been parsed, validated, and translated into Cosmos SQL
     * by {@link CosmosExpressionTranslator}. Named parameters from
     * {@link TranslatedQuery#namedParameters()} are bound directly as
     * {@link SqlParameter} values.
     * <p>
     * If {@link QueryRequest#partitionKey()} is set the query is scoped to a single
     * partition, consistent with {@link #query}.
     *
     * @param address    the logical database + container to query
     * @param translated the Cosmos SQL string and bound named parameters produced by the
     *                   expression translator
     * @param query      the original query request (used for page size, continuation
     *                   token, and partition key)
     * @param options    operation options (currently unused by this provider)
     * @return a page of results with an optional continuation token
     * @throws com.multiclouddb.api.MulticloudDbException on any Cosmos query error
     */
    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            CosmosQueryRequestOptions queryOptions = new CosmosQueryRequestOptions();
            if (query.partitionKey() != null) {
                queryOptions.setPartitionKey(new PartitionKey(query.partitionKey()));
            }
            if (query.maxPageSize() != null) {
                queryOptions.setMaxBufferedItemCount(query.maxPageSize());
            }

            List<SqlParameter> sqlParams = new ArrayList<>();
            for (Map.Entry<String, Object> entry : translated.namedParameters().entrySet()) {
                sqlParams.add(new SqlParameter(entry.getKey(), entry.getValue()));
            }

            String sql = applyResultSetControl(translated.queryString(), query);
            SqlQuerySpec sqlQuery = new SqlQuerySpec(sql, sqlParams);
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : CosmosConstants.PAGE_SIZE_DEFAULT;
            List<Map<String, Object>> items = new ArrayList<>();
            String continuationToken = null;
            java.time.Instant queryStart = java.time.Instant.now();

            Iterable<FeedResponse<JsonNode>> pages;
            if (query.continuationToken() != null) {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(query.continuationToken(), pageSize);
            } else {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(pageSize);
            }

            for (FeedResponse<JsonNode> page : pages) {
                for (JsonNode item : page.getResults()) {
                    items.add(toMap(item));
                }
                continuationToken = page.getContinuationToken();
                OperationDiagnostics diag = buildFeedDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address,
                        page, items.size(), java.time.Duration.between(queryStart, java.time.Instant.now()));
                return new QueryPage(items, continuationToken, diag);
            }

            OperationDiagnostics emptyDiag = OperationDiagnostics
                    .builder(ProviderId.COSMOS, OperationNames.QUERY,
                            java.time.Duration.between(queryStart, java.time.Instant.now()))
                    .itemCount(0).build();
            return new QueryPage(items, continuationToken, emptyDiag);
        } catch (CosmosException e) {
            logExceptionDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address, e);
            throw CosmosErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    @Override
    public CapabilitySet capabilities() {
        return CosmosCapabilities.CAPABILITIES;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    public void close() {
        cosmosClient.close();
    }

    // ── Provisioning ─────────────────────────────────────────────────────────

    /**
     * Creates the Cosmos DB database if it does not already exist (idempotent).
     * <p>
     * Uses the data-plane {@code createDatabaseIfNotExists} call. No management
     * or ARM SDK dependency is required.
     * <p>
     * <b>Permission requirement:</b> The caller must hold a role that permits
     * control-plane database creation (e.g., <em>Cosmos DB Operator</em> or
     * key-based authentication). When operating under a data-plane-only RBAC role
     * (e.g., <em>Cosmos DB Built-in Data Contributor</em>), this call will fail
     * with a {@code PERMISSION_DENIED} error. In that case provision the database
     * out-of-band (Azure Portal, CLI, or Bicep/Terraform) and do not call this
     * method.
     *
     * @param database the logical database name to create if absent
     * @throws MulticloudDbException with category {@code PERMISSION_DENIED} when the
     *                               caller lacks control-plane permissions, or
     *                               category {@code INTERNAL_ERROR} for other failures
     */
    @Override
    public void ensureDatabase(String database) {
        try {
            cosmosClient.createDatabaseIfNotExists(database);
            LOG.info("ensureDatabase: created or verified Cosmos database '{}'", database);
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.ENSURE_DATABASE);
        }
    }

    /**
     * Creates the Cosmos DB container if it does not already exist (idempotent).
     * <p>
     * The container is always created with partition key path
     * {@code /partitionKey}, matching the system field injected by the CRUD
     * methods.
     *
     * @param address the logical database + container address
     * @throws MulticloudDbException if creation fails
     */
    @Override
    public void ensureContainer(ResourceAddress address) {
        try {
            CosmosDatabase db = cosmosClient.getDatabase(address.database());
            CosmosContainerProperties props = new CosmosContainerProperties(
                    address.collection(), CosmosConstants.PARTITION_KEY_PATH);
            db.createContainerIfNotExists(props);
            LOG.info("ensureContainer: created or verified Cosmos container '{}/{}'",
                    address.database(), address.collection());
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.ENSURE_CONTAINER);
        }
    }

    /**
     * Returns the {@link CosmosContainer} handle for the given resource address.
     * Does not make a network call — the Cosmos SDK resolves the container lazily.
     *
     * @param address the logical database + container
     * @return a live {@link CosmosContainer} reference
     */
    private CosmosContainer getContainer(ResourceAddress address) {
        CosmosDatabase database = cosmosClient.getDatabase(address.database());
        return database.getContainer(address.collection());
    }

    /**
     * Resolves the Cosmos DB {@link PartitionKey} from the portable {@link MulticloudDbKey}.
     * The partition key value is always {@code key.partitionKey()}.
     *
     * @param key the portable document key
     * @return the Cosmos SDK partition key object
     */
    private PartitionKey resolvePartitionKey(MulticloudDbKey key) {
        return new PartitionKey(key.partitionKey());
    }

    /**
     * Applies TOP N (limit) and ORDER BY from the query request to a Cosmos SQL string.
     * <p>
     * For TOP N: rewrites {@code SELECT} to {@code SELECT TOP N} when limit is set.
     * For ORDER BY: appends {@code ORDER BY c.field ASC/DESC} clause.
     */
    private String applyResultSetControl(String sql, QueryRequest query) {
        String result = sql;

        // Apply TOP N — rewrite SELECT to SELECT TOP N using a boolean flag to
        // track success, avoiding false positives from field names that contain
        // the substring "TOP" (e.g. "topic", "topology", "stopper").
        if (query.limit() != null) {
            boolean topApplied = false;

            // Pattern 1: "SELECT VALUE c ..." — Cosmos scalar projection
            String r1 = result.replaceFirst("(?i)^SELECT\\s+VALUE\\s+c\\b",
                    "SELECT TOP " + query.limit() + " VALUE c");
            if (!r1.equals(result)) {
                result = r1;
                topApplied = true;
            }

            // Pattern 2: "SELECT * ..." — full document projection
            if (!topApplied) {
                String r2 = result.replaceFirst("(?i)^SELECT\\s+\\*",
                        "SELECT TOP " + query.limit() + " *");
                if (!r2.equals(result)) {
                    result = r2;
                    topApplied = true;
                }
            }

            // Pattern 3: any other SELECT (custom projections, aliases, etc.)
            if (!topApplied) {
                result = result.replaceFirst("(?i)^SELECT\\b",
                        "SELECT TOP " + query.limit());
            }
        }

        // Apply ORDER BY
        if (query.orderBy() != null && !query.orderBy().isEmpty()) {
            StringBuilder orderClause = new StringBuilder(" ORDER BY ");
            for (int i = 0; i < query.orderBy().size(); i++) {
                SortOrder so = query.orderBy().get(i);
                if (i > 0) orderClause.append(", ");
                orderClause.append("c.").append(so.field()).append(" ").append(so.direction().name());
            }
            result = result + orderClause;
        }

        return result;
    }

    /**
     * Logs per-operation diagnostics from a {@link CosmosItemResponse} at DEBUG
     * level: activity ID (request correlation), request charge (RU cost), and
     * HTTP status code.
     */
    private void logItemDiagnostics(String operation, ResourceAddress address,
            CosmosItemResponse<?> response) {
        CosmosDiagnosticsLogger.logItem(operation, address, response);
    }

    private void logFeedDiagnostics(String operation, ResourceAddress address,
            FeedResponse<?> page, int itemCount) {
        CosmosDiagnosticsLogger.logFeed(operation, address, page, itemCount);
    }

    private void logExceptionDiagnostics(String operation, ResourceAddress address,
            CosmosException e) {
        CosmosDiagnosticsLogger.logException(operation, address, e);
    }

    /**
     * Builds {@link OperationDiagnostics} from a {@link FeedResponse}, logs them
     * at DEBUG level, and logs full native diagnostics when opted-in via config.
     */
    private OperationDiagnostics buildFeedDiagnostics(String operation, ResourceAddress address,
            FeedResponse<?> page, int itemCount, java.time.Duration duration) {
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.COSMOS, operation, duration)
                .requestCharge(page.getRequestCharge())
                .itemCount(itemCount)
                .build();

        LOG.debug("cosmos.diagnostics op={} db={} col={} requestCharge={} itemCount={} hasMore={}",
                operation, address.database(), address.collection(),
                diag.requestCharge(), itemCount, page.getContinuationToken() != null);

        if (config.nativeDiagnosticsEnabled()) {
            CosmosDiagnostics native_ = page.getCosmosDiagnostics();
            if (native_ != null) {
                LOG.info("cosmos.native-diagnostics op={} db={} col={} details={}",
                        operation, address.database(), address.collection(), native_);
            }
        }
        return diag;
    }

    /**
     * Converts a caller-supplied {@code Map<String, Object>} document into a Jackson
     * {@link ObjectNode} suitable for Cosmos SDK write calls.
     * Jackson is used as a private implementation detail and does not appear on the
     * public API surface.
     *
     * @param document the document payload
     * @return an {@link ObjectNode} representation of the document
     */
    private ObjectNode toObjectNode(Map<String, Object> document) {
        return MAPPER.convertValue(document, ObjectNode.class);
    }


    /**
     * Converts a Cosmos SDK {@link JsonNode} response item into a plain
     * {@code Map<String, Object>} for return on the public API surface.
     *
     * @param node the JSON node returned by the Cosmos SDK; may be {@code null}
     * @return a map representation, or {@code null} if {@code node} is {@code null}
     */
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null) return null;
        return MAPPER.convertValue(node, MAP_TYPE);
    }
}

