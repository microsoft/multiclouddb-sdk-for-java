# Data Model: Multicloud DB SDK

This is a technology-agnostic model for the portable contract.

## Entities

### Provider
Represents the selected backend database provider.

Fields:
- `id`: enum/string (e.g., `cosmos`, `dynamo`, `spanner`)
- `display_name`: string
- `version`: optional string (provider SDK version / service API version as available)

### ClientConfig
Configuration required to select a provider and connect/authenticate.

Fields:
- `provider`: Provider id
- `connection`: provider-specific connection settings (endpoint/region/project/instance/database)
- `auth`: provider-specific authentication settings (credential chain, profile names, tenant ids, etc.)
- `options`: portable options (timeouts, retry policy)
- `feature_flags`: optional provider-specific opt-in toggles (must be explicit and portability-impacting)

### ResourceAddress
Portable addressing of the target logical storage.

Fields:
- `database`: string (or equivalent top-level namespace)
- `collection`: string (container/table)

Validation:
- must be non-empty strings

### Key
Portable representation of the minimum key material.

Fields:
- `partitionKey`: string (required — the distribution/hash key that determines data placement)
- `sortKey`: optional string (the item identifier/range key within a partition)
- `components`: optional map/list for composite keys

Validation:
- must include all key parts required by the configured provider and target collection schema

### Document
Portable JSON-like payload.

Fields:
- `value`: JSON-like object

Constraints:
- a complete `create()`/`upsert()` document must be non-null and serialize as a
  JSON object;
- shared preflight snapshots the top-level map and uses bounded SDK-owned
  Jackson serialization while inspecting nested values;
- top-level complete-document names `id`, `partitionKey`, `sortKey`, `ttl`,
  `ttlExpiry`, and `data` are reserved case-insensitively, and every
  underscore-prefixed top-level name is reserved;
- binary values are outside the portable value model, including binary values
  exposed while serializing a POJO;
- the serialized, structural, field-name, nesting, and partial-update
  field-count limits are enforced uniformly by shared API preflight.


### Query
Portable query request.

Fields:
- `expression`: portable query expression string (SQL-subset WHERE clause syntax)
- `nativeExpression`: provider-specific expression string (mutually exclusive with `expression`)
- `parameters`: key/value collection (named `@paramName` → value)
- `page_size`: optional int
- `continuation_token`: optional string

Validation:
- exactly one of `expression` or `nativeExpression` must be set (or both null for full scan)
- if `expression` is set, it must be valid portable syntax
- all `@paramName` references in the expression must have corresponding entries in `parameters`

### Expression (AST)
Sealed type hierarchy representing a parsed portable expression.

Variants:
- `ComparisonExpression`: field op value (where op is `=`, `<>`, `<`, `>`, `<=`, `>=`)
- `LogicalExpression`: left AND/OR right
- `NotExpression`: NOT child
- `FunctionCallExpression`: portable function with arguments (e.g., `starts_with(field, value)`)
- `InExpression`: field IN (value1, value2, ...)
- `BetweenExpression`: field BETWEEN low AND high

Supporting types:
- `FieldRef`: field name string (supports single-level dot notation, e.g., `address.city`)
- `Literal`: typed literal value (string, number, boolean, null)
- `Parameter`: `@paramName` reference resolved from the parameters map
- `ComparisonOp`: enum (EQ, NE, LT, GT, LE, GE)
- `LogicalOp`: enum (AND, OR)
- `PortableFunction`: enum (STARTS_WITH, CONTAINS, FIELD_EXISTS, STRING_LENGTH, COLLECTION_SIZE)

### ExpressionTranslator
Provider translation interface for translating Expression AST to a
provider-native query string.

Location:
`multiclouddb-api/src/main/java/com/multiclouddb/api/query/ExpressionTranslator.java`

Fields/methods:
- `translate(expression, parameters, container)` → `TranslatedQuery` (native
  string + bound parameters)

Implementations:
- `CosmosExpressionTranslator`: AST → Cosmos SQL WHERE clause (adds `c.` prefix to fields, keeps `@param` names)
- `DynamoExpressionTranslator`: AST → DynamoDB PartiQL WHERE clause (double-quotes table name, converts `@param` to positional `?`)
- `SpannerExpressionTranslator`: AST → Spanner GoogleSQL WHERE clause (bare field names, keeps `@param` names)

### NativeExpression
A tagged wrapper indicating the expression should be passed through to the provider without translation.

Fields:
- `text`: the raw provider-specific expression string
- `targetProvider`: optional provider id (for documentation/validation)

### TranslatedQuery
Result of expression translation.

Fields:
- `nativeExpression`: the translated expression string in provider-native syntax
- `boundParameters`: parameters in the format expected by the provider (named or positional)
- `fullStatement`: optional complete SQL statement (e.g., `SELECT * FROM c WHERE ...` for Cosmos)

