package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.CapabilitySet;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.Key;
import com.hyperscaledb.api.OperationNames;
import com.hyperscaledb.api.OperationOptions;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.hyperscaledb.api.query.TranslatedQuery;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.net.URI;
import java.util.*;

/**
 * Amazon DynamoDB provider client implementing CRUD + scan/query with
 * pagination.
 * <p>
 * Connection config keys:
 * <ul>
 * <li>{@code region} - AWS region (e.g., "us-east-1")</li>
 * <li>{@code endpoint} - Optional custom endpoint (for DynamoDB Local)</li>
 * </ul>
 * Auth config keys:
 * <ul>
 * <li>{@code accessKeyId} - AWS access key</li>
 * <li>{@code secretAccessKey} - AWS secret key</li>
 * </ul>
 */
public class DynamoProviderClient implements HyperscaleDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoProviderClient.class);

    private final DynamoDbClient dynamoClient;

    public DynamoProviderClient(HyperscaleDbClientConfig config) {

        String region   = config.connection().getOrDefault(DynamoConstants.CONFIG_REGION, DynamoConstants.REGION_DEFAULT);
        String endpoint = config.connection().get(DynamoConstants.CONFIG_ENDPOINT);

        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region));

        // Custom endpoint for DynamoDB Local
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        // Explicit credentials (required for DynamoDB Local)
        String accessKey = config.auth().get(DynamoConstants.CONFIG_ACCESS_KEY_ID);
        String secretKey = config.auth().get(DynamoConstants.CONFIG_SECRET_ACCESS_KEY);
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        }

        this.dynamoClient = builder.build();
        LOG.info("DynamoDB client created for region: {}, endpoint: {}", region,
                endpoint != null ? endpoint : "default");
    }

    @Override
    public void create(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            Map<String, AttributeValue> item = DynamoItemMapper.jsonNodeToAttributeMap(document);
            item.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            item.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(resolveTableName(address))
                    .item(item)
                    .conditionExpression("attribute_not_exists(" + DynamoConstants.ATTR_PARTITION_KEY + ")")
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();

            PutItemResponse response = dynamoClient.putItem(request);
            logItemDiagnostics(OperationNames.CREATE, address, response.responseMetadata().requestId(),
                    response.consumedCapacity());
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.CREATE);
        }
    }

    @Override
    public JsonNode read(ResourceAddress address, Key key, OperationOptions options) {
        try {
            Map<String, AttributeValue> keyMap = new LinkedHashMap<>();
            keyMap.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            keyMap.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));

            GetItemRequest request = GetItemRequest.builder()
                    .tableName(resolveTableName(address))
                    .key(keyMap)
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();

            GetItemResponse response = dynamoClient.getItem(request);
            logItemDiagnostics(OperationNames.READ, address, response.responseMetadata().requestId(),
                    response.consumedCapacity());
            if (!response.hasItem() || response.item().isEmpty()) {
                return null;
            }
            return DynamoItemMapper.attributeMapToJsonNode(response.item());
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.READ);
        }
    }

    @Override
    public void update(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            Map<String, AttributeValue> item = DynamoItemMapper.jsonNodeToAttributeMap(document);
            item.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            item.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(resolveTableName(address))
                    .item(item)
                    .conditionExpression("attribute_exists(" + DynamoConstants.ATTR_PARTITION_KEY + ")")
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();

            PutItemResponse response = dynamoClient.putItem(request);
            logItemDiagnostics(OperationNames.UPDATE, address, response.responseMetadata().requestId(),
                    response.consumedCapacity());
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    @Override
    public void upsert(ResourceAddress address, Key key, JsonNode document, OperationOptions options) {
        try {
            Map<String, AttributeValue> item = DynamoItemMapper.jsonNodeToAttributeMap(document);
            item.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            item.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(resolveTableName(address))
                    .item(item)
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();

            PutItemResponse response = dynamoClient.putItem(request);
            logItemDiagnostics(OperationNames.UPSERT, address, response.responseMetadata().requestId(),
                    response.consumedCapacity());
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.UPSERT);
        }
    }

    @Override
    public void delete(ResourceAddress address, Key key, OperationOptions options) {
        try {
            Map<String, AttributeValue> keyMap = new LinkedHashMap<>();
            keyMap.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            keyMap.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));

            DeleteItemRequest request = DeleteItemRequest.builder()
                    .tableName(resolveTableName(address))
                    .key(keyMap)
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();

            DeleteItemResponse response = dynamoClient.deleteItem(request);
            logItemDiagnostics(OperationNames.DELETE, address, response.responseMetadata().requestId(),
                    response.consumedCapacity());
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.DELETE);
        }
    }

    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        try {
            String tableName = resolveTableName(address);
            int pageSize = query.pageSize() != null ? query.pageSize() : DynamoConstants.PAGE_SIZE_DEFAULT;

            // Deserialize continuation token if present
            Map<String, AttributeValue> exclusiveStartKey = null;
            if (query.continuationToken() != null && !query.continuationToken().isBlank()) {
                exclusiveStartKey = DynamoContinuationToken.decode(query.continuationToken());
            }

            // If nativeExpression is set, use PartiQL passthrough
            if (query.nativeExpression() != null && !query.nativeExpression().isBlank()) {
                String stmt = query.nativeExpression();
                Map<String, Object> params = query.parameters();
                if (query.partitionKey() != null) {
                    stmt = appendPartitionKeyCondition(stmt);
                    LinkedHashMap<String, Object> combined = new LinkedHashMap<>();
                    if (params != null) combined.putAll(params);
                    combined.put(DynamoConstants.QUERY_PARTITION_KEY_PARAM, query.partitionKey());
                    params = combined;
                }
                return executePartiQL(stmt, params, pageSize, query.continuationToken());
            }

            // If expression is null/blank or the generic "SELECT * FROM c", do full scan
            if (query.expression() == null || query.expression().isBlank()
                    || query.expression().trim().equalsIgnoreCase(DynamoConstants.QUERY_SELECT_ALL_COSMOS)) {
                if (query.partitionKey() != null) {
                    // Scope scan to items with matching partition key (hash key)
                    return executeScanWithFilter(tableName,
                            DynamoConstants.SCAN_PARTITION_KEY_CONDITION,
                            Map.of(DynamoConstants.QUERY_PARTITION_KEY_PARAM, query.partitionKey()),
                            pageSize, exclusiveStartKey);
                }
                return executeScan(tableName, pageSize, exclusiveStartKey);
            }

            // Legacy: use expression as Scan filter (backward compat)
            if (query.partitionKey() != null) {
                String filter = DynamoConstants.ATTR_PARTITION_KEY + " = " + DynamoConstants.SCAN_PARTITION_KEY_PARAM
                        + " AND " + query.expression();
                Map<String, Object> combined = new LinkedHashMap<>();
                if (query.parameters() != null) combined.putAll(query.parameters());
                combined.put(DynamoConstants.QUERY_PARTITION_KEY_PARAM, query.partitionKey());
                return executeScanWithFilter(tableName, filter, combined, pageSize, exclusiveStartKey);
            }
            return executeScanWithFilter(tableName, query.expression(), query.parameters(),
                    pageSize, exclusiveStartKey);
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    /**
     * Execute a query using a pre-translated TranslatedQuery (PartiQL).
     * Called from DefaultHyperscaleDbClient when portable expressions are used.
     */
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        try {
            int pageSize = query.pageSize() != null ? query.pageSize() : DynamoConstants.PAGE_SIZE_DEFAULT;
            List<AttributeValue> params = new ArrayList<>();
            for (Object val : translated.positionalParameters()) {
                params.add(DynamoItemMapper.toAttributeValue(val));
            }

            // The translated query uses address.collection() as the table name,
            // but resolveTableName() composes database__collection. Replace it.
            String resolvedTable = resolveTableName(address);
            String rawCollection = address.collection();
            String stmt = translated.queryString();
            if (!resolvedTable.equals(rawCollection)) {
                stmt = stmt.replace("\"" + rawCollection + "\"", "\"" + resolvedTable + "\"");
            }

            // Inject partition key scoping as a partitionKey equality condition
            if (query.partitionKey() != null) {
                stmt = appendPartitionKeyCondition(stmt);
                params.add(DynamoItemMapper.toAttributeValue(query.partitionKey()));
            }

            ExecuteStatementRequest.Builder stmtBuilder = ExecuteStatementRequest.builder()
                    .statement(stmt)
                    .limit(pageSize);

            if (!params.isEmpty()) stmtBuilder.parameters(params);
            if (query.continuationToken() != null && !query.continuationToken().isBlank()) {
                stmtBuilder.nextToken(query.continuationToken());
            }

            ExecuteStatementResponse response = dynamoClient.executeStatement(stmtBuilder.build());

            List<JsonNode> items = new ArrayList<>();
            for (Map<String, AttributeValue> item : response.items()) {
                items.add(DynamoItemMapper.attributeMapToJsonNode(item));
            }

            logQueryDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address,
                    response.responseMetadata().requestId(),
                    response.consumedCapacity(), items.size(), response.nextToken());

            return new QueryPage(items, response.nextToken());
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    private QueryPage executePartiQL(String statement, Map<String, Object> parameters,
            int pageSize, String nextToken) {
        List<AttributeValue> params = new ArrayList<>();
        if (parameters != null) {
            for (Object val : parameters.values()) {
                params.add(DynamoItemMapper.toAttributeValue(val));
            }
        }

        ExecuteStatementRequest.Builder stmtBuilder = ExecuteStatementRequest.builder()
                .statement(statement)
                .limit(pageSize);

        if (!params.isEmpty()) stmtBuilder.parameters(params);
        if (nextToken != null && !nextToken.isBlank()) stmtBuilder.nextToken(nextToken);

        ExecuteStatementResponse response = dynamoClient.executeStatement(stmtBuilder.build());

        List<JsonNode> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToJsonNode(item));
        }

        logQueryDiagnostics(DynamoConstants.OP_QUERY_PARTIQL, null,
                response.responseMetadata().requestId(),
                response.consumedCapacity(), items.size(), response.nextToken());

        return new QueryPage(items, response.nextToken());
    }

    private QueryPage executeScan(String tableName, int pageSize,
            Map<String, AttributeValue> exclusiveStartKey) {
        ScanRequest.Builder scanBuilder = ScanRequest.builder()
                .tableName(tableName)
                .limit(pageSize)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

        if (exclusiveStartKey != null) scanBuilder.exclusiveStartKey(exclusiveStartKey);

        ScanResponse response = dynamoClient.scan(scanBuilder.build());
        List<JsonNode> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToJsonNode(item));
        }

        String continuationToken = null;
        if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
            continuationToken = DynamoContinuationToken.encode(response.lastEvaluatedKey());
        }

        logQueryDiagnostics(DynamoConstants.OP_QUERY_SCAN, null,
                response.sdkHttpResponse().firstMatchingHeader(DynamoConstants.HEADER_REQUEST_ID).orElse(null),
                response.consumedCapacity(), items.size(), continuationToken);

        return new QueryPage(items, continuationToken);
    }

    private QueryPage executeScanWithFilter(String tableName, String filterExpression,
            Map<String, Object> parameters, int pageSize,
            Map<String, AttributeValue> exclusiveStartKey) {
        Map<String, AttributeValue> expressionValues = new LinkedHashMap<>();
        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                String paramName = entry.getKey().startsWith(DynamoConstants.FILTER_PARAM_PREFIX)
                        ? entry.getKey()
                        : DynamoConstants.FILTER_PARAM_PREFIX + entry.getKey();
                expressionValues.put(paramName, DynamoItemMapper.toAttributeValue(entry.getValue()));
            }
        }

        ScanRequest.Builder scanBuilder = ScanRequest.builder()
                .tableName(tableName)
                .filterExpression(filterExpression)
                .limit(pageSize)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

        if (!expressionValues.isEmpty()) scanBuilder.expressionAttributeValues(expressionValues);
        if (exclusiveStartKey != null) scanBuilder.exclusiveStartKey(exclusiveStartKey);

        ScanResponse response = dynamoClient.scan(scanBuilder.build());
        List<JsonNode> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToJsonNode(item));
        }

        String continuationToken = null;
        if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
            continuationToken = DynamoContinuationToken.encode(response.lastEvaluatedKey());
        }

        logQueryDiagnostics(DynamoConstants.OP_QUERY_SCAN_FILTER, null,
                response.sdkHttpResponse().firstMatchingHeader(DynamoConstants.HEADER_REQUEST_ID).orElse(null),
                response.consumedCapacity(), items.size(), continuationToken);

        return new QueryPage(items, continuationToken);
    }

    private String resolveTableName(ResourceAddress address) {
        return address.database() + DynamoConstants.TABLE_NAME_SEPARATOR + address.collection();
    }

    /**
     * Appends {@code AND "partitionKey" = ?} (or {@code WHERE "partitionKey" = ?})
     * to a PartiQL statement so the query is scoped to a single partition key value.
     */
    private String appendPartitionKeyCondition(String stmt) {
        if (stmt.toUpperCase().contains(DynamoConstants.SQL_WHERE)) {
            return stmt + " AND " + DynamoConstants.PARTIQL_PARTITION_KEY_CONDITION;
        }
        return stmt + " WHERE " + DynamoConstants.PARTIQL_PARTITION_KEY_CONDITION;
    }

    @Override
    public CapabilitySet capabilities() {
        return DynamoCapabilities.CAPABILITIES;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T nativeClient(Class<T> clientType) {
        if (clientType.isInstance(dynamoClient)) {
            return (T) dynamoClient;
        }
        return null;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.DYNAMO;
    }

    @Override
    public void close() {
        dynamoClient.close();
    }

    // ── Provisioning ────────────────────────────────────────────────────────

    /**
     * No-op — DynamoDB has no native database concept.
     * The database dimension is encoded into table names by
     * {@link #resolveTableName(ResourceAddress)}.
     */
    @Override
    public void ensureDatabase(String database) {
        LOG.debug("ensureDatabase is a no-op for DynamoDB (database={})", database);
    }

    @Override
    public void ensureContainer(ResourceAddress address) {
        String tableName = resolveTableName(address);
        try {
            TableStatus status = describeTableStatus(tableName);

            if (status == null) {
                createTableAndWait(tableName);
            } else if (status == TableStatus.ACTIVE) {
                LOG.info("DynamoDB table already exists and is ACTIVE: {}", tableName);
            } else if (status == TableStatus.CREATING || status == TableStatus.UPDATING) {
                LOG.info("DynamoDB table {} is in {} state, waiting for ACTIVE", tableName, status);
                waitForTableActive(tableName);
            } else if (status == TableStatus.DELETING) {
                LOG.info("DynamoDB table {} is DELETING, waiting for deletion to complete", tableName);
                try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(dynamoClient).build()) {
                    waiter.waitUntilTableNotExists(DescribeTableRequest.builder()
                            .tableName(tableName).build());
                }
                createTableAndWait(tableName);
            }
        } catch (ResourceInUseException e) {
            // Table already exists (race condition) - safe to ignore
            LOG.debug("DynamoDB table already exists (race): {}", tableName);
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.ENSURE_CONTAINER);
        }
    }

    private TableStatus describeTableStatus(String tableName) {
        try {
            DescribeTableResponse response = dynamoClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            return response.table().tableStatus();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private void createTableAndWait(String tableName) {
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(tableName)
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName(DynamoConstants.ATTR_PARTITION_KEY)
                                .keyType(KeyType.HASH)
                                .build(),
                        KeySchemaElement.builder()
                                .attributeName(DynamoConstants.ATTR_SORT_KEY)
                                .keyType(KeyType.RANGE)
                                .build())
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName(DynamoConstants.ATTR_PARTITION_KEY)
                                .attributeType(DynamoConstants.KEY_ATTRIBUTE_TYPE)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName(DynamoConstants.ATTR_SORT_KEY)
                                .attributeType(DynamoConstants.KEY_ATTRIBUTE_TYPE)
                                .build())
                .billingMode(DynamoConstants.TABLE_BILLING_MODE)
                .build();

        dynamoClient.createTable(request);
        LOG.info("Created DynamoDB table: {}, waiting for ACTIVE status", tableName);
        waitForTableActive(tableName);
    }

    private void waitForTableActive(String tableName) {
        try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(dynamoClient).build()) {
            waiter.waitUntilTableExists(DescribeTableRequest.builder()
                    .tableName(tableName).build());
        }
        LOG.info("DynamoDB table is ACTIVE: {}", tableName);
    }

    /**
     * Logs per-item-operation diagnostics at DEBUG level:
     * AWS request ID (correlation) and consumed capacity units.
     */
    private void logItemDiagnostics(String operation, ResourceAddress address,
            String requestId, ConsumedCapacity consumedCapacity) {
        if (LOG.isDebugEnabled()) {
            double capacityUnits = consumedCapacity != null && consumedCapacity.capacityUnits() != null
                    ? consumedCapacity.capacityUnits() : 0.0;
            LOG.debug("dynamo.diagnostics op={} db={} col={} requestId={} capacityUnits={}",
                    operation,
                    address.database(),
                    address.collection(),
                    requestId,
                    capacityUnits);
        }
    }

    /**
     * Logs per-query/scan diagnostics at DEBUG level:
     * AWS request ID, consumed capacity units, result count, and whether more
     * pages are available.
     */
    private void logQueryDiagnostics(String operation, ResourceAddress address,
            String requestId, ConsumedCapacity consumedCapacity,
            int itemCount, String nextToken) {
        if (LOG.isDebugEnabled()) {
            double capacityUnits = consumedCapacity != null && consumedCapacity.capacityUnits() != null
                    ? consumedCapacity.capacityUnits() : 0.0;
            String db  = address != null ? address.database()    : "-";
            String col = address != null ? address.collection()  : "-";
            LOG.debug("dynamo.diagnostics op={} db={} col={} requestId={} capacityUnits={} itemCount={} hasMore={}",
                    operation, db, col, requestId, capacityUnits, itemCount, nextToken != null);
        }
    }
}
