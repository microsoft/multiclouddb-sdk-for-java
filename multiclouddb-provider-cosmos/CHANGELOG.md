# Changelog — multiclouddb-provider-cosmos

All notable changes to the `multiclouddb-provider-cosmos` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-beta.2] — 2026-06-17

> **Requires `multiclouddb-api` 0.1.0-beta.2 or later** — this release consumes API surface (change-feed cursors, `CLIENT_CLOSED` envelope, `EXTENDED_CHANGE_FEED_HISTORY` capability) introduced in API beta.2. The dependency is pinned in the published POM.

### Added

- Change-feed reader backed by `CosmosContainer.queryChangeFeed(...)` and `getFeedRanges()`. `listCursors` mints one cursor per feed range at the live tip via a one-item warmup query that captures a real continuation token (with a `@@PIT:<epoch-millis>` fallback for older SDKs). `readChanges` drains one page per call, rotates the partition list across ranges so multi-range cursors are not starved, and uses All-Versions-and-Deletes (AVAD) mode so `ChangeEvent.type()` distinguishes `CREATE` / `UPDATE` / `DELETE`. The target container must be provisioned with an AVAD `ChangeFeedPolicy`; non-AVAD containers surface the Cosmos 400 BadRequest through the normalised envelope on the first read. HTTP 410 GONE on `queryChangeFeed` is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Extended-retention provisioning: `CosmosProviderClient.ensureContainer(address)` provisions an AVAD `ChangeFeedPolicy` carrying the duration from `ChangeFeedConfig.extendedRetention(...)` when the user opted in, and reads back the active policy after `createContainerIfNotExists(...)` — throwing `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` (with `requestedRetention` and `activeRetention` in `providerDetails`) when a pre-existing container's retention does not match the request. A 400 BadRequest whose message fingerprint indicates the Cosmos account lacks Continuous Backup is re-mapped to `UNSUPPORTED_CAPABILITY(reason="continuous_backup_required")` so callers do not have to substring-match raw messages. `CosmosCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (up to 30 days via Continuous Backup; 7d minimum).
- `consistencyLevel` connection config key for opt-in client-level read consistency override. Valid case-insensitive values: `STRONG`, `BOUNDED_STALENESS`, `SESSION`, `CONSISTENT_PREFIX`, `EVENTUAL`. When absent, reads inherit the Cosmos DB account's configured default. See `docs/configuration.md` — *Consistency Level*.
- Typed `CLIENT_CLOSED` envelope on every post-close CRUD / query / provisioning / change-feed entry point, replacing leaked `IllegalStateException`s from azure-cosmos. `close()` is idempotent under concurrent callers; the underlying `cosmosClient.close()` is invoked exactly once.

### Changed

- Removed the hardcoded `ConsistencyLevel.SESSION` override from `CosmosClientBuilder`. Accounts with a default of `STRONG` or `BOUNDED_STALENESS` will now serve reads at their configured level (higher latency / RU cost than before). Accounts configured to `SESSION` are unaffected. To restore the previous behaviour explicitly, set `multiclouddb.connection.consistencyLevel=SESSION`.
- `BETWEEN` translation now wraps in parentheses (`(c.field BETWEEN @lo AND @hi)`). Without this, Cosmos NoSQL's parser binds the inner `AND` together with any trailing logical `AND`, producing a `BadRequest` for predicates like `age BETWEEN @lo AND @hi AND marker = @m`. The output of `TranslatedQuery.whereClause()` is now parenthesised.

