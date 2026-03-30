# Changelog — hyperscaledb-provider-spanner

All notable changes to the `hyperscaledb-provider-spanner` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-beta.1] — Unreleased

Initial public beta of the Google Cloud Spanner provider.

### Added

#### Provider adapter and client

- `SpannerProviderAdapter` — SPI entry point auto-discovered via
  `ServiceLoader`; registers as `ProviderId.SPANNER` and supplies
  `SpannerProviderClient` and `SpannerExpressionTranslator`
- `SpannerProviderClient` — full `HyperscaleDbProviderClient` implementation
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
- `upsert` — Spanner `INSERT_OR_UPDATE` mutation
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