### QueryPage
Single page of results.

Fields:
- `items`: list of Document (or row-mapped objects)
- `continuation_token`: optional string
- `diagnostics`: optional `OperationDiagnostics`

### Capability
Named feature/behavior that may be supported or not.

Fields:
- `name`: string
- `supported`: bool
- `notes`: optional string

Well-known query capabilities:
- `PORTABLE_QUERY_EXPRESSION`: provider supports portable expression translation (all providers)
- `LIKE_OPERATOR`: supports `LIKE` pattern matching (Cosmos, Spanner)
- `ORDER_BY`: supports `ORDER BY` in queries (Cosmos, Spanner)
- `ENDS_WITH`: supports `ends_with()` function (Cosmos, Spanner)
- `REGEX_MATCH`: supports regex pattern matching (Cosmos, Spanner)
- `CASE_FUNCTIONS`: supports `LOWER()`/`UPPER()` functions (Cosmos, Spanner)

Well-known write capabilities:
- `PARTIAL_UPDATE = "partial_update"`: provider supports the capability-gated
  shallow literal top-level set/replace contract. Cosmos DB and DynamoDB support
  it in this release; Spanner does not. Portable behavior is guaranteed only
  when both the resulting logical document's serialized JSON and portable
  structural footprint are at or below 390 KiB. Larger state-dependent results
  and TTL timing are outside this release's portable contract.

`CapabilitySet` supplies an unsupported default only for omitted
`PARTIAL_UPDATE`; unrelated omitted well-known names are not synthesized. Each
built-in provider exposes 18 effective capability rows: Cosmos DB and DynamoDB
explicitly declare 18, while Spanner declares 17 and receives the one core
default.

### MulticloudDbError
Provider-neutral error category.

Fields:
- `category`: enum (InvalidRequest, AuthenticationFailed, AuthorizationFailed, NotFound, Conflict, Throttled, TransientFailure, PermanentFailure, ProviderError, UnsupportedCapability)
- `message`: string
- `provider`: provider id
- `operation`: string
- `retryable`: bool
- `provider_details`: optional object (sanitized provider codes, request ids)

### UnsupportedCapability

A non-retryable specialization of `MulticloudDbError` used when an optional
portable operation or envelope is unavailable.

Fields:
- `category`: `UnsupportedCapability`
- `retryable`: `false`
- `provider_details.capability`: canonical capability name (for example
  `partial_update`) when failure occurs at the shared capability gate
- `provider_details.reason`: optional stable provider-limit reason when one
  attempted native update rejects a state-dependent result size

## Relationships
- ClientConfig selects Provider and influences capabilities.
- Client operations use ResourceAddress + Key/Document/Query.
- Query returns `QueryPage`; capability-gated behavior is exposed through `CapabilitySet` and structured errors.
- Errors are raised/returned as MulticloudDbError.

---

## Issue 25 Extensions (FR-049–FR-064)

The following entities were added or modified as part of issue 25 (Result Set Control, TTL/Write Metadata, Uniform Document Size, Provider Diagnostics).

### SortDirection (new enum)

Location: `multiclouddb-api/src/main/java/com/multiclouddb/api/SortDirection.java`

Values:
- `ASC` — ascending order
- `DESC` — descending order

### SortOrder (new class)

Location: `multiclouddb-api/src/main/java/com/multiclouddb/api/SortOrder.java`

Fields:
- `field: String` (required, non-empty — field name to sort on)
- `direction: SortDirection` (required)

Factory: `SortOrder.of(String field, SortDirection direction)`

### QueryRequest (modified)

New optional fields:
- `limit: Integer` — maximum number of items to return (Top N). `null` means no limit. Must be ≥ 1 when set.
- `orderBy: List<SortOrder>` — zero or more sort specifications. Empty list means no ordering.

Builder methods added:
- `limit(int n)` — sets Top N
- `orderBy(String field, SortDirection direction)` — appends a sort specification

Constraints:
- `limit` ≥ 1 when set (validated at construction time)
- `orderBy` is capability-gated; throws `MulticloudDbException(UNSUPPORTED_CAPABILITY)` at query time on providers that do not support ORDER BY (DynamoDB)

### DocumentMetadata (new class)

Location: `multiclouddb-api/src/main/java/com/multiclouddb/api/DocumentMetadata.java`

