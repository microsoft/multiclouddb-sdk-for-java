# Changelog

All notable changes to the Multicloud DB SDK modules.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and all modules adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## multiclouddb-api

### [Unreleased]

**Breaking changes — strict Lowest-Common-Denominator (LCD) portability:**

This release enforces strict LCD portability: any feature not supported by **all
three** providers (Cosmos, DynamoDB, Spanner) has been removed from the public
API. See `multiclouddb-api/CHANGELOG.md` for the full migration guide.

- **Removed types:** `DocumentMetadata`. `DocumentResult.read()` now returns
  the document `ObjectNode` directly.
- **Removed `QueryRequest` members:** `nativeExpression()`, `limit()`, and
  arbitrary-field `orderBy()`.
- **Removed `OperationOptions` members:** `ttlSeconds()`, `includeMetadata()`.
- **Removed `Capability` constants:** `CROSS_PARTITION_QUERY`,
  `NATIVE_SQL_QUERY`, `RESULT_LIMIT`, `LIKE_OPERATOR`, `ENDS_WITH`,
  `REGEX_MATCH`, `CASE_FUNCTIONS`, `ROW_LEVEL_TTL`, `WRITE_TIMESTAMP`. Only
  seven portable capabilities remain: `CONTINUATION_TOKEN_PAGING`,
  `TRANSACTIONS`, `BATCH_OPERATIONS`, `STRONG_CONSISTENCY`, `CHANGE_FEED`,
  `PORTABLE_QUERY_EXPRESSION`, `ORDER_BY` (restricted to the `sortKey` field).
- **`QueryRequest.partitionKey` is now required.** Building a `QueryRequest`
  without a partition key throws `IllegalArgumentException`.
- **`QueryRequest.orderBy(...)` accepts only `"sortKey"`** with `ASC` / `DESC`.
- **`QueryRequest.maxResults(int)` (new)** — client-side cap on the total
  number of items returned by `query()`. Replaces the removed `limit()`.

**Documentation:**

- `MulticloudDbClient.delete(...)` is documented as idempotent — silent on
  missing key. The Javadoc now declares that deleting a key that does not
  exist is a silent no-op on every provider, which is the true LCD across
  Cosmos (404 swallowed), DynamoDB (`DeleteItem` is idempotent natively) and
  Spanner (`Mutation.delete` is idempotent natively). Callers that need to detect a missing key should use
  `MulticloudDbClient.read(...)`, which returns `null` on every provider
  when the key does not exist (non-mutating). `update()` also throws
  `NOT_FOUND` on a missing key, but it requires a document body and
  **overwrites on hit**, so it is not a safe pure existence probe.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` - optional
  caller-supplied token appended to the SDK user-agent header sent by all
  provider clients.
- `MulticloudDbClientConfig.userAgentSuffix()` - accessor returning the
  configured suffix, or `null` if unset.
- `com.multiclouddb.spi.SdkUserAgent` - SPI helper that builds the canonical
  `multiclouddb-sdk-java/<version>` user-agent token.
- `MulticloudDbClient` - synchronous, provider-agnostic interface for CRUD,
  query, and schema provisioning
- `MulticloudDbClientFactory` - discovers provider adapters via `ServiceLoader`
- `MulticloudDbClientConfig` - immutable builder-pattern configuration
- `QueryRequest` - portable expression or native expression passthrough with
  named parameters, pagination, partition key scoping, limit, and orderBy
- `MulticloudDbKey` - portable `(partitionKey, sortKey)` identity
- `ResourceAddress` - `(database, collection)` targeting
- Portable expression parser, validator, and translator SPI
- `CapabilitySet` - runtime capability introspection
- `MulticloudDbException` - structured error model with portable categories
- `OperationDiagnostics` - latency, request charge, request ID
- `DocumentMetadata` - last modified, TTL expiry, version/ETag
- Document size enforcement (399 KB limit)

**Validation:**

- `userAgentSuffix(String)` rejects values longer than 256 characters and
  non-printable US-ASCII, protecting against header injection.

---

## multiclouddb-provider-cosmos

### [Unreleased]

**Changed — strict LCD portability:**

Aligned with `multiclouddb-api`'s strict LCD contract. See
`multiclouddb-api/CHANGELOG.md` for the full breaking-change list.

- `CosmosCapabilities` no longer declares the removed capabilities
  (`CROSS_PARTITION_QUERY`, `NATIVE_SQL_QUERY`, `RESULT_LIMIT`,
  `LIKE_OPERATOR`, `ENDS_WITH`, `REGEX_MATCH`, `CASE_FUNCTIONS`,
  `ROW_LEVEL_TTL`, `WRITE_TIMESTAMP`). Cosmos retains support for these
  features natively; they are simply no longer exposed through the portable
  API.
- `SELECT TOP n` translation is removed; `maxResults(int)` is enforced
  client-side via a single-page truncation in `DefaultMulticloudDbClient`.
- `nativeExpression()` passthrough is removed.
- `orderBy("sortKey", …)` is mapped to `ORDER BY c.id`; arbitrary-field
  orderBy is removed.

**Added:**

- `consistencyLevel` connection config key for opt-in client-level read
  consistency override (applied uniformly to every read from a given client
  instance). Valid values
  (case-insensitive): `STRONG`, `BOUNDED_STALENESS`, `SESSION`,
  `CONSISTENT_PREFIX`, `EVENTUAL`. When absent, read requests inherit the
  Cosmos DB account's configured default. See `docs/configuration.md` —
  *Consistency Level*.

**Changed:**

- Removed the hardcoded `ConsistencyLevel.SESSION` override from
  `CosmosClientBuilder`. Previously all reads were forced to `SESSION`
  regardless of the account's configured default. **Migration note:**
  accounts with a default of `STRONG` or `BOUNDED_STALENESS` will now serve
  reads at their configured level (higher latency / higher RU cost than
  before). Accounts configured to `SESSION` are unaffected. To restore the
  previous behaviour explicitly, set
  `multiclouddb.connection.consistencyLevel=SESSION`.

**Removed:**

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT`
  (`public static final ConsistencyLevel`, previously
  `ConsistencyLevel.SESSION`) — removed without a deprecation cycle; the
  project is pre-release. Callers referencing this constant should use
  `ConsistencyLevel.SESSION` directly.

