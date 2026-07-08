# Changelog — multiclouddb-provider-spanner

All notable changes to the `multiclouddb-provider-spanner` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Change-feed reader backed by Spanner change streams via the `READ_<stream>` TVF (single-use read-only transaction; 5-second bounded window per call). `listCursors` bootstraps the partition tree by calling the TVF with a `NULL` partition token and anchors each cursor''s bookmark at `max(now, childStart)` so `now()` cursors honour their live-tip contract on the emulator. `readChanges` drains a bounded window, absorbs `child_partitions_record` rows (splits/merges), rotates the partition list across partitions, and surfaces `isTerminal()=true` when a cursor''s sole partition closes without children. Each `data_change_record.mod` becomes one `ChangeEvent` with a stable `providerEventId` (`<server_transaction_id>:<commit_ts>:<record_sequence>:<mod_index>`). `INVALID_ARGUMENT` / `NOT_FOUND` / `OUT_OF_RANGE` on the TVF (most commonly a partition token outside the stream''s retention window) is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Per-collection change-stream name resolution: defaults to `<collection>_changes`; override via the `changeStream.<collection>` connection key (so producer and reader resolve the same stream when running against an operator-provisioned change stream).
- Extended-retention provisioning: `SpannerProviderClient.ensureContainer(address)` emits an idempotent `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = ''NEW_ROW'', retention_period = ''<value>'')` when the user opted in via `ChangeFeedConfig.extendedRetention(...)`, on both the fresh-table and the pre-existing-table paths. `value_capture_type = ''NEW_ROW''` ensures `mods.new_values` carries the full post-image (the GoogleSQL default of `OLD_AND_NEW_VALUES` only carries the mutated columns). The duplicate-name path reads back the active `retention_period` from `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and throws `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` (with `requestedRetention` and `activeRetention` in `providerDetails`) when the on-disk retention does not match the request — mirroring Cosmos''s read-back-and-reject so application code that branches on `providerDetails.reason` stays portable. `INVALID_ARGUMENT` from `updateDatabaseDdl(...)` whose message contains the `retention_period` token is re-mapped to `UNSUPPORTED_CAPABILITY(reason="retention_exceeds_native_max")`. `SpannerCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (default 24h; configurable up to 7d natively).
- Typed `CLIENT_CLOSED` envelope replacing prior raw `IllegalStateException` from `checkOpen()`. `close()` is idempotent and the `Spanner` field is `final` again; post-close errors attribute the failing operation (`create` / `read` / `update` / ...) instead of the literal `"checkOpen"`.

### Changed