### Removed

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT` (`public static final ConsistencyLevel`, previously `ConsistencyLevel.SESSION`) — removed without a deprecation cycle; the project is pre-release. Callers referencing this constant should use `ConsistencyLevel.SESSION` directly.

### Documentation

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Cosmos provider continues to swallow the native 404.

## [0.1.0-beta.1] — 2026-04-23

### Added

- Default sort-key ordering: all Cosmos DB queries now have `ORDER BY c.id ASC`
  appended automatically when no explicit `ORDER BY` is set, ensuring consistent
  sort behavior with DynamoDB. Aggregate queries (`COUNT`, `SUM`, `MIN`, `MAX`,
  `AVG`) and `GROUP BY` queries are exempt — Cosmos DB rejects `ORDER BY` on them.
  Queries with an existing `ORDER BY` clause are not modified (idempotent).
  See `docs/compatibility.md` for custom indexing policy requirements and RU cost
  implications.

### Changed

- `applyResultSetControl()` now uses a word-boundary regex (`\bORDER\s+BY\b`)
  instead of `String.contains()` to detect existing `ORDER BY` clauses, preventing
  false positives from string literals (e.g. `WHERE c.note = 'place order by friday'`).

- The Cosmos client now stamps the outgoing `User-Agent` header with the
  canonical `multiclouddb-sdk-java/<version>` token. When
  `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` is configured,
  the suffix is appended to the header.

#### Provider adapter and client

- `CosmosProviderAdapter` — SPI entry point auto-discovered via `ServiceLoader`;
  registers as `ProviderId.COSMOS` and supplies `CosmosProviderClient` and
  `CosmosExpressionTranslator`
- `CosmosProviderClient` — full `MulticloudDbProviderClient` implementation
  backed by the Azure Cosmos DB Java SDK v4

#### Authentication

- **Master-key auth** — when `connection.key` is provided, uses
  `CosmosClientBuilder.key()` for shared-key authentication
- **Azure Identity / Entra ID auth** — when no key is provided, uses
  `DefaultAzureCredential` (supporting Managed Identity, Azure CLI, environment
  variables, and the full Azure credential chain); optional `connection.tenantId`
  for multi-tenant scenarios

#### Connection modes

- **Gateway mode** (default) — HTTP-based routing through the Cosmos DB gateway
- **Direct mode** — TCP-based direct connectivity when
  `connection.connectionMode` is set to `"direct"`
- Default consistency level: `SESSION`

#### CRUD operations

- `create` — `CosmosContainer.createItem()` with automatic injection of Cosmos
  `id` field (from sort key or partition key) and `partitionKey` field
- `read` — `CosmosContainer.readItem()` with 404 mapped to `null` return
- `update` — `CosmosContainer.replaceItem()` with key-field injection
- `upsert` — `CosmosContainer.upsertItem()` with key-field injection
- `delete` — `CosmosContainer.deleteItem()` with idempotent 404 handling

#### Query support

- **Native Cosmos SQL passthrough** — execute raw Cosmos SQL via
  `QueryRequest.nativeExpression()`
- **Portable expression translation** — automatic translation of the portable
  query AST to Cosmos SQL via `CosmosExpressionTranslator`
- **Partition-key scoping** — when `QueryRequest.partitionKey()` is set,
  queries are scoped to a single logical partition via
  `CosmosQueryRequestOptions.setPartitionKey()`
- **Continuation-token pagination** — uses Cosmos DB's native continuation
  tokens for efficient server-side paging with configurable page size (default:
  100)
- Named parameter binding with automatic `@` prefix normalization

#### Expression translation (`CosmosExpressionTranslator`)

- Translates the portable AST to Cosmos SQL `SELECT * FROM c WHERE ...` syntax
- All fields prefixed with the Cosmos alias `c.` (e.g., `c.age >= @minAge`)
- Comparison, logical, NOT, IN, BETWEEN expressions fully supported
- Portable function mapping:
  - `starts_with` → `STARTSWITH(...)`
  - `contains` → `CONTAINS(...)`
  - `field_exists` → `IS_DEFINED(...)`
  - `string_length` → `LENGTH(...)`
  - `collection_size` → `ARRAY_LENGTH(...)`

#### Error mapping (`CosmosErrorMapper`)

- Maps Cosmos DB HTTP status codes to portable error categories:
  - `400` → `INVALID_REQUEST`
  - `401` → `AUTHENTICATION_FAILED`
  - `403` → `AUTHORIZATION_FAILED`
  - `404` → `NOT_FOUND`
  - `409`, `412` → `CONFLICT`
  - `429` → `THROTTLED`
  - `449`, `500`, `502`, `503` → `TRANSIENT_FAILURE`
- Retryable flag set for `429`, `449`, `500`, `502`, `503`
- Captures Cosmos substatus code, activity ID, and request charge in provider
  details

#### Diagnostics (`CosmosDiagnosticsLogger`)

- **Point operations** — DEBUG-level logging of operation, database, container,
  activity ID, status code, RU charge, and latency; auto-escalates to WARN when
  latency exceeds 10 ms or RU charge exceeds 10
- **Query pages** — DEBUG-level logging of RU charge, item count, continuation
  token presence, and latency; auto-escalates to WARN above 100 ms / 100 RU,
  or ERROR above 1000 ms
- **Exceptions** — ERROR-level logging with HTTP status, substatus, activity ID,
  and full native diagnostics string
- Optional **native SDK diagnostics** pass-through when
  `MulticloudDbClientConfig.nativeDiagnosticsEnabled()` is `true`

#### Provisioning

- `ensureDatabase` — idempotent database creation via
  `CosmosClient.createDatabaseIfNotExists()`
- `ensureContainer` — idempotent container creation with partition key path
  `/partitionKey`

#### Capabilities

- Reports all 13 well-known capabilities as supported with Cosmos-specific
  notes: continuation-token paging, cross-partition query, transactional batch,
  bulk operations, configurable consistency (including strong), native SQL
  query, change feed, portable expression translation, LIKE operator, ORDER BY,
  ENDS_WITH, REGEX_MATCH, and UPPER/LOWER case functions

#### Dependencies

- Azure Cosmos DB Java SDK v4 (`azure-cosmos 4.78.0`)
- Azure Identity (`azure-identity 1.18.2`)
