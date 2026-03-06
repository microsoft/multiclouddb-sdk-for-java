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
import com.hyperscaledb.api.*;
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
        String endpoint = config.connection().get("endpoint");
        String key = config.connection().get("key");

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Cosmos connection.endpoint is required");
        }

        CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(endpoint)
                .consistencyLevel(ConsistencyLevel.SESSION)
                .contentResponseOnWriteEnabled(true);

        // Authenticate: key-based if key is provided, otherwise DefaultAzureCredential
        if (key != null && !key.isBlank()) {
            builder.key(key);
            LOG.info("Cosmos client using key-based authentication");
            this.cosmosManager = null;
            this.resourceGroupName = null;
            this.accountName = null;
        } else {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            builder.credential(credential);
            LOG.info(
                    "Cosmos client using DefaultAzureCredential (supports Managed Identity, Azure CLI, environment variables)");

            // Initialise the ARM management client for provisioning (create DB /
            // containers)
            // because Cosmos data-plane RBAC does not support control-plane operations.
            String subscriptionId = config.connection().get("subscriptionId");
            this.resourceGroupName = config.connection().get("resourceGroupName");
            this.accountName = extractAccountName(endpoint);

            if (subscriptionId != null && resourceGroupName != null && accountName != null) {
                AzureProfile profile = new AzureProfile(null, subscriptionId, AzureEnvironment.AZURE);
                this.cosmosManager = CosmosManager.authenticate(credential, profile);
                LOG.info("Cosmos management client initialised (subscription={}, rg={}, account={})",
                        subscriptionId, resourceGroupName, accountName);
            } else {
                this.cosmosManager = null;
                LOG.warn("Management SDK config incomplete (subscriptionId/resourceGroupName) — "
                        + "provisioning will fall back to data-plane calls");
            }
        }

        // Use gateway mode for emulator compatibility; direct mode for production
        String connectionMode = config.connection().getOrDefault("connectionMode", "gateway");
        if ("direct".equalsIgnoreCase(connectionMode)) {
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

            // Merge key fields into document
            ObjectNode doc = document.isObject() ? (ObjectNode) document.deepCopy() : MAPPER.createObjectNode();
            doc.put("id", key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put("partitionKey", key.partitionKey());

            PartitionKey pk = resolvePartitionKey(key);
            container.createItem(doc, pk, new CosmosItemRequestOptions());
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "create");
        }
    }

    @Override
    public JsonNode read(ResourceAddress address, Key key, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);

            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            CosmosItemResponse<JsonNode> response = container.readItem(
                    cosmosId, pk, JsonNode.class);
            return response.getItem();
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw CosmosErrorMapper.map(e, "read");
        }
    }

    @Override
    public void update(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);

            // Merge key fields into document
            ObjectNode doc = document.isObject() ? (ObjectNode) document.deepCopy() : MAPPER.createObjectNode();
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            doc.put("id", cosmosId);
            doc.put("partitionKey", key.partitionKey());

            PartitionKey pk = resolvePartitionKey(key);
            container.replaceItem(doc, cosmosId, pk, new CosmosItemRequestOptions());
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "update");
        }
    }

    @Override
    public void upsert(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);

            // Merge key fields into document
            ObjectNode doc = document.isObject() ? (ObjectNode) document.deepCopy() : MAPPER.createObjectNode();
            doc.put("id", key.sortKey() != null ? key.sortKey() : key.partitionKey());
            doc.put("partitionKey", key.partitionKey());

            PartitionKey pk = resolvePartitionKey(key);
            container.upsertItem(doc, pk, new CosmosItemRequestOptions());
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "upsert");
        }
    }

    @Override
    public void delete(ResourceAddress address, Key key, OperationOptions options) {
        try {
            CosmosContainer container = getContainer(address);
            PartitionKey pk = resolvePartitionKey(key);
            String cosmosId = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            container.deleteItem(cosmosId, pk, new CosmosItemRequestOptions());
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return; // Delete is idempotent
            }
            throw CosmosErrorMapper.map(e, "delete");
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

            // Native expression passthrough
            String expression = query.nativeExpression() != null ? query.nativeExpression() : query.expression();
            if (expression == null || expression.isBlank()) {
                expression = "SELECT * FROM c";
            }

            // Build SqlQuerySpec with parameters
            List<SqlParameter> sqlParams = new ArrayList<>();
            if (query.parameters() != null) {
                for (Map.Entry<String, Object> entry : query.parameters().entrySet()) {
                    String paramName = entry.getKey().startsWith("@") ? entry.getKey() : "@" + entry.getKey();
                    sqlParams.add(new SqlParameter(paramName, entry.getValue()));
                }
            }

            SqlQuerySpec sqlQuery = new SqlQuerySpec(expression, sqlParams);

            // Execute query with paging
            int pageSize = query.pageSize() != null ? query.pageSize() : 100;
            List<JsonNode> items = new ArrayList<>();
            String continuationToken = null;

            Iterable<FeedResponse<JsonNode>> pages;
            if (query.continuationToken() != null) {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(query.continuationToken(), pageSize);
            } else {
                pages = container.queryItems(sqlQuery, queryOptions, JsonNode.class)
                        .iterableByPage(pageSize);
            }

            // Take only the first page
            for (FeedResponse<JsonNode> page : pages) {
                items.addAll(page.getResults());
                continuationToken = page.getContinuationToken();
                break; // Only one page per call
            }

            return new QueryPage(items, continuationToken);
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "query");
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

            // Build SqlQuerySpec from translated query
            List<SqlParameter> sqlParams = new ArrayList<>();
            for (Map.Entry<String, Object> entry : translated.namedParameters().entrySet()) {
                sqlParams.add(new SqlParameter(entry.getKey(), entry.getValue()));
            }

            SqlQuerySpec sqlQuery = new SqlQuerySpec(translated.queryString(), sqlParams);

            int pageSize = query.pageSize() != null ? query.pageSize() : 100;
            List<JsonNode> items = new ArrayList<>();
            String continuationToken = null;

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
                break;
            }

            return new QueryPage(items, continuationToken);
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "query");
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
            cosmosManager.serviceClient()
                    .getSqlResources()
                    .createUpdateSqlDatabase(
                            resourceGroupName,
                            accountName,
                            database,
                            new SqlDatabaseCreateUpdateParameters()
                                    .withResource(new SqlDatabaseResource().withId(database)),
                            com.azure.core.util.Context.NONE);
            LOG.info("Ensured Cosmos database via management SDK: {}", database);
        } else {
            try {
                cosmosClient.createDatabaseIfNotExists(database);
                LOG.info("Ensured Cosmos database: {}", database);
            } catch (CosmosException e) {
                throw CosmosErrorMapper.map(e, "ensureDatabase");
            }
        }
    }

    @Override
    public void ensureContainer(ResourceAddress address) {
        try {
            CosmosDatabase db = cosmosClient.getDatabase(address.database());
            CosmosContainerProperties props = new CosmosContainerProperties(
                    address.collection(), PARTITION_KEY_PATH);
            db.createContainerIfNotExists(props);
            LOG.info("Ensured Cosmos container: {}/{}", address.database(), address.collection());
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "ensureContainer");
        }
    }

    /**
     * Cosmos partition key path (matches key.partitionKey() → "partitionKey"
     * field).
     */
    private static final String PARTITION_KEY_PATH = "/partitionKey";

    private CosmosContainer getContainer(ResourceAddress address) {
        CosmosDatabase database = cosmosClient.getDatabase(address.database());
        return database.getContainer(address.collection());
    }

    private PartitionKey resolvePartitionKey(Key key) {
        return new PartitionKey(key.partitionKey());
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
