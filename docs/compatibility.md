# Portable API Surface

The Multicloud DB SDK ships **only capabilities that work identically across all
three providers**. If a feature cannot be delivered portably on Azure Cosmos DB,
Amazon DynamoDB, *and* Google Cloud Spanner, it is not part of the public API.
This strict portability guarantee means every line of code you write against the
SDK runs unchanged on any supported provider.

---

## What Works Everywhere

Every capability listed below is fully supported on **all** providers. There are
no asterisks, no provider-specific caveats, and no runtime checks required.

### CRUD Operations

| Operation | Description |
|-----------|-------------|
| **Create** | Insert a new document (fails if the key already exists) |
| **Read** | Point-read by partition key + sort key |
| **Update** | Replace an existing document (fails if not found) |
| **Upsert** | Create or replace — always succeeds |
| **Delete** | Remove by key (idempotent) |

### Query — Portable Expression DSL

Write a WHERE-clause filter once. The SDK translates it to the native query
language of whichever provider is configured — Cosmos SQL, DynamoDB PartiQL,
or Spanner GoogleSQL.

| Feature | Operators / Functions | Example |
|---------|----------------------|---------|
| Comparison | `=`, `!=`, `<`, `>`, `<=`, `>=` | `status = 'active'` |
| Logical | `AND`, `OR`, `NOT` | `age > 18 AND active = true` |
| String functions | `STARTS_WITH`, `CONTAINS` | `STARTS_WITH(name, 'A')` |
| Field introspection | `FIELD_EXISTS` | `FIELD_EXISTS(metadata)` |
| Length functions | `STRING_LENGTH`, `COLLECTION_SIZE` | `STRING_LENGTH(name) > 3` |
| Named parameters | `@paramName` | `price > @minPrice` |

```java
QueryRequest query = QueryRequest.builder()
    .expression("STARTS_WITH(name, @prefix) AND age >= @minAge")
    .parameter("prefix", "J")
    .parameter("minAge", 21)
    .maxPageSize(50)
    .build();

QueryPage page = client.query(address, query);
```

### Pagination

| Feature | Description |
|---------|-------------|
| **Cursor-based paging** | Continuation-token pagination across all providers |
| **Page size control** | `maxPageSize` to limit results per page |

### Data Management

| Feature | Description |
|---------|-------------|
| **Schema provisioning** | `provisionSchema()` creates databases, containers, and tables portably |
| **Transactions** | Multi-document transactional operations |
| **Batch operations** | Batch read/write for throughput efficiency |
| **Strong consistency** | Strongly-consistent reads |
| **Change feed** | Change feed / change streams |

### Diagnostics & Error Handling

| Feature | Description |
|---------|-------------|
| **Structured diagnostics** | Latency, request charge, and provider correlation IDs per operation |
| **Portable error categories** | All provider exceptions mapped to `MulticloudDbErrorCategory` |
| **Capability introspection** | `client.capabilities()` reports what the current provider supports |

---

## Portable Error Mapping

All provider exceptions are mapped to portable `MulticloudDbErrorCategory` values.
The raw HTTP or gRPC status code is also available via `error.statusCode()`.

| Category | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| `INVALID_REQUEST` | HTTP 400 | ValidationException, HTTP 400 | INVALID_ARGUMENT, FAILED_PRECONDITION |
| `AUTHENTICATION_FAILED` | HTTP 401 | UnrecognizedClientException, HTTP 401/403 | UNAUTHENTICATED |
| `AUTHORIZATION_FAILED` | HTTP 403 | AccessDeniedException | PERMISSION_DENIED |
| `NOT_FOUND` | HTTP 404 | ResourceNotFoundException, HTTP 404 | NOT_FOUND |
| `CONFLICT` (duplicate key) | HTTP 409 | `ConditionalCheckFailedException` from `create()` | ALREADY_EXISTS |
| `CONFLICT` (precondition) | HTTP 412 | `ConditionalCheckFailedException` from conditional `update()`¹ | ABORTED |
| `THROTTLED` | HTTP 429 | ProvisionedThroughputExceededException | RESOURCE_EXHAUSTED |
| `TRANSIENT_FAILURE` | HTTP 449, 500, 502, 503 | HTTP 500–5xx | UNAVAILABLE |
| `PERMANENT_FAILURE` | — | ItemCollectionSizeLimitExceededException | — |
| `PROVIDER_ERROR` | Other | Other | INTERNAL, Other |

> ¹ DynamoDB uses `ConditionalCheckFailedException` for both duplicate-key and precondition-failure
> cases. The portable API does not yet expose ETag-based conditional updates; when it does, the
> 412-equivalent path will be split into a dedicated `PRECONDITION_FAILED` category.

---

## Native Query Escape Hatch

For advanced scenarios requiring provider-specific query syntax, `nativeExpression()`
allows passing a raw query string directly to the underlying provider. This is the
**only** escape hatch the SDK provides — it is deliberately minimal.

```java
QueryRequest q = QueryRequest.builder()
    .nativeExpression("SELECT * FROM c WHERE c.title LIKE '%flight%'")
    .pageSize(25)
    .build();
```

!!! warning "Portability trade-off"

    Code using `nativeExpression()` is **not portable**. It will only work with
    the provider whose query language you've written. The SDK logs a
    `PortabilityWarning` when native expressions are used.

The SDK does **not** expose a `nativeClient()` method. Direct access to the
underlying provider client is intentionally omitted to enforce portability
guarantees — code written against the SDK must remain switchable between
providers by configuration alone.
