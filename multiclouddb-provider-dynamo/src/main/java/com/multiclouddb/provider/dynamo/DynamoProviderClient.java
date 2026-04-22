// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentMetadata;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.spi.SdkUserAgent;
import com.multiclouddb.api.query.TranslatedQuery;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
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
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;
import software.amazon.awssdk.http.SdkHttpResponse;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.Comparator;

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
public class DynamoProviderClient implements MulticloudDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoProviderClient.class);

    private final MulticloudDbClientConfig config;
    private final DynamoDbClient dynamoClient;

    /**
     * Constructs a DynamoDB provider client from the supplied configuration.
     * <p>
     * Authentication is selected automatically:
     * <ul>
     *   <li>If {@code auth.accessKeyId} and {@code auth.secretAccessKey} are both
     *       present, {@link StaticCredentialsProvider} is used. This is required for
     *       DynamoDB Local and useful when credentials cannot be sourced from the
     *       environment.</li>
     *   <li>Otherwise the AWS SDK's default credential provider chain is used
     *       (environment variables, {@code ~/.aws/credentials}, IAM role, etc.).</li>
     * </ul>
     * If {@code connection.endpoint} is set, it overrides the AWS regional endpoint —
     * use this to point at DynamoDB Local ({@code http://localhost:8000}).
     *
     * @param config client configuration carrying connection, auth, and options
     */
    public DynamoProviderClient(MulticloudDbClientConfig config) {
        this.config = config;
        String region   = config.connection().getOrDefault(DynamoConstants.CONFIG_REGION, DynamoConstants.REGION_DEFAULT);
        String endpoint = config.connection().get(DynamoConstants.CONFIG_ENDPOINT);

        // NOTE: DynamoDbClientBuilder.overrideConfiguration(...) fully REPLACES any prior override
        // configuration on the builder. When adding new overrides (retry policy, API call timeout,
        // metric publishers, additional advanced options, etc.), append them to the same
        // ClientOverrideConfiguration.builder() chain below rather than making a second
        // .overrideConfiguration(...) call, which would clobber the user-agent suffix.
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX,
                                        SdkUserAgent.userAgent(config))
                                .build());

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

    /** Package-private constructor for testing — injects a pre-configured {@link DynamoDbClient}. */
    DynamoProviderClient(DynamoDbClient dynamoClient) {
        this.dynamoClient = dynamoClient;
        this.config = MulticloudDbClientConfig.builder()
                .provider(com.multiclouddb.api.ProviderId.DYNAMO)
                .build();
    }

    /**
     * Inserts a new item into the DynamoDB table that corresponds to the given address.
     * <p>
     * The physical table name is composed as
     * {@code address.database() + "__" + address.collection()} (see
     * {@link DynamoConstants#TABLE_NAME_SEPARATOR}).
     * <p>
     * Before writing, two key attributes are injected (or overwritten) in the item:
     * <ul>
     *   <li>{@code partitionKey} — set to {@code key.partitionKey()} (hash key).</li>
     *   <li>{@code sortKey} — set to {@code key.sortKey()} if present, otherwise
     *       {@code key.partitionKey()} (range key).</li>
     * </ul>
     * A {@code attribute_not_exists(partitionKey)} condition expression is applied so
     * that the call fails with
     * {@link com.multiclouddb.api.MulticloudDbErrorCategory#CONFLICT} if an item with
     * the same key already exists.
     *
     * @param address  the logical database + collection (maps to a DynamoDB table)
     * @param key      the document key; {@code partitionKey} is required, {@code sortKey} is optional
     * @param document the document payload; all entries are stored as DynamoDB attributes
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException category {@code CONFLICT} if the
     *         item already exists, or any other mapped DynamoDB error
     */
    @Override
    public void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        try {
            Map<String, AttributeValue> item = DynamoItemMapper.mapToAttributeMap(document);
            item.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            item.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));
            if (options != null && options.ttlSeconds() != null) {
                long expiryEpoch = Instant.now().getEpochSecond() + options.ttlSeconds();
                item.put(DynamoConstants.ATTR_TTL_EXPIRY, AttributeValue.fromN(String.valueOf(expiryEpoch)));
            }

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

    /**
     * Retrieves a single item from DynamoDB by its composite key.
     * <p>
     * Uses a {@code GetItem} request (a direct key-lookup), which is the most
     * efficient read path in DynamoDB. Both the hash key ({@code partitionKey}) and
     * range key ({@code sortKey}) are required for the lookup; if {@code key.sortKey()}
     * is absent, {@code key.partitionKey()} is used for both attributes.
     *
     * @param address the logical database + collection
     * @param key     the document key
     * @param options operation options (currently unused by this provider)
     * @return the item as a {@code Map<String, Object>}, or {@code null} if not found
     * @throws com.multiclouddb.api.MulticloudDbException on any DynamoDB error
     */
    @Override
    public DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
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
            JsonNode rawDoc = DynamoItemMapper.attributeMapToJsonNode(response.item());
            if (!(rawDoc instanceof ObjectNode doc)) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.PROVIDER_ERROR,
                        "DynamoItemMapper.attributeMapToJsonNode returned a non-ObjectNode: "
                                + rawDoc.getClass().getSimpleName(),
                        ProviderId.DYNAMO, OperationNames.READ, false, null));
            }

            DocumentMetadata metadata = null;
            if (options != null && options.includeMetadata()) {
                DocumentMetadata.Builder metaBuilder = DocumentMetadata.builder();
                // Extract TTL expiry if the attribute is present on the item.
                JsonNode ttlNode = doc.get(DynamoConstants.ATTR_TTL_EXPIRY);
                if (ttlNode != null && ttlNode.isNumber()) {
                    metaBuilder.ttlExpiry(Instant.ofEpochSecond(ttlNode.longValue()));
                }
                metadata = metaBuilder.build();
            }
            return new DocumentResult(doc, metadata);
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.READ);
        }
    }

    /**
     * Replaces an existing item in DynamoDB (conditional put).
     * <p>
     * Uses a {@code PutItem} with an {@code attribute_exists(partitionKey)} condition
     * expression. If no item with the given key exists, the operation fails with
     * {@link com.multiclouddb.api.MulticloudDbErrorCategory#CONFLICT} (mapped from
     * DynamoDB's {@code ConditionalCheckFailedException}).
     *
     * @param address  the logical database + collection
     * @param key      the document key identifying the item to replace
     * @param document the new document payload; replaces all attributes of the stored item
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException category {@code NOT_FOUND} if the
     *         item does not exist
     */
    @Override
    public void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        try {
            Map<String, AttributeValue> item = DynamoItemMapper.mapToAttributeMap(document);
            item.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            item.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));
            if (options != null && options.ttlSeconds() != null) {
                long expiryEpoch = Instant.now().getEpochSecond() + options.ttlSeconds();
                item.put(DynamoConstants.ATTR_TTL_EXPIRY, AttributeValue.fromN(String.valueOf(expiryEpoch)));
            }

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(resolveTableName(address))
                    .item(item)
                    .conditionExpression("attribute_exists(" + DynamoConstants.ATTR_PARTITION_KEY + ")")
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();

            PutItemResponse response = dynamoClient.putItem(request);
            logItemDiagnostics(OperationNames.UPDATE, address, response.responseMetadata().requestId(),
                    response.consumedCapacity());
        } catch (ConditionalCheckFailedException e) {
            // attribute_exists() guard failed — item does not exist; map to NOT_FOUND
            // to match the contract and the behaviour of Cosmos (HTTP 404) and Spanner (NOT_FOUND).
            Map<String, String> details = new java.util.LinkedHashMap<>();
            if (e.awsErrorDetails() != null) {
                details.put("errorCode", e.awsErrorDetails().errorCode());
            }
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.NOT_FOUND,
                    "Item not found for update: " + e.getMessage(),
                    ProviderId.DYNAMO,
                    OperationNames.UPDATE,
                    false,
                    e.statusCode(),
                    details), e);
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    /**
     * Creates or replaces an item in DynamoDB (unconditional put / upsert semantics).
     * <p>
     * Uses a {@code PutItem} without any condition expression, so the item is
     * written regardless of whether it already exists. The key attributes
     * ({@code partitionKey}, {@code sortKey}) are injected before the write.
     *
     * @param address  the logical database + collection
     * @param key      the document key
     * @param document the document payload
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any DynamoDB error
     */
    @Override
    public void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        try {
            Map<String, AttributeValue> item = DynamoItemMapper.mapToAttributeMap(document);
            item.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(key.partitionKey()));
            item.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(
                    key.sortKey() != null ? key.sortKey() : key.partitionKey()));
            if (options != null && options.ttlSeconds() != null) {
                long expiryEpoch = Instant.now().getEpochSecond() + options.ttlSeconds();
                item.put(DynamoConstants.ATTR_TTL_EXPIRY, AttributeValue.fromN(String.valueOf(expiryEpoch)));
            }

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

    /**
     * Deletes an item from DynamoDB by its composite key.
     * <p>
     * Uses a {@code DeleteItem} request. The operation succeeds silently if the item
     * does not exist — delete is idempotent in DynamoDB.
     *
     * @param address the logical database + collection
     * @param key     the document key identifying the item to delete
     * @param options operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any DynamoDB error
     */
    @Override
    public void delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
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

    /**
     * Executes a query and returns a single page of results.
     * <p>
     * Query routing logic (evaluated in order):
     * <ol>
     *   <li><b>Native PartiQL passthrough</b> — if {@link QueryRequest#nativeExpression()}
     *       is set, the statement is executed via {@code ExecuteStatement} as-is.</li>
     *   <li><b>Full scan</b> — if expression is null/blank or equals the Cosmos-style
     *       {@code "SELECT * FROM c"} sentinel, a DynamoDB {@code Scan} is performed
     *       across the whole table (or partition-scoped if
     *       {@link QueryRequest#partitionKey()} is set).</li>
     *   <li><b>Filtered scan</b> — otherwise the expression is used as a DynamoDB
     *       {@code Scan} filter expression (backward-compatible legacy path).</li>
     * </ol>
     * Pagination is handled via {@link DynamoContinuationToken}: the opaque
     * {@code exclusiveStartKey} is encoded/decoded as a Base64 JSON string.
     * <p>
     * If {@link QueryRequest#partitionKey()} is set, the hash key equality condition
     * is automatically appended to scope the operation to a single partition.
     *
     * @param address the logical database + collection (maps to a DynamoDB table)
     * @param query   query request containing expression, parameters, page size, and
     *                optional continuation token
     * @param options operation options (currently unused by this provider)
     * @return a page of results; {@link QueryPage#continuationToken()} is non-null when
     *         more pages are available
     * @throws com.multiclouddb.api.MulticloudDbException on any DynamoDB error
     */
    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        try {
            validateResultSetControl(query, OperationNames.QUERY);
            String tableName = resolveTableName(address);
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : DynamoConstants.PAGE_SIZE_DEFAULT;
            // Respect Top N limit: cap the page size to avoid over-fetching
            if (query.limit() != null) {
                pageSize = Math.min(pageSize, query.limit());
            }

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

            // If expression is null/blank or the generic "SELECT * FROM c":
            // - With partitionKey: use DynamoDB Query API (O(partition size)) not Scan+Filter (O(table size))
            // - Without partitionKey: full-table scan (no alternative)
            if (query.expression() == null || query.expression().isBlank()
                    || query.expression().trim().equalsIgnoreCase(DynamoConstants.QUERY_SELECT_ALL_COSMOS)) {
                if (query.partitionKey() != null) {
                    return executeQueryByPartitionKey(address, tableName, query.partitionKey(),
                            null, null, pageSize, exclusiveStartKey);
                }
                return executeScan(tableName, pageSize, exclusiveStartKey);
            }

            // Legacy expression path:
            // - With partitionKey: Query API with KeyConditionExpression + FilterExpression
            // - Without partitionKey: Scan with FilterExpression (no key to scope on)
            if (query.partitionKey() != null) {
                return executeQueryByPartitionKey(address, tableName, query.partitionKey(),
                        query.expression(), query.parameters(), pageSize, exclusiveStartKey);
            }
            return executeScanWithFilter(tableName, query.expression(), query.parameters(),
                    pageSize, exclusiveStartKey);
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    /**
     * Executes a pre-translated portable query using DynamoDB PartiQL and returns a
     * single page of results.
     * <p>
     * Called by {@link com.multiclouddb.api.internal.DefaultMulticloudDbClient} after
     * the portable expression has been parsed, validated, and translated into a PartiQL
     * statement by {@link DynamoExpressionTranslator}.
     * <p>
     * The translated query uses {@code address.collection()} as the table name; this
     * method replaces that with the fully-resolved
     * {@code database__collection} name before execution.
     * <p>
     * Positional parameters from {@link TranslatedQuery#positionalParameters()} are
     * converted to DynamoDB {@link AttributeValue}s and bound in order. If
     * {@link QueryRequest#partitionKey()} is set, a trailing partition key equality
     * condition is appended and its value is added as the last positional parameter.
     *
     * @param address    the logical database + collection
     * @param translated the PartiQL statement and positional parameters produced by the
     *                   expression translator
     * @param query      the original query request (used for page size, continuation
     *                   token, and partition key)
     * @param options    operation options (currently unused by this provider)
     * @return a page of results with an optional continuation token
     * @throws com.multiclouddb.api.MulticloudDbException on any DynamoDB error
     */
    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        try {
            validateResultSetControl(query, OperationNames.QUERY_WITH_TRANSLATION);
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : DynamoConstants.PAGE_SIZE_DEFAULT;
            // Respect Top N limit
            if (query.limit() != null) {
                pageSize = Math.min(pageSize, query.limit());
            }
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

            java.time.Instant queryStart = java.time.Instant.now();
            ExecuteStatementResponse response = dynamoClient.executeStatement(stmtBuilder.build());

            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, AttributeValue> item : response.items()) {
                items.add(DynamoItemMapper.attributeMapToMap(item));
            }

            // PartiQL ExecuteStatement returns items in undefined order for scans.
            // Sort by sort key (ascending) to match the implicit ordering of
            // DynamoDB Query within a partition and the Cosmos provider's default
            // ORDER BY c.id ASC. Ordering applies within this page only; see
            // sortBySortKeyAsc() for the multi-page limitation note.
            items.sort(SORT_KEY_ASC);

            OperationDiagnostics diag = buildQueryDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address,
                    response.responseMetadata().requestId(),
                    response.consumedCapacity(), items.size(), response.nextToken(),
                    java.time.Duration.between(queryStart, java.time.Instant.now()), response.sdkHttpResponse());

            return new QueryPage(items, response.nextToken(), diag);
        } catch (DynamoDbException e) {
            throw DynamoErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    /**
     * Executes a native PartiQL {@code ExecuteStatement} and returns a page of results.
     * <p>
     * Parameter values are extracted from the supplied map in insertion order and
     * converted to DynamoDB {@link AttributeValue}s. The DynamoDB
     * {@code nextToken} is used for pagination (not the SDK-level
     * {@link DynamoContinuationToken} Base64 format used by Scan).
     *
     * @param statement  the PartiQL statement string
     * @param parameters query parameters (values converted to {@link AttributeValue}s in
     *                   insertion order)
     * @param pageSize   maximum number of items to return
     * @param nextToken  DynamoDB pagination token from a previous call, or {@code null}
     * @return a page of results
     */
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

        java.time.Instant partiqlStart = java.time.Instant.now();
        ExecuteStatementResponse response = dynamoClient.executeStatement(stmtBuilder.build());

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToMap(item));
        }

        OperationDiagnostics partiqlDiag = buildQueryDiagnostics(DynamoConstants.OP_QUERY_PARTIQL, null,
                response.responseMetadata().requestId(),
                response.consumedCapacity(), items.size(), response.nextToken(),
                java.time.Duration.between(partiqlStart, java.time.Instant.now()), response.sdkHttpResponse());

        return new QueryPage(items, response.nextToken(), partiqlDiag);
    }

    /**
     * Executes a DynamoDB <em>Query</em> scoped to a single partition (hash key) using
     * {@code KeyConditionExpression}. This is O(partition size), compared to
     * {@link #executeScanWithFilter} which performs a full-table Scan and is O(table size).
     *
     * <p>If {@code filterExpression} is provided it is applied as a {@code FilterExpression}
     * <em>after</em> the key-condition lookup; the caller must not include partition key
     * conditions inside {@code filterExpression} — those are handled by
     * {@link DynamoConstants#KEY_CONDITION_EXPRESSION} automatically.
     *
     * @param address            resource address used for diagnostics logging
     * @param tableName          resolved DynamoDB table name
     * @param partitionKeyValue  the partition key value to scope the query to
     * @param filterExpression   optional additional filter expression; {@code null} for none
     * @param filterParameters   expression attribute values for {@code filterExpression};
     *                           {@code null} if no filter expression is provided
     * @param pageSize           maximum number of items per page
     * @param exclusiveStartKey  decoded continuation token for pagination; {@code null} for first page
     * @return a {@link QueryPage} containing matching items and an optional continuation token
     */
    private QueryPage executeQueryByPartitionKey(ResourceAddress address, String tableName, String partitionKeyValue,
            String filterExpression, Map<String, Object> filterParameters,
            int pageSize, Map<String, AttributeValue> exclusiveStartKey) {
        Map<String, AttributeValue> expressionValues = new LinkedHashMap<>();
        expressionValues.put(DynamoConstants.KEY_CONDITION_PK_PARAM,
                AttributeValue.fromS(partitionKeyValue));

        if (filterParameters != null) {
            for (Map.Entry<String, Object> entry : filterParameters.entrySet()) {
                String paramName = entry.getKey().startsWith(DynamoConstants.FILTER_PARAM_PREFIX)
                        ? entry.getKey()
                        : DynamoConstants.FILTER_PARAM_PREFIX + entry.getKey();
                if (!DynamoConstants.KEY_CONDITION_PK_PARAM.equals(paramName)) {
                    expressionValues.put(paramName, DynamoItemMapper.toAttributeValue(entry.getValue()));
                }
            }
        }

        // DynamoDB's QueryRequest conflicts with com.multiclouddb.api.QueryRequest by name;
        // Java has no import aliases, so the FQCN is required for the SDK type.
        // `var` is used to avoid repeating it on the left-hand side.
        var queryBuilder = software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder()
                        .tableName(tableName)
                        .keyConditionExpression(DynamoConstants.KEY_CONDITION_EXPRESSION)
                        .expressionAttributeValues(expressionValues)
                        .limit(pageSize)
                        .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

        if (filterExpression != null && !filterExpression.isBlank()) {
            queryBuilder.filterExpression(filterExpression);
        }
        if (exclusiveStartKey != null) {
            queryBuilder.exclusiveStartKey(exclusiveStartKey);
        }

        java.time.Instant keyQueryStart = java.time.Instant.now();
        QueryResponse response = dynamoClient.query(queryBuilder.build());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToMap(item));
        }

        String continuationToken = null;
        if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
            continuationToken = DynamoContinuationToken.encode(response.lastEvaluatedKey());
        }

        OperationDiagnostics keyCondDiag = buildQueryDiagnostics(DynamoConstants.OP_QUERY_KEY_CONDITION, address,
                response.sdkHttpResponse().firstMatchingHeader(DynamoConstants.HEADER_REQUEST_ID).orElse(null),
                response.consumedCapacity(), items.size(), continuationToken,
                java.time.Duration.between(keyQueryStart, java.time.Instant.now()), response.sdkHttpResponse());

        return new QueryPage(items, continuationToken, keyCondDiag);
    }

    /**
     * Executes a full-table DynamoDB {@code Scan} with no filter and returns a page of
     * results.
     * <p>
     * Pagination uses {@code exclusiveStartKey} (decoded from the portable
     * {@link DynamoContinuationToken}) and encodes the returned
     * {@code lastEvaluatedKey} back into the portable token format.
     *
     * @param tableName        the physical DynamoDB table name
     * @param pageSize         maximum number of items to return
     * @param exclusiveStartKey the DynamoDB exclusive start key for pagination, or
     *                         {@code null} for the first page
     * @return a page of results
     */
    private QueryPage executeScan(String tableName, int pageSize,
            Map<String, AttributeValue> exclusiveStartKey) {
        ScanRequest.Builder scanBuilder = ScanRequest.builder()
                .tableName(tableName)
                .limit(pageSize)
                .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL);

        if (exclusiveStartKey != null) scanBuilder.exclusiveStartKey(exclusiveStartKey);

        java.time.Instant scanStart = java.time.Instant.now();
        ScanResponse response = dynamoClient.scan(scanBuilder.build());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToMap(item));
        }

        // DynamoDB Scan returns items in undefined hash-key order. Sort by sort key
        // (ascending) to match the implicit ordering of DynamoDB Query within a
        // partition and the Cosmos provider's default ORDER BY c.id ASC. Ordering
        // applies within this page only; see sortBySortKeyAsc() for the multi-page
        // limitation note.
        items.sort(SORT_KEY_ASC);

        String continuationToken = null;
        if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
            continuationToken = DynamoContinuationToken.encode(response.lastEvaluatedKey());
        }

        OperationDiagnostics scanDiag = buildQueryDiagnostics(DynamoConstants.OP_QUERY_SCAN, null,
                response.sdkHttpResponse().firstMatchingHeader(DynamoConstants.HEADER_REQUEST_ID).orElse(null),
                response.consumedCapacity(), items.size(), continuationToken,
                java.time.Duration.between(scanStart, java.time.Instant.now()), response.sdkHttpResponse());

        return new QueryPage(items, continuationToken, scanDiag);
    }

    /**
     * Executes a DynamoDB {@code Scan} with a filter expression and returns a page of
     * results.
     * <p>
     * Parameter names that do not already start with {@code :} are prefixed
     * automatically. Pagination uses the same {@link DynamoContinuationToken} encoding
     * as {@link #executeScan}.
     *
     * @param tableName        the physical DynamoDB table name
     * @param filterExpression the DynamoDB filter expression string
     * @param parameters       expression attribute values (keys are param names, may
     *                         or may not include the {@code :} prefix)
     * @param pageSize         maximum number of items to return
     * @param exclusiveStartKey DynamoDB exclusive start key for pagination, or
     *                         {@code null} for the first page
     * @return a page of results
     */
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

        java.time.Instant filterScanStart = java.time.Instant.now();
        ScanResponse response = dynamoClient.scan(scanBuilder.build());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(DynamoItemMapper.attributeMapToMap(item));
        }

        // DynamoDB Scan returns items in undefined hash-key order. Sort by sort key
        // (ascending) to match the implicit ordering of DynamoDB Query within a
        // partition and the Cosmos provider's default ORDER BY c.id ASC. Ordering
        // applies within this page only; see sortBySortKeyAsc() for the multi-page
        // limitation note.
        items.sort(SORT_KEY_ASC);

        String continuationToken = null;
        if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
            continuationToken = DynamoContinuationToken.encode(response.lastEvaluatedKey());
        }

        OperationDiagnostics filterDiag = buildQueryDiagnostics(DynamoConstants.OP_QUERY_SCAN_FILTER, null,
                response.sdkHttpResponse().firstMatchingHeader(DynamoConstants.HEADER_REQUEST_ID).orElse(null),
                response.consumedCapacity(), items.size(), continuationToken,
                java.time.Duration.between(filterScanStart, java.time.Instant.now()), response.sdkHttpResponse());

        return new QueryPage(items, continuationToken, filterDiag);
    }

    /**
     * Composes the physical DynamoDB table name from a logical resource address.
     * <p>
     * DynamoDB has no native database concept, so the database dimension is encoded
     * into the table name as: {@code database + "__" + collection}
     * (see {@link DynamoConstants#TABLE_NAME_SEPARATOR}).
     *
     * @param address the logical database + collection
     * @return the physical DynamoDB table name
     */
    private String resolveTableName(ResourceAddress address) {
        return address.database() + DynamoConstants.TABLE_NAME_SEPARATOR + address.collection();
    }

    /**
     * Comparator that orders result items by their sort key ({@code sortKey}
     * attribute) ascending.
     * <p>
     * <ul>
     *   <li>Items without a sort key sort before items that have one.</li>
     *   <li>String sort keys compare lexicographically.</li>
     *   <li>Numeric sort keys compare by value: {@code Long} and {@code Integer}
     *       use their native comparators to avoid precision loss. Other
     *       {@code Number} subtypes (including mixed-type pairs) are compared via
     *       {@link BigDecimal} — DynamoDB Number supports up to 38 significant
     *       digits, which exceeds {@code double} precision.</li>
     * </ul>
     * <p>
     * <b>Note:</b> This sort applies within a single page of results only.
     * For multi-page scans the overall iteration order across pages remains
     * determined by DynamoDB's internal token-based traversal, not by sort key.
     */
    private static final Comparator<Map<String, Object>> SORT_KEY_ASC =
            (a, b) -> {
                Object sa = a.get(DynamoConstants.ATTR_SORT_KEY);
                Object sb = b.get(DynamoConstants.ATTR_SORT_KEY);
                if (sa == null && sb == null) return 0;
                if (sa == null) return -1;
                if (sb == null) return 1;
                if (sa instanceof Number na && sb instanceof Number nb) {
                    // Use Long.compare for integer types to avoid precision loss —
                    // Double.compare(long, long) is incorrect for values > 2^53.
                    if (sa instanceof Long la && sb instanceof Long lb) {
                        return Long.compare(la, lb);
                    }
                    if (sa instanceof Integer ia && sb instanceof Integer ib) {
                        return Integer.compare(ia, ib);
                    }
                    // For Double, mixed types, or other Number subtypes use BigDecimal
                    // (DynamoDB Number supports up to 38 significant digits — beyond double
                    // precision — so toString() → BigDecimal preserves full value ordering).
                    return new BigDecimal(na.toString()).compareTo(new BigDecimal(nb.toString()));
                }
                return sa.toString().compareTo(sb.toString());
            };

    /**
     * Appends a partition key equality condition to a PartiQL statement.
     * <p>
     * If the statement already contains a {@code WHERE} clause, appends
     * {@code AND "partitionKey" = ?}; otherwise appends
     * {@code WHERE "partitionKey" = ?}.
     * The positional {@code ?} parameter must be added to the parameter list by the
     * caller.
     *
     * @param stmt the base PartiQL statement
     * @return the statement with the partition key condition appended
     */
    private String appendPartitionKeyCondition(String stmt) {
        if (stmt.toUpperCase().contains(DynamoConstants.SQL_WHERE)) {
            return stmt + " AND " + DynamoConstants.PARTIQL_PARTITION_KEY_CONDITION;
        }
        return stmt + " WHERE " + DynamoConstants.PARTIQL_PARTITION_KEY_CONDITION;
    }

    /**
     * Validates result-set control fields in a query request.
     * <p>
     * DynamoDB does not support server-side ORDER BY; any non-empty {@code orderBy}
     * list throws {@link MulticloudDbException} with
     * {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY}.
     * {@code limit} is supported via the DynamoDB Scan/PartiQL {@code LIMIT} parameter.
     */
    private void validateResultSetControl(QueryRequest query, String operation) {
        if (query.orderBy() != null && !query.orderBy().isEmpty()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                    "DynamoDB does not support ORDER BY. Check Capability.ORDER_BY before calling query().",
                    ProviderId.DYNAMO,
                    operation,
                    false,
                    null));
        }
    }

    @Override
    public CapabilitySet capabilities() {
        return DynamoCapabilities.CAPABILITIES;
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

    /**
     * Ensures the specified DynamoDB table exists and is {@code ACTIVE}, creating it if
     * absent.
     * <p>
     * The table is created with:
     * <ul>
     *   <li>Hash key: {@code partitionKey} (STRING)</li>
     *   <li>Range key: {@code sortKey} (STRING)</li>
     *   <li>Billing mode: {@code PAY_PER_REQUEST} (on-demand)</li>
     * </ul>
     * The method handles all intermediate table states gracefully:
     * <ul>
     *   <li>{@code null} (does not exist) — creates the table and waits for ACTIVE.</li>
     *   <li>{@code ACTIVE} — no-op.</li>
     *   <li>{@code CREATING} / {@code UPDATING} — waits for ACTIVE.</li>
     *   <li>{@code DELETING} — waits for deletion to complete, then creates.</li>
     * </ul>
     * Race conditions ({@link ResourceInUseException}) are silently ignored.
     *
     * @param address the logical database + collection; the physical table name is
     *                resolved via {@link #resolveTableName}
     * @throws com.multiclouddb.api.MulticloudDbException on any unrecoverable DynamoDB error
     */
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

    /**
     * Describes the current status of the given DynamoDB table.
     *
     * @param tableName the physical DynamoDB table name
     * @return the {@link TableStatus}, or {@code null} if the table does not exist
     */
    private TableStatus describeTableStatus(String tableName) {
        try {
            DescribeTableResponse response = dynamoClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build());
            return response.table().tableStatus();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    /**
     * Creates a DynamoDB table with the SDK's standard schema and waits until it is
     * {@code ACTIVE}.
     * <p>
     * Schema: hash key {@code partitionKey} (STRING) + range key {@code sortKey}
     * (STRING), billing mode {@code PAY_PER_REQUEST}.
     *
     * @param tableName the physical table name to create
     */
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

    /**
     * Blocks until the given DynamoDB table reaches {@code ACTIVE} status using the
     * AWS SDK's built-in waiter.
     *
     * @param tableName the physical table name to wait on
     */
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
     *
     * @param operation       the operation name (from {@link OperationNames})
     * @param address         the resource address (database + collection)
     * @param requestId       the AWS request ID from the response metadata
     * @param consumedCapacity the consumed capacity returned by DynamoDB, or {@code null}
     */
    private void logItemDiagnostics(String operation, ResourceAddress address,
            String requestId, ConsumedCapacity consumedCapacity) {
        double capacityUnits = consumedCapacity != null && consumedCapacity.capacityUnits() != null
                ? consumedCapacity.capacityUnits() : 0.0;

        LOG.debug("dynamo.diagnostics op={} db={} col={} requestId={} capacityUnits={}",
                operation, address.database(), address.collection(),
                requestId, capacityUnits);

        if (config.nativeDiagnosticsEnabled()) {
            LOG.info("dynamo.native-diagnostics op={} db={} col={} consumedCapacity={}",
                    operation, address.database(), address.collection(),
                    formatConsumedCapacity(consumedCapacity));
        }
    }

    /**
     * Logs per-query/scan diagnostics at DEBUG level: AWS request ID, consumed
     * capacity units, result count, and whether more pages exist.
     *
     * @param operation       the operation name (from {@link OperationNames} or
     *                        {@link DynamoConstants})
     * @param address         the resource address, or {@code null} for PartiQL
     *                        passthrough queries where the address is not available
     * @param requestId       the AWS request ID, or {@code null} for Scan responses
     *                        (extracted from HTTP headers separately)
     * @param consumedCapacity the consumed capacity, or {@code null}
     * @param itemCount       the number of items in the current page
     * @param nextToken       the pagination token, or {@code null} if no more pages
     */
    private OperationDiagnostics buildQueryDiagnostics(String operation, ResourceAddress address,
            String requestId, ConsumedCapacity consumedCapacity,
            int itemCount, String nextToken, java.time.Duration duration,
            SdkHttpResponse httpResponse) {
        double capacityUnits = consumedCapacity != null && consumedCapacity.capacityUnits() != null
                ? consumedCapacity.capacityUnits() : 0.0;
        int statusCode = httpResponse != null ? httpResponse.statusCode() : 0;
        String db  = address != null ? address.database()   : "-";
        String col = address != null ? address.collection() : "-";
        OperationDiagnostics diag = OperationDiagnostics
                .builder(ProviderId.DYNAMO, operation, duration)
                .requestId(requestId)
                .statusCode(statusCode)
                .requestCharge(capacityUnits)
                .itemCount(itemCount)
                .build();

        LOG.debug("dynamo.diagnostics op={} db={} col={} requestId={} capacityUnits={} itemCount={} hasMore={}",
                operation, db, col, requestId, capacityUnits, itemCount, nextToken != null);

        if (config.nativeDiagnosticsEnabled()) {
            LOG.info("dynamo.native-diagnostics op={} db={} col={} headers={} consumedCapacity={}",
                    operation, db, col,
                    httpResponse != null ? httpResponse.headers() : null,
                    formatConsumedCapacity(consumedCapacity));
        }
        return diag;
    }

    /**
     * Formats a {@link ConsumedCapacity} object for native-diagnostics logging,
     * including per-table, GSI, and LSI breakdowns when present.
     */
    private static String formatConsumedCapacity(ConsumedCapacity cc) {
        if (cc == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("table=").append(cc.tableName())
                .append(" total=").append(cc.capacityUnits());
        if (cc.readCapacityUnits() != null) sb.append(" rcu=").append(cc.readCapacityUnits());
        if (cc.writeCapacityUnits() != null) sb.append(" wcu=").append(cc.writeCapacityUnits());
        if (cc.globalSecondaryIndexes() != null && !cc.globalSecondaryIndexes().isEmpty()) {
            sb.append(" gsi=").append(cc.globalSecondaryIndexes());
        }
        if (cc.localSecondaryIndexes() != null && !cc.localSecondaryIndexes().isEmpty()) {
            sb.append(" lsi=").append(cc.localSecondaryIndexes());
        }
        return sb.toString();
    }
}
