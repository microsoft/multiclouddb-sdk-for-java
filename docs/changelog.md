# Changelog

All notable changes to the Multicloud DB SDK modules.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and all modules adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## multiclouddb-api

### [Unreleased]

**Added:**

- Portable change-feed API in `com.multiclouddb.api.changefeed`: `ChangeFeedCursor` (opaque, persistable via `toToken()` / `fromToken(...)` with a `now()` live-tip sentinel), `ChangeFeedPage` (events + `nextCursor` + `hasMore`/`terminal`), `ChangeEvent` (with stable `providerEventId` for dedup), `ChangeType`, and `CursorExpiredException`. Two new entry points on `MulticloudDbClient`: `listCursors(ResourceAddress)` and `readChanges(ResourceAddress, ChangeFeedCursor[, OperationOptions])`. Provider SPI methods default to `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged. The cursor wire format is opaque, version-tagged Base64URL JSON; the 24-hour portable baseline is enforced client-side on the token''s last-issued timestamp. `OperationOptions.timeout()` is not enforced on the change-feed path in this release.
- New error category `MulticloudDbErrorCategory.CURSOR_EXPIRED` carrying a canonical `providerDetails.reason` set (`TOKEN_AGED_OUT`, `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`, `MALFORMED`, `VERSION_UNSUPPORTED`, `PROVIDER_MISMATCH`, `RESOURCE_MISMATCH`), exported as public `CursorTokenCodec.REASON_*` constants.
- New error category `MulticloudDbErrorCategory.CLIENT_CLOSED` surfaced by a `DefaultMulticloudDbClient` post-close guard on every public entry point (replaces provider-specific `IllegalStateException` leaks). `MulticloudDbClient.close()` is now idempotent.
- Extended change-feed retention opt-in: `ChangeFeedConfig.extendedRetention(Duration)` (validates `> 24h`), wired into `MulticloudDbClientConfig.changeFeed(...)`, plus the new `Capability.EXTENDED_CHANGE_FEED_HISTORY`. The factory''s build-time gate refuses to instantiate a client whose provider does not declare the capability, surfacing `UNSUPPORTED_CAPABILITY(reason="extended_retention_unavailable")` before any I/O. The cursor token wire format carries an optional `"e"` field stamping the opted-in retention so a persisted cursor under a 7-day opt-in can be resumed beyond 24h up to the configured window without `TOKEN_AGED_OUT`; older tokens (no `"e"`) keep the 24h floor.
- `OperationNames.LIST_CURSORS`, `READ_CHANGES`, `PROVISION_SCHEMA` propagated through `MulticloudDbError.operation()` and `OperationDiagnostics`.

**Documentation:**

- `MulticloudDbClient.delete(...)` is documented as idempotent on every provider — a missing key is a silent no-op. Callers needing to detect a missing key should use `read(...)`.

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

**Added:**

- Change-feed reader backed by `CosmosContainer.queryChangeFeed(...)` and `getFeedRanges()`. `listCursors` mints one cursor per feed range at the live tip via a one-item warmup query that captures a real continuation token (with a `@@PIT:<epoch-millis>` fallback for older SDKs). `readChanges` drains one page per call, rotates the partition list across ranges so multi-range cursors are not starved, and uses All-Versions-and-Deletes (AVAD) mode so `ChangeEvent.type()` distinguishes `CREATE` / `UPDATE` / `DELETE`. The target container must be provisioned with an AVAD `ChangeFeedPolicy`. HTTP 410 GONE on `queryChangeFeed` is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Extended-retention provisioning: `CosmosProviderClient.ensureContainer(address)` provisions an AVAD `ChangeFeedPolicy` carrying the duration from `ChangeFeedConfig.extendedRetention(...)` when the user opted in, and reads back the active policy — throwing `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` when a pre-existing container''s retention does not match. A 400 BadRequest whose message fingerprint indicates the Cosmos account lacks Continuous Backup is re-mapped to `UNSUPPORTED_CAPABILITY(reason="continuous_backup_required")`. `CosmosCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (up to 30 days via Continuous Backup; 7d minimum).
- `consistencyLevel` connection config key for opt-in client-level read consistency override (`STRONG`, `BOUNDED_STALENESS`, `SESSION`, `CONSISTENT_PREFIX`, `EVENTUAL`). When absent, reads inherit the account''s configured default.
- Typed `CLIENT_CLOSED` envelope on every post-close entry point, replacing leaked `IllegalStateException`s from azure-cosmos. `close()` is idempotent under concurrent callers.