- `upsert(address, key, document)` now uses Spanner `INSERT_OR_UPDATE` (was `REPLACE`). `REPLACE` is internally delete-then-insert, which change streams surface as `mod_type=INSERT` — making a second upsert of the same key appear as `ChangeType.CREATE` instead of `ChangeType.UPDATE`. `INSERT_OR_UPDATE` matches the `UPDATE` / `MODIFY` behaviour of Cosmos AVAD and DynamoDB Streams. The observable upsert semantics are unchanged: `FIELD_DATA` continues to project only the new document''s fields on read, so `CrudConformanceTests.upsertOverwrites` still passes.
- Spanner instance creation in `ensureDatabase` is gated to emulator mode. In production (no `emulatorHost` configured), the instance is expected to pre-exist; only the database is created. Creating a Spanner instance is a billable, region-specific operation that should be done deliberately.
- Complex container values (`Map`, `Collection`) round-trip through STRING columns using an unambiguous prefix marker (`U+0001` + `mcdb:json:`). User strings that happen to start with `{` or `[` are returned verbatim; user strings that themselves begin with `U+0001` are escaped at write time.
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN @lo AND @hi)`) for cross-provider consistency.

### Breaking changes

- **`update()` is a partial update preserving previously written fields.** The provider performs a read-modify-write transaction so the merged field set is written; the previous behaviour overwrote `FIELD_DATA` with only the columns named in the call, silently hiding every other previously-written column on the next `read()`. **Known cross-provider asymmetry:** Cosmos `update()` and DynamoDB `update()` are still full-document replaces; the portable SPI contract is currently undefined and alignment is tracked as follow-up. Callers that relied on the previous behaviour should issue a full document `upsert()` instead.
- **Document field named `data` is rejected** with `MulticloudDbException(category = INVALID_REQUEST)`. The provider reserves the `data` column for the internal `FIELD_DATA` metadata. The reserved-field check is case-insensitive (Spanner resolves column names case-insensitively); `Data` / `DATA` / `dAtA` are all rejected with the offending field name echoed back so callers can pinpoint which key to rename.
- **`upsert()` is a full document replace.** Columns absent from the upserted document become NULL on read; this matches the Cosmos / DynamoDB upsert contract. Callers that want partial modification must call `update()`.
- **Customer-managed tables require a `data STRING(MAX)` column.** Tables created by `ensureContainer()` already include it; tables provisioned outside the SDK must run `ALTER TABLE <table> ADD COLUMN data STRING(MAX);`.
- **`ensureDatabase(name)` throws `MulticloudDbException(INVALID_REQUEST)` when `name` does not match the configured `databaseId`.** Operations always route to the client''s configured database; accepting a different name silently provisioned the wrong database previously. To target a different database, construct a new client.
- **Lifecycle errors are typed.** `checkOpen()` and the `ensureDatabase` name-mismatch validation throw `MulticloudDbException` with categories `CLIENT_CLOSED` and `INVALID_REQUEST` respectively, replacing the prior raw `IllegalStateException` / `IllegalArgumentException`. Consumers that caught the raw JDK exceptions must catch `MulticloudDbException` and branch on `error().category()`.
- **`SpannerRowMapper.toMap()` preserves explicitly written `null` values.** Callers iterating `page.items().get(i)` must tolerate `null` (e.g., `Objects.toString(e.getValue(), "")` instead of `e.getValue().toString()`).

### Fixed

- **Default `ORDER BY` no longer fires for aggregate / `GROUP BY` queries.** The provider previously appended `ORDER BY partitionKey, sortKey` to every SELECT, which GoogleSQL rejects on aggregates with `column not aggregated`. The default is now suppressed when the SQL contains an aggregate function or `GROUP BY`; caller-supplied `ORDER BY` is honoured verbatim. The default also no longer duplicates primary-key columns when the caller already sorts by them — only the missing key is appended as a tiebreaker — and `ORDER BY` detection ignores string literals so `WHERE comment = ''please ORDER BY date''` is no longer a false positive.
- **Legacy / pre-`FIELD_DATA` rows preserve every column on read and `update()`.** When `FIELD_DATA` is absent or malformed, `SpannerRowMapper` applies the historical "no metadata => no filtering" rule including nulls; `update()` deliberately leaves `FIELD_DATA` alone so the reader''s fallback continues to project all legacy columns. A subsequent `upsert()` or `create()` promotes the row into the metadata regime by writing a complete `FIELD_DATA` stamp.
- `ensureDatabase()` / `ensureContainer()` no longer leak raw `RuntimeException` on non-Spanner failures. `InterruptedException` surfaces as `MulticloudDbException(TRANSIENT_FAILURE, retryable=true)` (with the interrupt flag restored); non-Spanner causes inside the admin `ExecutionException` surface as `MulticloudDbException(PROVIDER_ERROR)` preserving the original cause.
- `setMutationValue` no longer fails on common Java types (e.g. `java.time.Instant`) — JSON serialisation is restricted to `Map`/`Collection`; every other type falls back to `value.toString()`.

### Known limitations

- `setMutationValue` / `bindParameter` write `(String) null` for null values regardless of the target column type. Writing `null` into a Spanner `INT64`, `BOOL`, or `FLOAT64` column will be rejected with `INVALID_ARGUMENT` until the provider performs schema introspection to bind the correctly typed null. Workaround: pass a typed zero / sentinel value (e.g., `0L`, `false`), or wrap the column in a STRING.

### Documentation

- `delete()` of a missing key is documented as a silent no-op (idempotent); `Mutation.delete(table, Key.of(pk, sk))` is idempotent natively.

## [0.1.0-beta.1] — 2026-04-23

### Added

- The Spanner client now contributes the canonical
  `multiclouddb-sdk-java/<version>` token to the outgoing gRPC `user-agent`
  metadata via gax `FixedHeaderProvider`. The gax channel preserves the
  underlying gRPC default user-agent and merges this token alongside it. When
  `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` is configured,
  the suffix is appended to the token.

#### Provider adapter and client

- `SpannerProviderAdapter` — SPI entry point auto-discovered via
  `ServiceLoader`; registers as `ProviderId.SPANNER` and supplies
  `SpannerProviderClient` and `SpannerExpressionTranslator`
- `SpannerProviderClient` — full `MulticloudDbProviderClient` implementation
  backed by the Google Cloud Spanner Java client library

#### Authentication

- **Application Default Credentials** — uses the Google Cloud ADC chain
  (service account JSON, `gcloud auth`, Compute Engine metadata, etc.) for
  production environments
- **Emulator support** — when `connection.emulatorHost` is set, routes traffic
  to the Spanner emulator and bypasses normal cloud authentication

#### Connection configuration

- Required: `connection.instanceId`, `connection.databaseId`
- Optional: `connection.projectId` (defaults to `"test-project"`),
  `connection.emulatorHost`

#### CRUD operations

- `create` — Spanner `INSERT` mutation with `partitionKey` and `sortKey`
  columns; sort key defaults to partition key when absent; additional document
  fields written as individual columns via `writeMutationFields`
- `read` — GoogleSQL `SELECT * FROM <table> WHERE partitionKey = @partitionKey
  AND sortKey = @sortKey` via `singleUse().executeQuery()`; returns `null` when
  no row matches
- `update` — Spanner `UPDATE` mutation (fails if row does not exist)
- `upsert` — Spanner `INSERT_OR_UPDATE` mutation (merging upsert; superseded
  by `REPLACE` semantics in the Unreleased section — see *Breaking changes*
  above)
- `delete` — Spanner `DELETE` mutation using `KeySet.singleKey()`; `NOT_FOUND`
  is silently ignored for idempotent delete semantics

#### Query support

- **Native GoogleSQL passthrough** — execute raw GoogleSQL via
  `QueryRequest.nativeExpression()`
- **Full table scan** — when no expression is provided or expression equals
  the Cosmos-style sentinel `SELECT * FROM c`
- **Portable expression translation** — automatic translation via
  `SpannerExpressionTranslator` in the `queryWithTranslation` path
- **Partition-key scoping** — automatically appends
  `partitionKey = @_pkval` when `QueryRequest.partitionKey()` is set
- Named parameter binding supporting String, Long, Double, Boolean, and
  `byte[]` types; parameter names have leading `@` stripped for Spanner
  binding compatibility

#### Expression translation (`SpannerExpressionTranslator`)

- Translates the portable AST to GoogleSQL
  `SELECT * FROM <container> WHERE ...` syntax with named `@parameter`
  placeholders
- Comparison, logical, NOT, IN, BETWEEN expressions fully supported
- Portable function mapping:
  - `starts_with` → `STARTS_WITH(...)`
  - `contains` → `STRPOS(...) > 0`
  - `field_exists` → `field IS NOT NULL`
  - `string_length` → `CHAR_LENGTH(...)`
  - `collection_size` → `ARRAY_LENGTH(...)`

#### Row mapping (`SpannerRowMapper`)

- Read-side conversion from Spanner `ResultSet` rows to portable
  `Map<String, Object>` and Jackson `JsonNode`
- Type mapping: `STRING` → string, `INT64` → long, `FLOAT64` → double,
  `BOOL` → boolean, `BYTES` → Base64 string, `TIMESTAMP`/`DATE` → ISO string,
  `JSON` → parsed JSON node (with raw-string fallback)
- Null values mapped to JSON null / Java null

#### Error mapping (`SpannerErrorMapper`)

- Maps Spanner gRPC `ErrorCode` to portable error categories:
  - `INVALID_ARGUMENT` → `INVALID_REQUEST`
  - `NOT_FOUND` → `NOT_FOUND`
  - `ALREADY_EXISTS`, `ABORTED` → `CONFLICT`
  - `PERMISSION_DENIED` → `AUTHORIZATION_FAILED`
  - `UNAUTHENTICATED` → `AUTHENTICATION_FAILED`
  - `RESOURCE_EXHAUSTED` → `THROTTLED`
  - `FAILED_PRECONDITION` → `INVALID_REQUEST`
  - `UNIMPLEMENTED` → `UNSUPPORTED_CAPABILITY`
  - `UNAVAILABLE` → `TRANSIENT_FAILURE`
- Retryable flag sourced from `SpannerException.isRetryable()`
- Captures gRPC status name and error message in provider details
- Non-Spanner exceptions mapped to `PROVIDER_ERROR` with retryable `false`

#### Pagination (`SpannerContinuationToken`)

- Offset-based pagination encoded as opaque Base64-URL tokens (no padding)
- Provider client applies `LIMIT pageSize + 1 OFFSET offset` to detect whether
  more pages exist
- Decode gracefully returns offset `0` for null, blank, or malformed tokens

#### Provisioning

- `ensureDatabase` — no-op (database is selected at construction time)
- `ensureContainer` — probes table existence with `SELECT 1 FROM <table>
  LIMIT 1`; if missing, creates DDL with fixed schema: `partitionKey
  STRING(MAX) NOT NULL`, `sortKey STRING(MAX) NOT NULL`, `data STRING(MAX)`,
  `PRIMARY KEY (partitionKey, sortKey)` via `DatabaseAdminClient.
  updateDatabaseDdl()`; ignores `"Duplicate name in schema"` race conditions

#### Capabilities

- Reports all 13 well-known capabilities as supported with Spanner-specific
  notes: continuation-token paging (offset-based), cross-partition query,
  transactions, batch operations, strong consistency (external consistency),
  native SQL query (GoogleSQL), change feed, portable expression translation,
  LIKE operator, ORDER BY, ENDS_WITH, REGEX_MATCH, and case functions

#### Dependencies

- Google Cloud Spanner (`google-cloud-spanner 6.62.0`)