**Changed:**

- `BETWEEN` translation now wraps in parentheses
  (`(c.field BETWEEN @lo AND @hi)`). Without the wrapping parens, Cosmos
  NoSQL's parser greedily binds the `BETWEEN`'s inner `AND` together with any
  trailing logical `AND`, producing a *"Syntax error, incorrect syntax near
  'AND'"* `BadRequest` for predicates like
  `age BETWEEN @lo AND @hi AND marker = @m`. The output of
  `TranslatedQuery.whereClause()` is now parenthesised — backward-compatible
  at the query-execution level, but consumers that string-match the where
  clause should update their expectations.

**Documentation:**

- `delete()` of a missing key remains a silent no-op (idempotent). The
  Cosmos provider continues to swallow the native 404 from `deleteItem(...)`,
  matching the LCD behaviour of DynamoDB (`DeleteItem` is idempotent
  natively) and Spanner (`Mutation.delete` is idempotent natively).
  Documented in the API Javadoc on `MulticloudDbClient.delete(...)` and in
  `docs/guide.md`. No caller-visible behaviour change. Callers needing to
  detect a missing key should use `read()`, which returns `null` on every
  provider when the key does not exist.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent header stamping with `multiclouddb-sdk-java/<version>` token
  and optional user-configured suffix.
- `CosmosProviderAdapter` - SPI entry point for Cosmos DB
- `CosmosProviderClient` - full implementation backed by Azure Cosmos DB Java SDK v4
- Master-key and Azure Identity (Entra ID) authentication
- Gateway and Direct connection modes
- Full CRUD with automatic field injection (`id`, `partitionKey`)
- Portable expression translation to Cosmos SQL
- Native SQL passthrough
- Cross-partition query support (capability-gated)
- Schema provisioning (database + container creation)

---

## multiclouddb-provider-dynamo

### [Unreleased]

**Changed — strict LCD portability:**

Aligned with `multiclouddb-api`'s strict LCD contract. See
`multiclouddb-api/CHANGELOG.md` for the full breaking-change list.

- **Cross-partition `Scan` routing is removed.** Every `QueryRequest` now
  carries a `partitionKey` by construction, so the DynamoDB provider always
  routes to the native `Query` API with a `KeyConditionExpression`.
- `orderBy` is narrowed to `orderBy("sortKey", ASC|DESC)`, mapped to
  `ScanIndexForward(true|false)`. Default is ASC.
- `QueryRequest.maxResults(int)` is enforced client-side by
  `DefaultMulticloudDbClient`; the old `limit()` path is removed.
