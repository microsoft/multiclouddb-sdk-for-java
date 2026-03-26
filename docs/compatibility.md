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

if (caps.isSupported(Capability.LIKE_OPERATOR)) {
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
    .maxPageSize(20)
    .build();

// Function call
QueryRequest q2 = QueryRequest.builder()
    .expression("STARTS_WITH(name, @prefix) AND age >= @minAge")
    .parameter("prefix", "J")
    .parameter("minAge", 21)
    .maxPageSize(50)
    .build();
```

## Error Category Mapping

All provider exceptions are mapped to portable `HyperscaleDbErrorCategory` values.
The raw HTTP or gRPC status code is also available via `error.statusCode()`.

| Category  | Cosmos DB  | DynamoDB  | Spanner  |
|-----------|------------|-----------|----------|
| `INVALID_REQUEST`  | HTTP 400  | ValidationException, HTTP 400  | INVALID_ARGUMENT, FAILED_PRECONDITION  |
| `AUTHENTICATION_FAILED`  | HTTP 401  | UnrecognizedClientException, HTTP 401/403  | UNAUTHENTICATED  |
| `AUTHORIZATION_FAILED`  | HTTP 403  | AccessDeniedException  | PERMISSION_DENIED  |
| `NOT_FOUND`  | HTTP 404  | ResourceNotFoundException, HTTP 404  | NOT_FOUND  |
| `CONFLICT` (409 — duplicate key)  | HTTP 409  | `ConditionalCheckFailedException` from `create()` — `attribute_not_exists` guard fails when the item already exists  | ALREADY_EXISTS  |
| `CONFLICT` (412 — precondition)  | HTTP 412  | `ConditionalCheckFailedException` from `update()`/`upsert()` with a condition expression¹  | ABORTED  |
| `THROTTLED`  | HTTP 429  | ProvisionedThroughputExceededException, ThrottlingException  | RESOURCE_EXHAUSTED  |
| `TRANSIENT_FAILURE`  | HTTP 449, 500, 502, 503  | HTTP 500–5xx  | UNAVAILABLE  |
| `PERMANENT_FAILURE`  | —  | ItemCollectionSizeLimitExceededException  | —  |
| `UNSUPPORTED_CAPABILITY`  | —  | —  | UNIMPLEMENTED  |
| `PROVIDER_ERROR`  | Other  | Other  | INTERNAL, Other  |

> ¹ DynamoDB uses `ConditionalCheckFailedException` for both the 409 (duplicate-key on `create`) and 412
> (precondition failure on conditional `update`/`upsert`) cases — both currently map to `CONFLICT`.
> The portable API does not yet expose ETag-based conditional updates; when it does, the 412-equivalent
> path will be split into a dedicated `PRECONDITION_FAILED` category (tracked in issue #29).

## Native Client Escape Hatch

When portable abstractions are insufficient, access the provider's native client
directly via `nativeClient(Class<T>)`. This is also the correct path for
**async / reactive access** — the portable API is synchronous by design; each
provider exposes its own async client type:

```java
// Cosmos DB — sync
CosmosClient cosmosClient = client.nativeClient(CosmosClient.class);
// Cosmos DB — async (Reactor Mono/Flux)
CosmosAsyncClient cosmosAsync = client.nativeClient(CosmosAsyncClient.class);

// DynamoDB — sync
DynamoDbClient dynamoClient = client.nativeClient(DynamoDbClient.class);
// DynamoDB — async (CompletableFuture)
DynamoDbAsyncClient dynamoAsync = client.nativeClient(DynamoDbAsyncClient.class);

// Spanner — sync/async (ApiFuture / gRPC streaming)
Spanner spannerClient = client.nativeClient(Spanner.class);
```

> **Warning**: Native client access breaks portability. The SDK logs an INFO
> message when the escape hatch is used. The async client types listed above
> are provider-specific — code using them cannot be switched to another
> provider by configuration alone.
