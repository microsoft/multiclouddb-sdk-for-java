# Hyperscale DB SDK — Provider Compatibility Matrix

This document describes the portable capabilities supported by each provider and the
provider-specific extensions (feature flags) that are available.

## Portable Capabilities

| Capability                   | Cosmos DB | DynamoDB | Spanner | Description |
|------------------------------|:---------:|:--------:|:-------:|-------------|
| `PORTABLE_QUERY_EXPRESSION`  | ✅        | ✅       | ✅      | Portable expression DSL for queries |
| `CONTINUATION_TOKEN_PAGING`  | ✅        | ✅       | ✅      | Cursor-based pagination |
| `TRANSACTIONS`               | ✅        | ✅       | ✅      | Multi-document transactions |
| `BATCH_OPERATIONS`           | ✅        | ✅       | ✅      | Batch read/write operations |
| `STRONG_CONSISTENCY`         | ✅        | ✅       | ✅      | Strongly-consistent reads |
| `CHANGE_FEED`                | ✅        | ✅       | ✅      | Change feed / streams |
| `CROSS_PARTITION_QUERY`      | ✅        | ❌       | ✅      | Cross-partition / global secondary index queries |
| `NATIVE_SQL_QUERY`           | ✅        | ❌       | ✅      | Native SQL-like query passthrough |
| `LIKE`                       | ✅        | ❌       | ✅      | LIKE pattern matching in queries |
| `ORDER_BY`                   | ✅        | ❌       | ✅      | ORDER BY clause support |
| `ENDS_WITH`                  | ✅        | ❌       | ✅      | ENDS_WITH string function |
| `REGEX_MATCH`                | ✅        | ❌       | ✅      | Regular expression matching |
| `CASE_FUNCTIONS`             | ✅        | ❌       | ✅      | UPPER/LOWER case functions |

### Capability Discovery at Runtime

```java
HyperscaleDbClient client = HyperscaleDbClientFactory.create(config);
CapabilitySet caps = client.capabilities();

if (caps.isSupported(Capability.LIKE)) {
    // Use LIKE in queries
} else {
    // Fall back to STARTS_WITH + CONTAINS
}
```

## Portable Expression DSL

All providers support the portable expression grammar for queries:

| Feature         | Operator / Function   | Example |
|-----------------|-----------------------|---------|
| Comparison      | `=`, `!=`, `<`, `>`, `<=`, `>=` | `status = 'active'` |
| Logical         | `AND`, `OR`, `NOT`    | `age > 18 AND active = true` |
| Functions       | `STARTS_WITH`         | `STARTS_WITH(name, 'A')` |
|                 | `CONTAINS`            | `CONTAINS(tags, 'urgent')` |
|                 | `FIELD_EXISTS`        | `FIELD_EXISTS(metadata)` |
|                 | `STRING_LENGTH`       | `STRING_LENGTH(name) > 3` |
|                 | `COLLECTION_SIZE`     | `COLLECTION_SIZE(items) >= 1` |
| Parameters      | `@paramName`          | `price > @minPrice` |

### Expression Examples

```java
// Simple comparison
QueryRequest q1 = QueryRequest.builder()
    .expression("status = @status")
    .parameter("status", "active")
    .pageSize(20)
    .build();

// Function call
QueryRequest q2 = QueryRequest.builder()
    .expression("STARTS_WITH(name, @prefix) AND age >= @minAge")
    .parameter("prefix", "J")
    .parameter("minAge", 21)
    .pageSize(50)
    .build();
```

## Provider-Specific Extensions (Feature Flags)

Feature flags enable provider-specific behaviors. Using them produces **portability
warnings** at client creation time (logged at WARN level).

### Azure Cosmos DB

| Flag | Effect | Default |
|------|--------|---------|
| `cosmos.sessionConsistency` | Enable session-level consistency tokens | `false` |
| `cosmos.crossPartitionQuery` | Allow queries to fan out across partitions | `false` |

### Amazon DynamoDB

| Flag | Effect | Default |
|------|--------|---------|
| `dynamo.strongConsistentReads` | Force strongly-consistent reads on all get operations | `false` |
| `dynamo.onDemandBilling` | Use on-demand billing mode for table creation | `false` |

### Google Cloud Spanner

| Flag | Effect | Default |
|------|--------|---------|
| `spanner.readOnlyTransactions` | Use read-only transactions for query operations | `false` |
| `spanner.staleReads` | Allow stale reads with configurable staleness | `false` |

### Usage

```java
HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
    .provider(ProviderId.COSMOS)
    .connection(Map.of("endpoint", "https://..."))
    .featureFlag("cosmos.crossPartitionQuery", "true")
    .build();

// At creation time, a WARN log will be emitted:
// "Portability warning [COSMOS_CROSS_PARTITION_QUERY]: ..."
HyperscaleDbClient client = HyperscaleDbClientFactory.create(config);
```

## Error Category Mapping

All provider exceptions are mapped to portable `HyperscaleDbErrorCategory` values:

| Category | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| `INVALID_REQUEST` | HTTP 400 | ValidationException, HTTP 400 | INVALID_ARGUMENT, FAILED_PRECONDITION |
| `AUTHENTICATION_FAILED` | HTTP 401 | UnrecognizedClientException, HTTP 401/403 | UNAUTHENTICATED |
| `AUTHORIZATION_FAILED` | HTTP 403 | AccessDeniedException | PERMISSION_DENIED |
| `NOT_FOUND` | HTTP 404 | ResourceNotFoundException, HTTP 404 | NOT_FOUND |
| `CONFLICT` | HTTP 409, 412 | ConditionalCheckFailedException | ALREADY_EXISTS, ABORTED |
| `THROTTLED` | HTTP 429 | ProvisionedThroughputExceededException, ThrottlingException | RESOURCE_EXHAUSTED |
| `TRANSIENT_FAILURE` | HTTP 449, 500, 502, 503 | HTTP 500–5xx | UNAVAILABLE |
| `PERMANENT_FAILURE` | — | ItemCollectionSizeLimitExceededException | — |
| `UNSUPPORTED_CAPABILITY` | — | — | UNIMPLEMENTED |
| `PROVIDER_ERROR` | Other | Other | INTERNAL, Other |

## Native Client Escape Hatch

When portable abstractions are insufficient, access the provider's native client:

```java
// Cosmos DB
CosmosClient cosmosClient = client.nativeClient(CosmosClient.class);

// DynamoDB
DynamoDbClient dynamoClient = client.nativeClient(DynamoDbClient.class);

// Spanner
Spanner spannerClient = client.nativeClient(Spanner.class);
```

> **Warning**: Native client access breaks portability. The SDK logs an INFO
> message when the escape hatch is used.
