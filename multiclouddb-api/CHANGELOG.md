# Changelog — multiclouddb-api

All notable changes to the `multiclouddb-api` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-beta.2] — 2026-06-17

### Added

- Portable change-feed API in `com.multiclouddb.api.changefeed`: `ChangeFeedCursor` (opaque, persistable via `toToken()` / `fromToken(...)` with a `now()` live-tip sentinel), `ChangeFeedPage` (events + `nextCursor` + `hasMore`/`terminal`), `ChangeEvent` (with stable `providerEventId` for dedup), `ChangeType`, and `CursorExpiredException`. Two new entry points on `MulticloudDbClient`: `listCursors(ResourceAddress)` and `readChanges(ResourceAddress, ChangeFeedCursor[, OperationOptions])`. Provider SPI methods default to `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged. The cursor wire format is opaque, version-tagged Base64URL JSON; the 24-hour portable baseline is enforced client-side on the token's last-issued timestamp. `OperationOptions.timeout()` is not enforced on the change-feed path in this release (wall-clock follows each provider's page-fetch budget).
- New error category `MulticloudDbErrorCategory.CURSOR_EXPIRED` carrying a canonical `providerDetails.reason` set (`TOKEN_AGED_OUT`, `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`, `MALFORMED`, `VERSION_UNSUPPORTED`, `PROVIDER_MISMATCH`, `RESOURCE_MISMATCH`), exported as public `CursorTokenCodec.REASON_*` constants so providers share a single source of truth.
- New error category `MulticloudDbErrorCategory.CLIENT_CLOSED` surfaced by a `DefaultMulticloudDbClient` post-close guard on every public entry point (replaces provider-specific `IllegalStateException` leaks). `MulticloudDbClient.close()` is now idempotent.
- Extended change-feed retention opt-in: `ChangeFeedConfig.extendedRetention(Duration)` (validates `> 24h`), wired into `MulticloudDbClientConfig.changeFeed(...)`, plus the new well-known `Capability.EXTENDED_CHANGE_FEED_HISTORY`. The `MulticloudDbClientFactory.create(...)` build-time gate refuses to instantiate a client whose provider does not declare the capability, surfacing `UNSUPPORTED_CAPABILITY(reason="extended_retention_unavailable")` before any I/O. The cursor token wire format carries an optional `"e"` field stamping the opted-in retention so a persisted cursor under a 7-day opt-in can be resumed beyond 24h up to the configured window without `TOKEN_AGED_OUT`; older tokens (no `"e"`) keep the 24h floor.
- `OperationNames.LIST_CURSORS`, `READ_CHANGES`, `PROVISION_SCHEMA` propagated through `MulticloudDbError.operation()` and `OperationDiagnostics`.

### Documentation

- `MulticloudDbClient.delete(...)` is documented as idempotent on every provider — a missing key is a silent no-op (LCD of Cosmos 404 swallow, DynamoDB native idempotence, and Spanner `Mutation.delete`). Callers needing to detect a missing key should use `read(...)`, which returns `null` when the key is missing.

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