Fields:
- `lastModified: Instant` — last write timestamp (null if unavailable)
- `ttlExpiry: Instant` — TTL expiry timestamp (null if no TTL or provider doesn't expose it)
- `version: String` — provider-native version/ETag (null if unavailable; ETag on Cosmos)

Provider availability:
| Field | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| `lastModified` | ✅ via `_ts` | ❌ | ❌ |
| `ttlExpiry` | ❌ | ✅ via `ttlExpiry` attr | ❌ |
| `version` | ✅ ETag | ❌ | ❌ |

`metadata()` is null when metadata was not requested. With
`includeMetadata=true`, the metadata envelope is present and callers inspect
the three nullable fields independently; Spanner currently returns an empty
envelope. `WRITE_TIMESTAMP` indicates only whether `lastModified` may be
populated (Cosmos DB true, DynamoDB/Spanner false).

### DocumentResult (new class)

Location: `multiclouddb-api/src/main/java/com/multiclouddb/api/DocumentResult.java`

Fields:
- `document: ObjectNode` (required — the document payload)
- `metadata: DocumentMetadata` — null when
  `OperationOptions.includeMetadata()` is false (the default); otherwise an
  envelope with independently nullable fields

**API impact**: `MulticloudDbClient.read()` return type changed from `JsonNode` to `DocumentResult`. Existing callers use `.document()` to get the payload.

### OperationOptions (modified)

New optional fields:
- `ttlSeconds: Integer` — per-request TTL for write operations (`create`, `upsert`). `null` means no TTL. Must be ≥ 1 when set; providers without `ROW_LEVEL_TTL` ignore it and store without expiry, so callers requiring expiration inspect the capability before writing.
- `includeMetadata: boolean` — whether to return `DocumentMetadata` on read. Default `false`.

Builder methods added:
- `ttlSeconds(int seconds)` — sets TTL for write operations
- `includeMetadata(boolean include)` — enables metadata retrieval on reads
- `OperationOptions.builder()` — entry point for full builder pattern

Backward-compatible factory methods retained:
- `OperationOptions.defaults()` — no timeout, no TTL, no metadata
- `OperationOptions.withTimeout(Duration)` — timeout only shortcut

### Capability (modified — new constants)

New constants added to `Capability`:
- `ROW_LEVEL_TTL = "row_level_ttl"` — provider supports per-document expiry
- `WRITE_TIMESTAMP = "write_timestamp"` — provider exposes last-write timestamp in document metadata
- `RESULT_LIMIT = "result_limit"` — provider supports Top N result capping
- `PARTIAL_UPDATE = "partial_update"` — capability-gated shallow literal
  top-level set/replace; Cosmos DB and DynamoDB supported, Spanner unsupported
  through the API default

### Portable write-input validation

Shared preflight currently enforces:

- serialized input: 399,360 bytes (390 KiB)
- structural footprint: 399,360 bytes (390 KiB)
- nested and partial-update field names: 50,000 UTF-8 bytes
- complete-document top-level field names: 128 Unicode characters
- nested map/list containers: 31
- partial-update fields: 10

Implementation defaults are package-private in
`multiclouddb-api/src/main/java/com/multiclouddb/api/internal/WriteLimits.java`;
they are not part of the public Java API. Applications should consume typed
`INVALID_REQUEST` limit details instead of compiling copied values. Runtime
limit discovery and configuration are deferred to
[#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116).

Primary enforcement:
`multiclouddb-api/src/main/java/com/multiclouddb/api/internal/DocumentSizeValidator.java`

Shared preflight rejects null complete documents; top-level `id`,
`partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, and `data`
case-insensitively; and every underscore-prefixed top-level name. It snapshots
the top-level map and uses bounded SDK-owned Jackson serialization while
validating the complete document in `DefaultMulticloudDbClient.create()` and
`upsert()`, and the incoming field map in `update()`, before provider
delegation. Binary values (including values hidden inside POJOs), field names
above 50,000 UTF-8 bytes, nesting above 31 map/list containers below the document
root, and either 390 KiB limit fail with non-retryable
`MulticloudDbException(INVALID_REQUEST)`.

Partial `update()` is shallow, literal, top-level set/replace with at most 10
fields, no TTL, one native atomic write, and `NOT_FOUND` for a missing item. The
update check does not prevalidate the resulting stored item; its base result
envelope independently requires serialized and structural size at or below
390 KiB. A state-dependent result above either bound is outside this release's
portable contract and may succeed or fail under native provider limits.
Case-distinct names such as `foo` and `Foo` remain separate literal fields.
TTL timing is outside the portable contract: DynamoDB `UpdateItem` happens to
leave `ttlExpiry` unchanged, while Cosmos DB `patchItem` advances `_ts` and
restarts relative TTL. Until behavior is normalized, callers requiring fixed
absolute expiry must not call `update()` on TTL-bearing items.

### Provider Schema Changes

#### Cosmos DB
No schema change. `_ttl` and `_ts` are system-managed properties read from the response.

#### DynamoDB
`ttlExpiry` attribute (Number, epoch seconds) written when TTL is set. Attribute name defined in `DynamoConstants.ATTR_TTL_EXPIRY`.

#### Spanner
`SpannerConstants` class added centralizing all provider string literals (mirrors `CosmosConstants`/`DynamoConstants`).