- `DynamoCapabilities` no longer declares the removed capabilities
  (`CROSS_PARTITION_QUERY`, `NATIVE_SQL_QUERY`, `RESULT_LIMIT`,
  `LIKE_OPERATOR`, `ENDS_WITH`, `REGEX_MATCH`, `CASE_FUNCTIONS`,
  `ROW_LEVEL_TTL`, `WRITE_TIMESTAMP`).

**Changed:**

- `BETWEEN` translation now wraps in parentheses (`(field BETWEEN ? AND ?)`).
  Mirrors the parenthesised form emitted by sibling translators so
  cross-provider query stitching is uniform. PartiQL parses both forms
  correctly, so this is not a correctness fix on Dynamo — purely a
  consistency improvement. The output of `TranslatedQuery.whereClause()` is
  now parenthesised.

**Documentation:**

- `delete()` of a missing key remains a silent no-op (idempotent). The
  Dynamo provider issues an unconditional `DeleteItem`, so a delete of a key
  that does not exist is silently ignored — matching the LCD behaviour of
  Cosmos (404 swallowed) and Spanner (`Mutation.delete` is idempotent
  natively). No `attribute_exists` guard is added, so deletes do not pay the
  conditional-write WCU surcharge. Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. Callers needing to
  detect a missing key should use `read()`, which returns `null` on every
  provider when the key does not exist.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent suffix support via `SdkAdvancedClientOption.USER_AGENT_SUFFIX`.
- `DynamoProviderAdapter` - SPI entry point for DynamoDB
- `DynamoProviderClient` - full implementation backed by AWS SDK for Java 2.25.16
- AWS credential authentication (access key + secret key)
- Full CRUD with `attribute_not_exists` / `attribute_exists` guards
- Portable expression translation to PartiQL
- Native PartiQL passthrough
- Schema provisioning (table creation with ACTIVE-wait)

---

## multiclouddb-provider-spanner

### [Unreleased]

**Changed — strict LCD portability:**

Aligned with `multiclouddb-api`'s strict LCD contract. See
`multiclouddb-api/CHANGELOG.md` for the full breaking-change list.

- `nativeExpression()` passthrough is removed.
- `query.limit(int)` translation is removed; `maxResults(int)` is enforced
  client-side by `DefaultMulticloudDbClient`.
- `orderBy("sortKey", ASC|DESC)` is the only accepted form; mapped to
  `ORDER BY sortKey ASC|DESC`. Default is ASC.
- `DocumentMetadata` extraction is removed (the type itself is gone from the
  API). `read()` now returns the raw document only.
- `SpannerCapabilities` no longer declares the removed capabilities
  (`NATIVE_SQL_QUERY`, `RESULT_LIMIT`, `LIKE_OPERATOR`, `ENDS_WITH`,
  `REGEX_MATCH`, `CASE_FUNCTIONS`, `ROW_LEVEL_TTL`, `WRITE_TIMESTAMP`,
  `CROSS_PARTITION_QUERY`).

**Changed:**

- `BETWEEN` translation now wraps in parentheses
  (`(field BETWEEN @lo AND @hi)`). Mirrors the parenthesised form emitted by
  sibling translators so cross-provider query stitching is uniform.
  GoogleSQL parses both forms correctly, so this is not a correctness fix on
  Spanner — purely a consistency improvement. The output of
  `TranslatedQuery.whereClause()` is now parenthesised.

**Documentation:**

- `delete()` of a missing key remains a silent no-op (idempotent). The
  Spanner provider continues to use `Mutation.delete(table, Key.of(pk, sk))`
  via `databaseClient.write(...)`, which is idempotent natively — deleting a
  row that does not exist returns success without modifying state. This
  matches the LCD behaviour of Cosmos (404 swallowed) and DynamoDB
  (`DeleteItem` is idempotent natively). Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. Callers needing to
  detect a missing key should use `read()`, which returns `null` on every
  provider when the key does not exist.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent support via gax `FixedHeaderProvider`.
- `SpannerProviderAdapter` - SPI entry point for Spanner
- `SpannerProviderClient` - full implementation backed by Google Cloud Spanner 6.62.0
- GCP credential and emulator authentication
- Full CRUD with mutation-based writes
- Portable expression translation to GoogleSQL
- Native GoogleSQL passthrough
- Schema provisioning (DDL-based table creation)
