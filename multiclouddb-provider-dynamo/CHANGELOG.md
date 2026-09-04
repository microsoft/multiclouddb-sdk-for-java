# Changelog — multiclouddb-provider-dynamo

All notable changes to the `multiclouddb-provider-dynamo` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- `update()` now sends one conditional, aliased `UpdateItem SET` request instead of replacing the item with `PutItem`. Omitted fields are preserved; a failed `attribute_exists(partitionKey)` guard maps to `NOT_FOUND` without create.
- Structured null/map/list values use native DynamoDB `NULL`/`M`/`L` shapes. The generated update expression is rejected before I/O above 4,096 UTF-8 bytes; an accepted call consumes one item update's write capacity.
- A size-specific update `ValidationException` is normalized to non-retryable `UNSUPPORTED_CAPABILITY` with `reason=dynamodb_result_item_size_limit` and `maximumResultBytes=409600` when the existing item plus fields would exceed DynamoDB's 400 KiB result-item limit. This path follows one attempted `UpdateItem`, preserves the native cause/metadata, and adds no read/merge preflight; other validation failures remain `INVALID_REQUEST`.
- Declares `PARTIAL_UPDATE` and `PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS` supported, and `PARTIAL_UPDATE_EXTENDED_PAYLOAD` unsupported because either the 4,096-byte expression envelope or state-dependent 409,600-byte result-item envelope can bind before the common 408,576-byte field-map limit.

## [0.1.0-beta.2] — 2026-06-22

> **Requires `multiclouddb-api` 0.1.0-beta.2 or later** — this release consumes API surface (change-feed cursors, `CLIENT_CLOSED` envelope, `ChangeFeedConfig.extendedRetention(...)` opt-in gating) introduced in API beta.2. The dependency is pinned in the published POM.

### Added

- Change-feed reader backed by DynamoDB Streams (`DescribeStream`, `GetShardIterator`, `GetRecords`). `listCursors` returns one cursor per open shard at the live tip with a pre-resolved `LATEST` iterator (`@@ITER:<iterator>` continuation), avoiding the silent event loss that an `ANCHOR_NOW` sentinel produces between mint and first read. `readChanges` drains one shard's page per call, rotates the partition list across shards so multi-shard cursors are not starved, transitions to an `AFTER_SEQUENCE_NUMBER` continuation on the first observed record (good for the full 24-hour stream retention), and absorbs shard splits/closes by re-describing the stream and emitting child shards on the next cursor. `TrimmedDataAccessException` is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`; `ExpiredIteratorException` (~5-minute iterator idle timeout) is mapped to `reason=ITERATOR_EXPIRED`. Change-event payloads preserve the full DynamoDB type system (`M`/`L`/`SS`/`NS`/nested) via the shared `DynamoItemMapper`. The target table must have `StreamSpecification(NEW_AND_OLD_IMAGES)` enabled; otherwise the reader fails fast with `UNSUPPORTED_CAPABILITY(reason="stream_not_enabled")`.
- `DynamoCapabilities` explicitly declares `EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED` (DynamoDB Streams is fixed at 24h server-side; an SDK-managed archive-on-read path via customer-provisioned Kafka brokers is on the v1.x roadmap). Callers that opt in to `ChangeFeedConfig.extendedRetention(...)` fail fast at client-build time via the API-module factory gate; `DynamoProviderClient`'s constructor carries a defence-in-depth mirror gate so SPI-direct integrators (`ServiceLoader` consumers bypassing the factory) cannot silently drop the opt-in.
- Default sort-key ordering: scan paths (`executeScan`, `executeScanWithFilter`, `queryWithTranslation`) sort items per-page by sort key ascending, matching DynamoDB's native `Query` API and the Cosmos provider's global default. Per-page only — multi-page scans retain DynamoDB's token-based traversal order across pages.
- Typed `CLIENT_CLOSED` envelope on every post-close CRUD / query / provisioning / change-feed entry point. `close()` is idempotent and also disposes the embedded `DynamoDbStreamsClient`.

### Changed