**Changed:**

- Removed the hardcoded `ConsistencyLevel.SESSION` override from `CosmosClientBuilder`. Accounts with a default of `STRONG` or `BOUNDED_STALENESS` will now serve reads at their configured level. To restore the previous behaviour, set `multiclouddb.connection.consistencyLevel=SESSION`.
- `BETWEEN` translation now wraps in parentheses (`(c.field BETWEEN @lo AND @hi)`) to avoid a Cosmos NoSQL parser ambiguity with trailing `AND`.

**Removed:**

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT` — removed without a deprecation cycle (pre-release). Callers should use `ConsistencyLevel.SESSION` directly.

**Documentation:**

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Cosmos provider continues to swallow the native 404.

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

**Added:**

- Change-feed reader backed by DynamoDB Streams (`DescribeStream`, `GetShardIterator`, `GetRecords`). `listCursors` returns one cursor per open shard at the live tip with a pre-resolved `LATEST` iterator (`@@ITER:<iterator>` continuation), avoiding silent event loss between mint and first read. `readChanges` drains one shard''s page per call, rotates the partition list across shards, transitions to an `AFTER_SEQUENCE_NUMBER` continuation on the first observed record, and absorbs shard splits/closes. `TrimmedDataAccessException` → `CursorExpiredException(reason=PROVIDER_TRIMMED)`; `ExpiredIteratorException` → `reason=ITERATOR_EXPIRED`. Change-event payloads preserve the full DynamoDB type system via the shared `DynamoItemMapper`. The target table must have `StreamSpecification(NEW_AND_OLD_IMAGES)` enabled; otherwise `UNSUPPORTED_CAPABILITY(reason="stream_not_enabled")`.
- `DynamoCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED` (DynamoDB Streams is fixed at 24h server-side; SDK-managed archive-on-read via customer-provisioned Kafka is on the v1.x roadmap). Callers that opt in to `ChangeFeedConfig.extendedRetention(...)` fail fast at client-build time via the API-module factory gate; the `DynamoProviderClient` constructor mirrors the gate for SPI-direct integrators.
- Default sort-key ordering: scan paths sort items per-page by sort key ascending, matching DynamoDB''s native `Query` API and the Cosmos provider''s default. Per-page only.
- Typed `CLIENT_CLOSED` envelope on every post-close entry point. `close()` is idempotent and also disposes the embedded `DynamoDbStreamsClient`.

**Changed:**

- `SORT_KEY_ASC` comparator handles numeric sort keys with type-aware comparison (Long/Integer use native compare; mixed numerics fall back to `BigDecimal`) so integers beyond `2^53` are no longer truncated.
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN ? AND ?)`) for cross-provider consistency.

**Documentation:**

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Dynamo provider issues an unconditional `DeleteItem`.
- AWS SDK v2 (2.34.x) bundles the DynamoDB Streams client classes inside the main `software.amazon.awssdk:dynamodb` artifact at `software.amazon.awssdk.services.dynamodb.streams.*` (verified against the published `dynamodb-2.34.0.jar`); no separate `dynamodbstreams` dependency is required. If `aws-sdk.version` is bumped, re-verify that the Streams classes remain bundled.

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

**Added:**

