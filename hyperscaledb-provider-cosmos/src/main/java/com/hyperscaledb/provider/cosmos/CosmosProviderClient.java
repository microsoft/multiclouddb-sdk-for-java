package com.hyperscaledb.provider.cosmos;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.cosmos.CosmosManager;
import com.azure.resourcemanager.cosmos.models.SqlDatabaseCreateUpdateParameters;
import com.azure.resourcemanager.cosmos.models.SqlDatabaseResource;
import com.azure.resourcemanager.cosmos.models.SqlContainerCreateUpdateParameters;
import com.azure.resourcemanager.cosmos.models.SqlContainerResource;
import com.azure.resourcemanager.cosmos.models.ContainerPartitionKey;
import com.hyperscaledb.api.*;
import com.hyperscaledb.api.OperationDiagnostics;
import com.hyperscaledb.api.OperationNames;
import com.hyperscaledb.api.query.TranslatedQuery;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
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
public class CosmosProviderClient implements HyperscaleDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosProviderClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CosmosClient cosmosClient;
    private final HyperscaleDbClientConfig config;

    // Management-plane client for provisioning (non-null only in cloud/RBAC mode)
    private final CosmosManager cosmosManager;
    private final String resourceGroupName;
    private final String accountName;

    public CosmosProviderClient(HyperscaleDbClientConfig config) {
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
            this.cosmosManager    = null;
            this.resourceGroupName = null;
            this.accountName       = null;
        } else {
            String tenantId = config.connection().get(CosmosConstants.CONFIG_TENANT_ID);
            DefaultAzureCredentialBuilder credentialBuilder = new DefaultAzureCredentialBuilder();
            if (tenantId != null && !tenantId.isBlank()) {
                credentialBuilder.tenantId(tenantId);
            }
            TokenCredential credential = credentialBuilder.build();
            builder.credential(credential);
            LOG.info("Cosmos client using DefaultAzureCredential (supports Managed Identity, Azure CLI, environment variables)");

            String subscriptionId   = config.connection().get(CosmosConstants.CONFIG_SUBSCRIPTION_ID);
            this.resourceGroupName  = config.connection().get(CosmosConstants.CONFIG_RESOURCE_GROUP);
            this.accountName        = extractAccountName(endpoint);

            if (subscriptionId != null && resourceGroupName != null && accountName != null) {
                AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
                this.cosmosManager = CosmosManager.authenticate(credential, profile);
                LOG.info("Cosmos management client initialised (subscription={}, rg={}, account={})",
                        subscriptionId, resourceGroupName, accountName);
            } else {
                this.cosmosManager = null;
                LOG.warn("Management SDK config incomplete (subscriptionId/resourceGroupName) — "
                        + "provisioning will fall back to data-plane calls");
            }
        }

        String connectionMode = config.connection().getOrDefault(
                CosmosConstants.CONFIG_CONNECTION_MODE, CosmosConstants.CONNECTION_MODE_DEFAULT);
        if (CosmosConstants.CONNECTION_MODE_DIRECT.equalsIgnoreCase(connectionMode)) {
            builder.directMode();
        } else {
            builder.gatewayMode();
        }

        this.cosmosClient = builder.buildClient();
        LOG.info("Cosmos client created for endpoint: {}", endpoint);
    }

    @Override
    public void create(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = document.isObject() ? (ObjectNode) document.deepCopy() : MAPPER.createObjectNode();
            doc.put(CosmosConstants.FIELD_ID, key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            PartitionKey pk = resolvePartitionKey(key);
            java.time.Instant start = java.time.Instant.now();
            CosmosItemResponse<ObjectNode> response = container.createItem(doc, pk, new CosmosItemRequestOptions());
            buildItemDiagnostics(OperationNames.CREATE, address, response,
                    java.time.Duration.between(start, java.time.Instant.now()));
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.CREATE);
        }
    }

    @Override
    public JsonNode read(ResourceAddress address, Key key, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            java.time.Instant start = java.time.Instant.now();
            CosmosItemResponse<JsonNode> response = container.readItem(cosmosId, pk, JsonNode.class);
            buildItemDiagnostics(OperationNames.READ, address, response,
                    java.time.Duration.between(start, java.time.Instant.now()));
            return response.getItem();
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw CosmosErrorMapper.map(e, OperationNames.READ);
        }
    }

    @Override
    public void update(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = document.isObject() ? (ObjectNode) document.deepCopy() : MAPPER.createObjectNode();
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            doc.put(CosmosConstants.FIELD_ID, cosmosId);
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            PartitionKey pk = resolvePartitionKey(key);
            java.time.Instant start = java.time.Instant.now();
            CosmosItemResponse<ObjectNode> response = container.replaceItem(doc, cosmosId, pk, new CosmosItemRequestOptions());
            buildItemDiagnostics(OperationNames.UPDATE, address, response,
                    java.time.Duration.between(start, java.time.Instant.now()));
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    @Override
    public void upsert(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            ObjectNode doc = document.isObject() ? (ObjectNode) document.deepCopy() : MAPPER.createObjectNode();
            doc.put(CosmosConstants.FIELD_ID, key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put(CosmosConstants.FIELD_PARTITION_KEY, key.partitionKey());
            PartitionKey pk = resolvePartitionKey(key);
            java.time.Instant start = java.time.Instant.now();
            CosmosItemResponse<ObjectNode> response = container.upsertItem(doc, pk, new CosmosItemRequestOptions());
            buildItemDiagnostics(OperationNames.UPSERT, address, response,
                    java.time.Duration.between(start, java.time.Instant.now()));
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.UPSERT);
        }
    }

    @Override
    public void delete(ResourceAddress address, Key key, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            java.time.Instant start = java.time.Instant.now();
            CosmosItemResponse<Object> response = container.deleteItem(cosmosId, pk, new CosmosItemRequestOptions());
            buildItemDiagnostics(OperationNames.DELETE, address, response,
                    java.time.Duration.between(start, java.time.Instant.now()));
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return;
            }
            throw CosmosErrorMapper.map(e, OperationNames.DELETE);
        }
    }

    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            CosmosQueryRequestOptions queryOptions = new CosmosQueryRequestOptions();
            if (query.partitionKey() != null) {
                queryOptions.setPartitionKey(new PartitionKey(query.partitionKey()));
            }
            if (query.pageSize() != null) {
                queryOptions.setMaxBufferedItemCount(query.pageSize());
            }

            String expression = query.nativeExpression() != null ? query.nativeExpression() : query.expression();
            if (expression == null || expression.isBlank()) {
                expression = CosmosConstants.QUERY_SELECT_ALL;
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
            int pageSize = query.pageSize() != null ? query.pageSize() : CosmosConstants.PAGE_SIZE_DEFAULT;
            List<JsonNode> items = new ArrayList<>();
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
                items.addAll(page.getResults());
                continuationToken = page.getContinuationToken();
                OperationDiagnostics diag = buildFeedDiagnostics(OperationNames.QUERY, address, page,
                        items.size(), java.time.Duration.between(queryStart, java.time.Instant.now()));
                return new QueryPage(items, continuationToken, diag);
            }

            return new QueryPage(items, continuationToken);
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            CosmosQueryRequestOptions queryOptions = new CosmosQueryRequestOptions();
            if (query.partitionKey() != null) {
                queryOptions.setPartitionKey(new PartitionKey(query.partitionKey()));
            }
            if (query.pageSize() != null) {
                queryOptions.setMaxBufferedItemCount(query.pageSize());
            }

            List<SqlParameter> sqlParams = new ArrayList<>();
            for (Map.Entry<String, Object> entry : translated.namedParameters().entrySet()) {
                sqlParams.add(new SqlParameter(entry.getKey(), entry.getValue()));
            }

            SqlQuerySpec sqlQuery = new SqlQuerySpec(translated.queryString(), sqlParams);
            int pageSize = query.pageSize() != null ? query.pageSize() : CosmosConstants.PAGE_SIZE_DEFAULT;
            List<JsonNode> items = new ArrayList<>();
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
                items.addAll(page.getResults());
                continuationToken = page.getContinuationToken();
                OperationDiagnostics diag = buildFeedDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address,
                        page, items.size(), java.time.Duration.between(queryStart, java.time.Instant.now()));
                return new QueryPage(items, continuationToken, diag);
            }

            return new QueryPage(items, continuationToken);
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    @Override
    public CapabilitySet capabilities() {
        return CosmosCapabilities.CAPABILITIES;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T nativeClient(Class<T> clientType) {
        if (clientType.isInstance(cosmosClient)) {
            return (T) cosmosClient;
        }
        return null;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    public void close() {
        cosmosClient.close();
    }

    // ── Provisioning ────────────────────────────────────────────────────────

    @Override
    public void ensureDatabase(String database) {
        if (cosmosManager != null) {
            try {
                cosmosManager.serviceClient()
                        .getSqlResources()
                        .getSqlDatabase(resourceGroupName, accountName, database);
                LOG.info("Cosmos database already exists, skipping create: {}", database);
                return;
            } catch (com.azure.core.management.exception.ManagementException e) {
                if (e.getResponse() != null && e.getResponse().getStatusCode() != 404) {
                    throw e;
                }
            }
            cosmosManager.serviceClient()
                    .getSqlResources()
                    .createUpdateSqlDatabase(
                            resourceGroupName, accountName, database,
                            new SqlDatabaseCreateUpdateParameters()
                                    .withResource(new SqlDatabaseResource().withId(database)),
                            com.azure.core.util.Context.NONE);
            LOG.info("Ensured Cosmos database via management SDK: {}", database);
        } else {
            try {
                cosmosClient.createDatabaseIfNotExists(database);
                LOG.info("Ensured Cosmos database: {}", database);
            } catch (CosmosException e) {
                throw CosmosErrorMapper.map(e, OperationNames.ENSURE_DATABASE);
            }
        }
    }

    @Override
    public void ensureContainer(ResourceAddress address) {
        if (cosmosManager != null) {
            try {
                cosmosManager.serviceClient()
                        .getSqlResources()
                        .getSqlContainer(resourceGroupName, accountName,
                                address.database(), address.collection());
                LOG.info("Cosmos container already exists, skipping create: {}/{}",
                        address.database(), address.collection());
                return;
            } catch (com.azure.core.management.exception.ManagementException e) {
                if (e.getResponse() != null && e.getResponse().getStatusCode() != 404) {
                    throw e;
                }
            }
            cosmosManager.serviceClient()
                    .getSqlResources()
                    .createUpdateSqlContainer(
                            resourceGroupName, accountName,
                            address.database(), address.collection(),
                            new SqlContainerCreateUpdateParameters()
                                    .withResource(new SqlContainerResource()
                                            .withId(address.collection())
                                            .withPartitionKey(new ContainerPartitionKey()
                                                    .withPaths(List.of(CosmosConstants.PARTITION_KEY_PATH)))),
                            com.azure.core.util.Context.NONE);
            LOG.info("Ensured Cosmos container via management SDK: {}/{}",
                    address.database(), address.collection());
        } else {
            try {
                CosmosDatabase db = cosmosClient.getDatabase(address.database());
                CosmosContainerProperties props = new CosmosContainerProperties(
                        address.collection(), CosmosConstants.PARTITION_KEY_PATH);
                db.createContainerIfNotExists(props);
                LOG.info("Ensured Cosmos container: {}/{}", address.database(), address.collection());
            } catch (CosmosException e) {
                throw CosmosErrorMapper.map(e, OperationNames.ENSURE_CONTAINER);
            }
        }
    }


    private CosmosContainer getContainer(ResourceAddress address) {
        CosmosDatabase database = cosmosClient.getDatabase(address.database());
        return database.getContainer(address.collection());
    }

    private PartitionKey resolvePartitionKey(Key key) {
        return new PartitionKey(key.partitionKey());
    }

    /**
     * Builds {@link OperationDiagnostics} from a {@link CosmosItemResponse}, logs
     * them at DEBUG level, and — when {@code nativeDiagnosticsEnabled} is set in
     * config — logs the full {@link CosmosDiagnostics} string at INFO level.
     */
    private OperationDiagnostics buildItemDiagnostics(String operation, ResourceAddress address,
            CosmosItemResponse<?> response, java.time.Duration duration) {
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.COSMOS, operation, duration)
                .requestId(response.getActivityId())
                .statusCode(response.getStatusCode())
                .requestCharge(response.getRequestCharge())
                .etag(response.getETag())
                .sessionToken(response.getSessionToken())
                .build();

        LOG.debug("cosmos.diagnostics op={} db={} col={} activityId={} requestCharge={} statusCode={} etag={}",
                operation, address.database(), address.collection(),
                diag.requestId(), diag.requestCharge(), diag.statusCode(), diag.etag());

        if (config.nativeDiagnosticsEnabled()) {
            CosmosDiagnostics native_ = response.getDiagnostics();
            if (native_ != null) {
                LOG.info("cosmos.native-diagnostics op={} db={} col={} details={}",
                        operation, address.database(), address.collection(), native_);
            }
        }
        return diag;
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
     * Extracts the Cosmos account name from an endpoint URL.
     * E.g. {@code https://my-account.documents.azure.com:443/} →
     * {@code my-account}.
     */
    private static String extractAccountName(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            if (host != null && host.contains(".")) {
                return host.substring(0, host.indexOf('.'));
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        return null;
    }
}
