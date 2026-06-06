# Data Model: Multicloud DB SDK

This is a technology-agnostic model for the portable contract.

> **Strict LCD Portability**: As of the strict-LCD release, fields like
> `nativeExpression`, `limit`, arbitrary-field `orderBy`,
> `OperationOptions.ttlSeconds` / `includeMetadata`, and `DocumentMetadata`
> have been removed from the public API. References below are historical;
> see `multiclouddb-api/CHANGELOG.md` for the current model.

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
- must be serializable to a JSON-like representation

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
SPI interface for translating Expression AST to provider-native query string.

Fields/methods:
- `translate(expression, parameters, resourceAddress)` → `TranslatedQuery` (native string + bound parameters)
- `supportedCapabilities()` → set of query-related Capability names

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
- `warnings`: optional list of portability warnings

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

### PortabilityWarning
A structured warning indicating non-portable behavior or provider divergence.

Fields:
- `code`: string
- `message`: string
- `scope`: operation/resource
- `provider`: provider id

### MulticloudDbError
Provider-neutral error category.

Fields:
- `category`: enum (InvalidRequest, AuthenticationFailed, AuthorizationFailed, NotFound, Conflict, Throttled, TransientFailure, PermanentFailure, ProviderError)
- `message`: string
- `provider`: provider id
- `operation`: string
- `retryable`: bool
- `provider_details`: optional object (sanitized provider codes, request ids)

## Relationships
- ClientConfig selects Provider and influences capabilities.
- Client operations use ResourceAddress + Key/Document/Query.
- Query returns QueryPage and may emit PortabilityWarnings.
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
| `lastModified` | ✅ via `_ts` | ❌ | ✅ (schema column) |
| `ttlExpiry` | ✅ via `_ttl`+`_ts` | ✅ via `ttlExpiry` attr | ❌ |
| `version` | ✅ ETag | ❌ | ❌ |

### DocumentResult (new class)

Location: `multiclouddb-api/src/main/java/com/multiclouddb/api/DocumentResult.java`

Fields:
- `document: ObjectNode` (required — the document payload)
- `metadata: DocumentMetadata` — null when `OperationOptions.includeMetadata()` is false (the default)

**API impact**: `MulticloudDbClient.read()` return type changed from `JsonNode` to `DocumentResult`. Existing callers use `.document()` to get the payload.

### OperationOptions (modified)

New optional fields:
- `ttlSeconds: Integer` — per-request TTL for write operations (`create`, `upsert`). `null` means no TTL. Must be ≥ 1 when set.
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

### DocumentSizeValidator (new utility)

Location: `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/DocumentSizeValidator.java`

Static utility:
- `MAX_BYTES = 400 * 1024` (400 KB — DynamoDB hard limit, lowest common denominator)
- `validate(JsonNode document, String operation)` — throws `MulticloudDbException(INVALID_REQUEST)` when serialized UTF-8 size exceeds limit

Applied in `DefaultMulticloudDbClient.create()` and `upsert()` before provider delegation.

### Provider Schema Changes

#### Cosmos DB
No schema change. `_ttl` and `_ts` are system-managed properties read from the response.

#### DynamoDB
`ttlExpiry` attribute (Number, epoch seconds) written when TTL is set. Attribute name defined in `DynamoConstants.ATTR_TTL_EXPIRY`.

#### Spanner
`SpannerConstants` class added centralizing all provider string literals (mirrors `CosmosConstants`/`DynamoConstants`).
