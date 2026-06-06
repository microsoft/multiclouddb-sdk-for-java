# Changelog — multiclouddb-api

All notable changes to the `multiclouddb-api` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Breaking changes — strict Lowest-Common-Denominator (LCD) portability

This release enforces strict LCD portability: any feature not supported by **all
three** providers (Cosmos, DynamoDB, Spanner) has been removed from the public
API. Application code that compiled against earlier betas may need migration.
See the *Migration guide* section below.

#### Removed types

- `DocumentMetadata` (and `DocumentResult.metadata()`) — write-metadata
  (`lastModified`, `ttlExpiry`, `version`) is not uniformly available across all
  three providers, so the type is removed. `DocumentResult.read()` now returns
  the document `ObjectNode` directly.

#### Removed members on `QueryRequest`

- `nativeExpression()` and `Builder.nativeExpression(String)` — the
  provider-native query escape hatch is removed. All queries must use the
  portable expression DSL.
- `orderBy(String, SortDirection)` and the per-page `SortOrder` API — only one
  portable ordering is exposed: orderBy is restricted to the `sortKey` field
  with `ASC` or `DESC` (see *Behavioural changes* below).
- `limit(int)` — server-side `TOP N` is not portable to DynamoDB. The new
  `maxResults(int)` cap (client-side single-page truncation) replaces it.

#### Removed members on `OperationOptions`

- `ttlSeconds()` and `Builder.ttlSeconds(long)` — row-level TTL is not
  supported by Spanner.
- `includeMetadata()` and `Builder.includeMetadata(boolean)` — write-timestamp
  metadata is not supported by DynamoDB or Spanner.

#### Removed `Capability` constants

- `CROSS_PARTITION_QUERY`, `NATIVE_SQL_QUERY`, `RESULT_LIMIT`, `LIKE_OPERATOR`,
  `ORDER_BY` is **kept** but its semantics are restricted to the `sortKey`
  field, `ENDS_WITH`, `REGEX_MATCH`, `CASE_FUNCTIONS`, `ROW_LEVEL_TTL`,
  `WRITE_TIMESTAMP`. The portable capability set now contains exactly seven
  capabilities: `CONTINUATION_TOKEN_PAGING`, `TRANSACTIONS`, `BATCH_OPERATIONS`,
  `STRONG_CONSISTENCY`, `CHANGE_FEED`, `PORTABLE_QUERY_EXPRESSION`, `ORDER_BY`.

### Behavioural changes

- **`QueryRequest.partitionKey` is now required.** Building a `QueryRequest`
  without a partition key throws `IllegalArgumentException`. Cross-partition
  queries are not portable to DynamoDB and are no longer exposed.
- **`QueryRequest.orderBy` accepts only `sortKey`.** The portable ordering
  contract is "ascending or descending by the document's sort key, within a
  partition". Other field names are rejected at builder time. The default
  ordering (no `orderBy` call) is ASC by `sortKey`.
- **`QueryRequest.maxResults(int)` (new)** — cross-provider cap on the total
  number of items returned by `query()`. Enforced client-side by truncating
  the page returned from the provider; the underlying continuation token is
  still surfaced on the truncated page. Replaces the removed `limit()`.

### Migration guide

