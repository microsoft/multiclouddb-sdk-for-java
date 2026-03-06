# Data Model: Hyperscale DB SDK

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

### HyperscaleDbError
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
- Errors are raised/returned as HyperscaleDbError.
