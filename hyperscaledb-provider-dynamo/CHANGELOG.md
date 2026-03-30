# Changelog — hyperscaledb-provider-dynamo

All notable changes to the `hyperscaledb-provider-dynamo` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-beta.1] — Unreleased

Initial public beta of the Amazon DynamoDB provider.

### Added

#### Provider adapter and client

- `DynamoProviderAdapter` — SPI entry point auto-discovered via `ServiceLoader`;
  registers as `ProviderId.DYNAMO` and supplies `DynamoProviderClient` and
  `DynamoExpressionTranslator`
- `DynamoProviderClient` — full `HyperscaleDbProviderClient` implementation
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