| Removed/Changed | Replacement |
|----------------|-------------|
| `QueryRequest.builder().nativeExpression("SELECT …")` | Rewrite using the portable expression DSL. If the source query relied on provider-specific operators (`LIKE`, regex, etc.), the application must add a filter pass in code. |
| `QueryRequest.builder().limit(25)` | `QueryRequest.builder().maxResults(25)` |
| `QueryRequest.builder().orderBy("createdAt", DESC)` | `QueryRequest.builder().orderBy("sortKey", DESC)`. The sort field must be `sortKey` (the document's MulticloudDbKey sort component). |
| Cross-partition `QueryRequest` (no `partitionKey` set) | Set a `partitionKey`. Workloads that need to scan multiple partitions must paginate per partition. |
| `OperationOptions.builder().ttlSeconds(3_600)` | Manage TTL at the container/table level via the provider's native console; do not pass per-document TTL. |
| `OperationOptions.builder().includeMetadata(true)` | Removed. There is no portable equivalent. |
| `result.metadata().lastModified() / .ttlExpiry() / .version()` | Removed. There is no portable equivalent. |
| `Capability.CROSS_PARTITION_QUERY`, `NATIVE_SQL_QUERY`, `RESULT_LIMIT`, `LIKE_OPERATOR`, `ENDS_WITH`, `REGEX_MATCH`, `CASE_FUNCTIONS`, `ROW_LEVEL_TTL`, `WRITE_TIMESTAMP` | Removed. Every remaining capability is supported by all three providers; runtime capability checks for portable code are unnecessary. |

### Documentation

- **`MulticloudDbClient.delete(...)` is documented as idempotent — silent on
  missing key.** The Javadoc now declares that deleting a key that does not
  exist is a silent no-op on every provider, which is the true LCD across
  Cosmos (404 swallowed), DynamoDB (`DeleteItem` is idempotent natively) and
  Spanner (`Mutation.delete` is idempotent natively). Callers that need to detect a missing key should use
  `MulticloudDbClient.read(...)`, which returns `null` on every provider
  when the key does not exist (non-mutating). `update()` also throws
  `NOT_FOUND` on a missing key, but it requires a document body and
  **overwrites on hit**, so it is not a safe pure existence probe.

## [0.1.0-beta.1] — 2026-04-23

### Added

- `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` — optional
  caller-supplied token appended to the SDK user-agent header sent by all
  provider clients (Cosmos, DynamoDB, Spanner). Useful for downstream
  identification of applications, frameworks, or tenants. Pass `null` to clear
  a previously-set suffix.
- `MulticloudDbClientConfig.userAgentSuffix()` — accessor returning an
  `Optional<String>` of the configured suffix.
- `com.multiclouddb.spi.SdkUserAgent` — SPI helper that builds the canonical
  `multiclouddb-sdk-java/<version> (<jvm>; <os>)` token consumed by provider
  adapters.

### Validation

- `userAgentSuffix(String)` rejects values longer than 256 characters and any
  string containing characters outside printable US-ASCII (0x20–0x7E) plus
  horizontal tab (0x09), throwing `IllegalArgumentException`. This protects
  the user-agent header from injection of CR/LF or other control characters.

#### Portable client API

- `MulticloudDbClient` — synchronous, provider-agnostic interface for CRUD
  (`create`, `read`, `update`, `upsert`, `delete`), query, and schema
  provisioning (`ensureDatabase`, `ensureContainer`, `provisionSchema`)
- `MulticloudDbClientFactory` — discovers provider adapters via `ServiceLoader`
  and returns a configured `MulticloudDbClient` instance
- `MulticloudDbClientConfig` — immutable builder-pattern configuration holding
  provider identity, connection properties, auth properties, default operation
  options, and native diagnostics opt-in flag. All map accessors return
  unmodifiable copies; builder setters perform defensive copying
- `OperationOptions` — per-call controls (currently: timeout with hard-deadline
  contract)
- `QueryRequest` — immutable query input with portable expression or native
  expression passthrough, named parameters, page-size hint, continuation token,
  and optional partition-key scoping
- `QueryPage` — immutable query result page carrying items
  (`List<Map<String, Object>>`), opaque continuation token, and optional
  diagnostics

#### Identity and addressing

- `ProviderId` — extensible value-object identifying providers with well-known
  constants (`COSMOS`, `DYNAMO`, `SPANNER`) and runtime registration support
- `ResourceAddress` — database + container/table logical address
- `MulticloudDbKey` — portable record key supporting partition key, optional
  sort key, and arbitrary extra components. `toString()` result is cached at
  construction time

#### Query expression model

- Sealed `Expression` AST with six node types:
  - `ComparisonExpression` — field comparisons (`=`, `<>`, `!=`, `<`, `>`,
    `<=`, `>=`)
  - `LogicalExpression` — boolean connectives (`AND`, `OR`)
  - `NotExpression` — unary negation
  - `InExpression` — set membership (`field IN (...)`)
  - `BetweenExpression` — range predicate (`field BETWEEN low AND high`)
  - `FunctionCallExpression` — portable functions with five built-ins:
    `starts_with`, `contains`, `field_exists`, `string_length`,
    `collection_size`
- `FieldRef` — field reference supporting dot-notation paths (e.g.,
  `address.city`)
- `Literal` — typed constant values (string, number, boolean, null)
- `Parameter` — named parameter placeholder (`@paramName`)
- `ComparisonOp` and `LogicalOp` — operator enumerations
- `PortableFunction` — enumeration of cross-provider function names
- `TranslatedQuery` — translation output carrying the provider-native query
  string plus named or positional parameter bindings
- `ExpressionTranslator` — SPI interface that providers implement to translate
  the portable AST into their native query language
- `ExpressionParser` — hand-written recursive-descent parser for a SQL-like
  WHERE-clause subset supporting parentheses, boolean logic (`AND`, `OR`,
  `NOT`), comparisons, `IN`, `BETWEEN`, function calls, string/number/boolean/
  null literals, `@`-prefixed named parameters, and dot-notation field paths
- `ExpressionValidator` — validates that all `@parameter` references in a parsed
  expression tree have corresponding entries in the supplied parameter map;
  throws `ExpressionValidationException` with all unresolved parameter names

#### Capability model

- `Capability` — extensible value-object with supported/unsupported state and
  optional provider-specific notes. Thirteen well-known capabilities defined:
  `continuation_token_paging`, `cross_partition_query`, `transactions`,
  `batch_operations`, `strong_consistency`, `native_sql_query`, `change_feed`,
  `portable_query_expression`, `like_operator`, `order_by`, `ends_with`,
  `regex_match`, `case_functions`
- `CapabilitySet` — immutable capability map for capability introspection at
  runtime

#### Error model

- `MulticloudDbErrorCategory` — extensible string-based error category with ten
  well-known values: `INVALID_REQUEST`, `AUTHENTICATION_FAILED`,
  `AUTHORIZATION_FAILED`, `NOT_FOUND`, `CONFLICT`, `THROTTLED`,
  `TRANSIENT_FAILURE`, `PERMANENT_FAILURE`, `PROVIDER_ERROR`,
  `UNSUPPORTED_CAPABILITY`
- `MulticloudDbError` — structured portable error payload carrying category,
  message, provider identity, operation name, HTTP status code, retryable flag,
  and unmodifiable provider-detail map
- `MulticloudDbException` — runtime exception wrapping `MulticloudDbError` with
  optional `OperationDiagnostics` attachment

#### Diagnostics

- `OperationDiagnostics` — builder-pattern value object capturing provider
  identity, operation name, duration, and optional fields: request ID, HTTP
  status code, request charge (RU), ETag, session token, and item count
- `OperationNames` — canonical operation name constants shared across
  diagnostics, errors, and provider implementations

#### SPI (Service Provider Interface)

- `MulticloudDbProviderAdapter` — SPI entry point that providers register via
  `META-INF/services`; supplies provider identity, client factory, and optional
  expression translator
- `MulticloudDbProviderClient` — SPI contract for provider implementations
  covering CRUD, query, query-with-translation, provisioning
  (`ensureDatabase`, `ensureContainer`, `provisionSchema` with default parallel
  two-phase implementation using bounded thread pool), capabilities, provider
  identity, and `AutoCloseable` lifecycle
