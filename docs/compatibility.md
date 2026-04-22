# Multicloud DB SDK — Provider Compatibility Matrix

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
MulticloudDbClient client = MulticloudDbClientFactory.create(config);
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

All provider exceptions are mapped to portable `MulticloudDbErrorCategory` values.
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

## Default Sort-Key Ordering

Starting from this release, all Cosmos DB and DynamoDB query paths return results
sorted by the document's sort key ascending.

### Cosmos DB

Cosmos DB appends `ORDER BY c.id ASC` to every query that does not already carry
an explicit `ORDER BY` clause (and is not an aggregate / `GROUP BY` query). This
is applied server-side, so the order is globally consistent across all pages.

> **⚠️ Custom indexing policy — composite index required**
> If your Cosmos container uses a **custom indexing policy** that does not include
> a composite index on `(filterField ASC, id ASC)`, Cosmos DB will throw a
> `400 Bad Request` at runtime for cross-partition queries that combine `WHERE` and
> the default `ORDER BY c.id ASC`. The default indexing policy includes all paths
> and supports this automatically. If you have tuned your indexing policy, add the
> composite index for every field you filter on:
> ```json
> { "compositeIndexes": [ [{ "path": "/filterField", "order": "ascending" },
>                          { "path": "/id", "order": "ascending" }] ] }
> ```
>
> **⚠️ RU cost**
> Appending `ORDER BY c.id ASC` to all Cosmos queries incurs an additional RU
> charge versus unordered queries, proportional to result-set size. This cost is
> the price of cross-provider consistency and is expected behavior.
>
> **⚠️ Aggregates and GROUP BY**
> Cosmos DB rejects `ORDER BY` on aggregate expressions (`COUNT`, `SUM`, `MIN`,
> `MAX`, `AVG`) and `GROUP BY` queries. The SDK automatically detects these patterns
> and omits the default `ORDER BY` for them.

### DynamoDB

DynamoDB results are sorted in memory per page after fetching (client-side).
Within a single page, items are returned sorted by sort key ascending.
For multi-page scans the overall order across pages is determined by DynamoDB's
internal token-based traversal, not sort key — this is a known limitation.

### Spanner

The Spanner provider does not yet implement default sort-key ordering.
Consumers relying on consistent cross-provider sort behavior should not use
the Spanner provider until this gap is addressed.

> **Tracking issue**: Default sort-key ordering for the Spanner provider is
> tracked in [GitHub Issue #55](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/55)
> (to be created). Until resolved, do not mix Spanner with Cosmos or DynamoDB
> in conformance-sensitive workloads.

## Escape Hatch Policy

The SDK does not expose a `nativeClient()` method. Direct access to the
underlying provider client is intentionally omitted to enforce portability
guarantees — code written against the SDK must remain switchable between
providers by configuration alone.

For provider-specific features not covered by the portable API, file a feature
request or implement the operation using the provider's own SDK alongside
(not through) the `MulticloudDbClient`.