- `SORT_KEY_ASC` comparator handles numeric sort keys with type-aware comparison (`Long`/`Integer` use their native compare; mixed numerics fall back to `BigDecimal`) so integers beyond `2^53` are no longer truncated through `Double.compare`.
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN ? AND ?)`) for cross-provider consistency.

### Documentation

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Dynamo provider issues an unconditional `DeleteItem` and does not pay the conditional-write WCU surcharge.
- AWS SDK v2 (2.34.x) bundles the DynamoDB Streams client classes inside the main `software.amazon.awssdk:dynamodb` artifact at `software.amazon.awssdk.services.dynamodb.streams.*` (verified against the published `dynamodb-2.34.0.jar`); no separate `dynamodbstreams` dependency is required. If `aws-sdk.version` is bumped, re-verify that the Streams classes remain bundled.

## [0.1.0-beta.1] — 2026-04-23

### Added

- Default sort-key ordering: all DynamoDB scan paths (`executeScan`,
  `executeScanWithFilter`, `queryWithTranslation`) now sort result items by sort
  key ascending within each page before returning the `QueryPage`. This matches
  the behavior of DynamoDB's native `Query` API (which sorts by range key within
  a partition) and mirrors the global sort introduced in the Cosmos provider.
  Note: sorting is per-page only — multi-page scans retain DynamoDB's token-based
  traversal order across pages. See `docs/compatibility.md` for details.

### Changed

- `SORT_KEY_ASC` comparator now handles numeric sort keys using type-aware
  comparison: `Long` pairs use `Long.compare`, `Integer` pairs use
  `Integer.compare`, and all other `Number` types (including mixed) use
  `BigDecimal` comparison to preserve DynamoDB's 38-digit numeric precision.
  Previously all numeric keys were compared via `Double.compare`, which loses
  precision for integers > 2^53.

- The DynamoDB client now stamps the outgoing `User-Agent` header with the
  canonical `multiclouddb-sdk-java/<version>` token via the AWS SDK
  `ClientOverrideConfiguration` API user-agent suffix. When
  `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` is configured,
  the suffix is appended to the header.

#### Provider adapter and client

- `DynamoProviderAdapter` — SPI entry point auto-discovered via `ServiceLoader`;
  registers as `ProviderId.DYNAMO` and supplies `DynamoProviderClient` and
  `DynamoExpressionTranslator`
- `DynamoProviderClient` — full `MulticloudDbProviderClient` implementation
  backed by the AWS SDK for Java v2 DynamoDB client

#### Authentication

- **Static credentials** — when `connection.accessKeyId` and
  `connection.secretAccessKey` are provided, uses `AwsBasicCredentials` with
  `StaticCredentialsProvider`
- **Default credential chain** — when credentials are not explicitly provided,
  falls back to the AWS SDK default credential provider chain (environment
  variables, system properties, IAM roles, etc.)

#### Connection configuration

- Configurable AWS region via `connection.region` (default: `us-east-1`)
- Optional custom endpoint override via `connection.endpoint` for DynamoDB
  Local or compatible emulators

#### CRUD operations

- `create` — conditional `PutItem` with `attribute_not_exists(partitionKey)` to
  enforce uniqueness; automatically injects `partitionKey` and `sortKey`
  attributes
- `read` — `GetItem` with composite key lookup; returns `null` when item is
  missing
- `update` — conditional `PutItem` with `attribute_exists(partitionKey)`;
  `ConditionalCheckFailedException` mapped to portable `NOT_FOUND`
- `upsert` — unconditional `PutItem` (no condition expression)
- `delete` — `DeleteItem`; idempotent (missing items do not raise errors)

#### Query support

- **Smart query routing** with four execution paths:
  1. **Native PartiQL passthrough** — raw PartiQL via `ExecuteStatement` when
     `QueryRequest.nativeExpression()` is set
  2. **Partition-key scoped query** — DynamoDB `Query` with
     `KeyConditionExpression` when partition key is provided without a filter
  3. **Filtered scan** — DynamoDB `Scan` with `FilterExpression` when a
     portable or legacy expression is provided without partition key
  4. **Full table scan** — DynamoDB `Scan` when no expression or partition key
     is provided
- **Portable expression translation** — automatic translation via
  `DynamoExpressionTranslator` in the `queryWithTranslation` path

#### Expression translation (`DynamoExpressionTranslator`)

- Translates the portable AST to DynamoDB PartiQL
  `SELECT * FROM "container" WHERE ...` syntax with positional `?` parameters
- Comparison, logical, NOT, IN, BETWEEN expressions fully supported
- Portable function mapping:
  - `starts_with` → `begins_with(...)`
  - `contains` → `contains(...)`
  - `field_exists` → `field IS NOT MISSING`
  - `string_length` → `char_length(...)`
  - `collection_size` → `size(...)`

#### Item mapping (`DynamoItemMapper`)

- Bidirectional conversion between portable `Map<String, Object>` / Jackson
  `JsonNode` and DynamoDB `AttributeValue` maps
- Supports strings, numbers (integer, long, double), booleans, nulls, nested
  objects, arrays, and DynamoDB set types (`SS`, `NS`) on the read path
- Heuristic number round-tripping: decimal strings → `Double`, integer strings
  → `Int` or `Long`

#### Error mapping (`DynamoErrorMapper`)

- Maps DynamoDB exception types to portable error categories:
  - `ConditionalCheckFailedException` → `CONFLICT`
  - `ResourceNotFoundException` → `NOT_FOUND`
  - `ValidationException` → `INVALID_REQUEST`
  - `AccessDeniedException` → `AUTHORIZATION_FAILED`
  - `UnrecognizedClientException` → `AUTHENTICATION_FAILED`
  - `ProvisionedThroughputExceededException`, `ThrottlingException`,
    `RequestLimitExceeded` → `THROTTLED`
  - `ItemCollectionSizeLimitExceededException` → `PERMANENT_FAILURE`
- HTTP status code fallback mapping for unrecognized exceptions
  (`400` → `INVALID_REQUEST`, `401`/`403` → `AUTHENTICATION_FAILED`,
  `404` → `NOT_FOUND`, `5xx` → `TRANSIENT_FAILURE`)
- Retryable flag set for throttling exceptions and 5xx responses
- Captures error code, service name, and request ID in provider details

#### Pagination (`DynamoContinuationToken`)

- Encodes DynamoDB `LastEvaluatedKey` maps into opaque Base64-URL tokens
  (no padding)
- Decodes tokens back to `Map<String, AttributeValue>` for `ExclusiveStartKey`
- Supports `S` (string) and `N` (number) attribute types in key serialization
- Native PartiQL path uses DynamoDB's built-in `nextToken` for pagination

#### Provisioning

- `ensureDatabase` — no-op (DynamoDB has no database concept)
- `ensureContainer` — creates table with `partitionKey` (hash) and `sortKey`
  (range) as `String` attributes using `PAY_PER_REQUEST` billing mode; handles
  table lifecycle states (`CREATING`, `UPDATING`, `DELETING`) with waiter-based
  polling; ignores `ResourceInUseException` race conditions

#### Table naming

- Logical `ResourceAddress` mapped to physical DynamoDB table name via
  `database__collection` convention (double-underscore separator)

#### Diagnostics

- Point operation logging with request ID and consumed capacity
- Query/scan diagnostics with request ID, HTTP status code, consumed capacity
  (table + GSI/LSI breakdown), item count, duration, and has-more-pages
  indicator

#### Capabilities

- Reports 6 capabilities as supported: continuation-token paging, transactions
  (`TransactWriteItems`/`TransactGetItems`), batch operations
  (`BatchWriteItem`/`BatchGetItem`), strong consistency (for item reads),
  change feed (DynamoDB Streams), portable expression translation
- Reports 7 capabilities as unsupported: cross-partition query, native SQL
  query, LIKE operator, ORDER BY, ENDS_WITH, REGEX_MATCH, case functions

#### Dependencies

- AWS SDK for Java v2 DynamoDB (`software.amazon.awssdk:dynamodb 2.34.0`)