- Change-feed reader backed by Spanner change streams via the `READ_<stream>` TVF (single-use read-only transaction; 5-second bounded window per call). `listCursors` bootstraps the partition tree with a `NULL` partition token and anchors each cursor''s bookmark at `max(now, childStart)` so `now()` cursors honour their live-tip contract on the emulator. `readChanges` drains a bounded window, absorbs `child_partitions_record` rows (splits/merges), rotates the partition list, and surfaces `isTerminal()=true` when a cursor''s sole partition closes without children. Each `data_change_record.mod` becomes one `ChangeEvent` with a stable `providerEventId` (`<server_transaction_id>:<commit_ts>:<record_sequence>:<mod_index>`). `INVALID_ARGUMENT` / `NOT_FOUND` / `OUT_OF_RANGE` on the TVF → `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Per-collection change-stream name resolution: defaults to `<collection>_changes`; override via the `changeStream.<collection>` connection key.
- Extended-retention provisioning: `SpannerProviderClient.ensureContainer(address)` emits an idempotent `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = ''NEW_ROW'', retention_period = ''<value>'')` when the user opted in. `value_capture_type = ''NEW_ROW''` ensures `mods.new_values` carries the full post-image (the GoogleSQL default of `OLD_AND_NEW_VALUES` only carries the mutated columns). The duplicate-name path reads back the active `retention_period` from `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and throws `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` on mismatch. `INVALID_ARGUMENT` from `updateDatabaseDdl(...)` mentioning `retention_period` → `UNSUPPORTED_CAPABILITY(reason="retention_exceeds_native_max")`. `SpannerCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (default 24h; up to 7d natively).
- Typed `CLIENT_CLOSED` envelope replacing prior raw `IllegalStateException` from `checkOpen()`. `close()` is idempotent; post-close errors attribute the failing operation instead of `"checkOpen"`.

**Changed:**

- `upsert(address, key, document)` uses Spanner `INSERT_OR_UPDATE` (was `REPLACE`). `REPLACE` is internally delete-then-insert, which change streams surface as `mod_type=INSERT` — making a second upsert of the same key appear as `ChangeType.CREATE` instead of `ChangeType.UPDATE`. `INSERT_OR_UPDATE` matches Cosmos AVAD and DynamoDB Streams.
- Spanner instance creation in `ensureDatabase` is gated to emulator mode. In production the instance is expected to pre-exist; only the database is created.
- Complex container values (`Map`, `Collection`) round-trip through STRING columns using an unambiguous prefix marker (`U+0001` + `mcdb:json:`).
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN @lo AND @hi)`) for cross-provider consistency.

**Breaking changes:**

- `update()` is a partial update preserving previously written fields (read-modify-write transaction). **Known cross-provider asymmetry:** Cosmos and DynamoDB `update()` are still full-document replaces.
- Document field named `data` is rejected with `MulticloudDbException(INVALID_REQUEST)` (case-insensitive — Spanner resolves column names case-insensitively). The `data` column is reserved for the internal `FIELD_DATA` metadata.
- `upsert()` is a full document replace; columns absent from the upserted document become NULL on read (matches the Cosmos / DynamoDB upsert contract).
- Customer-managed tables require a `data STRING(MAX)` column. Tables created by `ensureContainer()` already include it; tables provisioned outside the SDK must run `ALTER TABLE <table> ADD COLUMN data STRING(MAX);`.
- `ensureDatabase(name)` throws `MulticloudDbException(INVALID_REQUEST)` when `name` does not match the configured `databaseId`.
- Lifecycle errors are typed: `checkOpen()` throws `MulticloudDbException(CLIENT_CLOSED)`, `ensureDatabase` name-mismatch throws `MulticloudDbException(INVALID_REQUEST)`, replacing the prior raw `IllegalStateException` / `IllegalArgumentException`.
- `SpannerRowMapper.toMap()` preserves explicitly written `null` values; callers iterating `page.items().get(i)` must tolerate `null`.

**Fixed:**

- Default `ORDER BY` no longer fires for aggregate / `GROUP BY` queries (GoogleSQL rejects with `column not aggregated`). It also no longer duplicates primary-key columns when the caller already sorts by them, and `ORDER BY` detection ignores string literals (so `WHERE comment = ''please ORDER BY date''` is no longer a false positive).
- Legacy / pre-`FIELD_DATA` rows preserve every column on read and `update()`. When `FIELD_DATA` is absent or malformed, the reader applies the historical "no metadata => no filtering" rule; `update()` deliberately leaves `FIELD_DATA` alone so the reader''s fallback continues to project all legacy columns. A subsequent `upsert()` or `create()` promotes the row into the metadata regime.
- `ensureDatabase()` / `ensureContainer()` no longer leak raw `RuntimeException` on non-Spanner failures. `InterruptedException` → `TRANSIENT_FAILURE`; non-Spanner causes inside the admin `ExecutionException` → `PROVIDER_ERROR`.
- `setMutationValue` no longer fails on common Java types (e.g. `java.time.Instant`) — JSON serialisation is restricted to `Map`/`Collection`; every other type falls back to `value.toString()`.

**Known limitations:**

- `setMutationValue` / `bindParameter` write `(String) null` for null values regardless of the target column type. Writing `null` into a Spanner `INT64`, `BOOL`, or `FLOAT64` column will be rejected. Workaround: pass a typed zero / sentinel value, or wrap the column in a STRING.

**Documentation:**

- `delete()` of a missing key is documented as a silent no-op (idempotent); `Mutation.delete(table, Key.of(pk, sk))` is idempotent natively.

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
