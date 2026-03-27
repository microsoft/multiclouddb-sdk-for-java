# Feature Specification: Hyperscale DB SDK (Unifying Database Client)

**Feature Branch**: `001-clouddb-sdk`  
**Created**: 2026-01-23  
**Status**: Draft  

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Write Once, Run Anywhere CRUD + Query (Priority: P1)

As an application developer, I can use a single SDK interface to perform basic create/update, read, delete, and read-query operations against a chosen cloud database provider, switching providers by configuration only.

**Why this priority**: This is the core value proposition: portability across Cosmos DB, DynamoDB, and Spanner without rewriting the application’s data access layer.

**Independent Test**: A single sample application can run against any supported provider by changing configuration only, and it can create data, read it back, delete it, and query it with paging.

**Acceptance Scenarios**:

1. **Given** a valid configuration for Provider A and an empty collection, **When** the app writes an item and reads it by key, **Then** the returned item matches what was written.
2. **Given** a valid configuration for Provider B and existing items, **When** the app queries with a page size limit, **Then** it receives a page of results and a continuation token (or equivalent) to fetch the next page.

---

### User Story 1b - Portable Query Expressions Across Providers (Priority: P1)

As an application developer, I can write query filter expressions once using a portable SQL-subset syntax with named parameters (`@paramName`) and portable function names (`starts_with`, `contains`, `field_exists`, `string_length`, `collection_size`). The SDK automatically translates these expressions into each provider's native query format (Cosmos DB SQL, DynamoDB PartiQL, Spanner GoogleSQL) so that the same expression produces equivalent results on any provider.

**Why this priority**: Query is a core operation (FR-002). Without a portable expression language, developers must write provider-specific query syntax, which defeats the "write once, run anywhere" goal. This completes the portability story for the read-query operation.

**Independent Test**: A single portable expression (e.g., `status = @status AND starts_with(name, @prefix)`) can be executed against each supported provider and returns equivalent results. Switching providers requires no expression changes.

**Acceptance Scenarios**:

1. **Given** a portable expression `status = @status` with parameter `{"status": "active"}`, **When** executed against Cosmos DB, DynamoDB, and Spanner, **Then** the SDK translates it to each provider's native format and returns matching items consistently.
2. **Given** a portable expression using operators (`=`, `<>`, `<`, `>`, `AND`, `OR`, `NOT`, `IN`, `BETWEEN`) and functions (`starts_with`, `contains`, `field_exists`), **When** executed against any provider, **Then** results are equivalent for equivalent data across all providers.
3. **Given** a complex expression `(status = @s1 OR status = @s2) AND priority > @minP AND NOT contains(title, @excluded)`, **When** executed against any provider, **Then** the SDK correctly translates the full boolean expression tree and returns correct results.
4. **Given** an expression using a capability-gated feature not supported by the target provider (e.g., `LIKE` on DynamoDB), **When** the query is submitted, **Then** the SDK raises a clear error at translation time, before executing the query.

---

### User Story 1c - Native Expression Fallback (Priority: P2)

As an application developer, I can use provider-specific query features (e.g., `LIKE` on Cosmos DB, regex on Spanner) by submitting a native expression that bypasses the portable translator. The SDK passes the expression through directly and clearly signals that this is a non-portable operation.

**Why this priority**: The portable subset covers the intersection of all providers. Developers need an escape hatch for advanced provider-specific queries without losing the SDK's benefits for everything else.

**Independent Test**: A native Cosmos DB SQL expression with `LIKE` executes correctly on Cosmos DB. Attempting to run the same native expression against DynamoDB produces a clear error.

**Acceptance Scenarios**:

1. **Given** a native expression targeting Cosmos DB, **When** executed against Cosmos DB, **Then** it is passed through without translation and returns correct results.
2. **Given** a native expression targeting Cosmos DB, **When** executed against DynamoDB, **Then** the SDK raises a clear error indicating the expression is provider-specific.
3. **Given** a native expression using DynamoDB PartiQL syntax, **When** executed against DynamoDB, **Then** it is passed through directly and returns correct results.

---

### User Story 1d - Portable Resource Provisioning (Priority: P2)

As an application developer, I can use the SDK to ensure that the required database and collection/container/table resources exist before performing data operations, without writing any provider-specific provisioning code or using provider SDKs directly.

**Why this priority**: Applications need database and collection resources to exist before CRUD/query operations can succeed. Without portable provisioning, developers must write provider-specific setup code (Cosmos SDK, DynamoDB SDK, Spanner Admin API), which defeats the "write once, run anywhere" goal and leaks provider details into application code.

**Independent Test**: A sample application can call `provisionSchema` (or individual `ensureDatabase` and `ensureContainer`) to set up its resources, then perform CRUD operations, switching providers by configuration only. No provider-specific provisioning code is needed.

**Acceptance Scenarios**:

1. **Given** a valid configuration for any supported provider, **When** the application calls `ensureDatabase(databaseName)`, **Then** the database/namespace is created if it does not exist, or the call succeeds silently if it already exists.
2. **Given** a valid configuration for any supported provider, **When** the application calls `ensureContainer(resourceAddress)`, **Then** the collection/container/table is created with the SDK's standard schema (partition key, sort key, data column) if it does not exist, or the call succeeds silently if it already exists.
3. **Given** a provider where databases are implicit (e.g., DynamoDB has no explicit database concept), **When** the application calls `ensureDatabase(databaseName)`, **Then** the call succeeds as a no-op without error.
4. **Given** a race condition where two processes simultaneously provision the same resource, **When** both call `ensureContainer`, **Then** both succeed without error (idempotent behavior).
5. **Given** a schema map of multiple databases and collections, **When** the application calls `provisionSchema(schema)`, **Then** all databases and containers are created in parallel without provider-specific code, and the call is equivalent to calling `ensureDatabase` and `ensureContainer` individually for each entry.

---

### User Story 1e - Partition-Key-Scoped Queries (Priority: P1)

As an application developer, I can scope query operations to a specific partition key value, so that the query is executed efficiently using each provider's native partition-scoped mechanism rather than performing a cross-partition scan.

**Why this priority**: Partition-key-scoped queries are fundamental to the performance model of all three supported databases. Without this abstraction, developers must either accept cross-partition scans (poor performance at scale) or write provider-specific query code (breaking portability).

**Independent Test**: A query with `partitionKey("portfolio-alpha")` returns only items within that partition, and the provider uses its native efficient mechanism (Cosmos DB single-partition query, DynamoDB filtered PartiQL query).

**Acceptance Scenarios**:

1. **Given** a collection with items across multiple partition keys, **When** a query specifies `partitionKey("X")`, **Then** only items with partition key value "X" are returned.
2. **Given** a query with both a partition key scope and a filter expression, **When** executed, **Then** the partition scope narrows the search space and the filter is applied within that partition only.
3. **Given** a query without a partition key scope, **When** executed, **Then** it behaves as before (cross-partition scan), maintaining backward compatibility.
4. **Given** a partition key scope on any supported provider, **When** the query is executed, **Then** the provider uses its native efficient partition-scoping mechanism.

---

### User Story 2 - Portability Confidence via Capabilities & Clear Differences (Priority: P2)

As an application developer, I can determine whether a feature/behavior is portable (supported consistently) and I receive clear signals when behavior may differ or a capability is unavailable.

**Why this priority**: Portability only works when users can trust the boundaries; ambiguous “it might work” behavior creates production risk.

**Independent Test**: For any operation that relies on a capability not supported by a provider, the SDK fails fast with a clear, actionable message and exposes a way to detect the capability beforehand.

**Acceptance Scenarios**:

1. **Given** a provider that does not support a requested capability, **When** the application attempts that operation, **Then** it receives a structured error indicating the capability gap and how to handle it.

---

### User Story 3 - Consistent Failure Handling & Diagnostics (Priority: P3)

As an application developer or operator, I can understand and respond to errors consistently across providers, and I can access diagnostics needed to troubleshoot issues without exposing sensitive data.

**Why this priority**: Reliable operations and debuggability are required to adopt the SDK in production.

**Independent Test**: When a request fails (auth, throttling, transient outage), the SDK returns a consistent error category, indicates whether the operation is retryable, and provides identifiers/metadata to aid troubleshooting.

**Acceptance Scenarios**:

1. **Given** invalid credentials, **When** an operation is attempted, **Then** the SDK returns an authentication failure category and does not leak secrets.
2. **Given** a throttling scenario, **When** an operation is attempted, **Then** the SDK indicates throttling and exposes any provider-supplied retry/backoff guidance.

---

### User Story 4 - Opt-in Provider Extensions with Visible Portability Impact (Priority: P2)

As an application developer, I can opt into provider-specific features or behaviors through explicit extensions/hooks, and the SDK clearly denotes that this choice may reduce portability.

**Why this priority**: This preserves “portable-by-default” while still letting advanced users benefit from provider capabilities when they deliberately choose to.

**Independent Test**: A sample app can enable a provider-specific extension and the SDK visibly signals reduced portability (before or at the time of use), while the portable contract remains unchanged when extensions are not enabled.

**Acceptance Scenarios**:

1. **Given** an application that only uses the portable contract, **When** it switches providers by configuration only, **Then** it behaves consistently within the portable contract without requiring code changes.
2. **Given** an application that explicitly enables a provider-specific feature/behavior, **When** the feature/behavior is used, **Then** the SDK emits a clear signal that portability may be reduced and identifies the scope of the non-portable behavior.
3. **Given** an application that does not enable any provider-specific features/behaviors, **When** it performs portable operations, **Then** no provider-specific behavior is implicitly enabled.

---

### User Story 5 - Result Set Control: Top N and Ordering (Priority: P1)

As an application developer, I can limit query results to the first N items and control the sort order (ascending or descending) so that I can efficiently retrieve "most recent" or "top K" results without fetching and discarding excess data.

**Why this priority**: Top N is a fundamental query pattern used heavily in production workloads (e.g., "most recent 10 events", "top 5 positions by value"). Without portable result limiting, applications must fetch all matching items and truncate client-side, which is wasteful and expensive at scale.

**Independent Test**: A query with a result limit of 5 returns at most 5 items on any supported provider. A query with ORDER BY descending on a timestamp field returns items in reverse chronological order, and applying a limit of 1 returns only the most recent item.

**Acceptance Scenarios**:

1. **Given** a collection with 100 items, **When** a query specifies a result limit of 10, **Then** at most 10 items are returned, regardless of how many match the filter.
2. **Given** a collection with timestamped items, **When** a query specifies ORDER BY timestamp descending with a limit of 1, **Then** only the most recently written item is returned.
3. **Given** a query with both a filter expression and a result limit, **When** executed, **Then** the filter is applied first and the limit restricts how many matching items are returned.
4. **Given** a provider that does not support ORDER BY, **When** a query specifies ORDER BY, **Then** the SDK raises a clear error indicating the capability is unavailable.

---

### User Story 6 - Document Time-to-Live and Write Metadata (Priority: P2)

As an application developer, I can set a time-to-live (TTL) on individual documents so they are automatically removed after expiration, and I can retrieve metadata (TTL remaining, last write timestamp) to make data freshness decisions — all without writing provider-specific code.

**Why this priority**: TTL-based automatic expiration is a critical pattern for time-series data, session management, and operational data with defined retention windows. Write timestamps enable applications to determine data freshness when reconciling across multiple sources. Both are heavily used in Cassandra workloads being migrated.

**Independent Test**: A document created with a TTL of 60 seconds is automatically removed after 60 seconds on any provider that supports TTL. Reading the document before expiration returns the TTL remaining and the last write timestamp.

**Acceptance Scenarios**:

1. **Given** a provider that supports row-level TTL, **When** a document is created with a TTL of 300 seconds, **Then** the document is automatically removed after approximately 300 seconds.
2. **Given** a document with TTL set, **When** the document is read, **Then** the response includes metadata indicating the approximate remaining TTL.
3. **Given** a document that was recently written, **When** the document is read, **Then** the response includes metadata indicating the write timestamp.
4. **Given** a provider that does not support row-level TTL, **When** a document is created with a TTL value, **Then** the SDK raises a clear error indicating the capability is unavailable.
5. **Given** a document without TTL set, **When** the document is read, **Then** the TTL metadata is absent or indicates no expiration, and the write timestamp is still available.

---

### User Story 7 - Uniform Document Size and Quota Limits (Priority: P2)

As an application developer, I experience consistent document size limits and quota constraints across all providers, so that my application behaves predictably regardless of which provider is selected.

**Why this priority**: Providers impose different native limits for document size, partition size, and other quotas (e.g., DynamoDB's 400 KB item size vs. Cosmos DB's 2 MB default, or varying logical partition size caps). Without uniform enforcement, applications may work on one provider but fail unexpectedly on another, undermining portability.

**Independent Test**: A document exceeding 400 KB is rejected with a clear error on every provider, and a document within 400 KB is accepted on every provider.

**Acceptance Scenarios**:

1. **Given** a document within the SDK's uniform size limit, **When** it is stored on any provider, **Then** it is persisted successfully.
2. **Given** a document exceeding the SDK's uniform size limit, **When** the application attempts to store it on any provider, **Then** the SDK raises a clear, consistent error indicating the limit before sending the request to the provider.
3. **Given** a provider with a native size limit larger than the SDK's uniform limit, **When** a document exceeding the SDK limit is submitted, **Then** the SDK still rejects it to maintain cross-provider consistency.
4. **Given** quota limits defined by the SDK (e.g., maximum partition size), **When** those limits are approached or exceeded, **Then** the SDK surfaces clear, provider-neutral errors or warnings.

---

### Edge Cases

- What happens when the key model differs by provider (e.g., partitioned keys vs composite primary keys)?
- How does the SDK behave when a continuation token is expired/invalid or used against a different query?
- What happens when the provider enforces different default consistency behaviors?
- How does the SDK handle throttling, quota exhaustion, and rate limits across providers?
- What happens when a collection/database does not exist, or exists with incompatible settings? The SDK provides `ensureDatabase` and `ensureContainer` methods that create resources idempotently, but does not handle incompatible settings (e.g., different partition key paths on an existing container).
- What happens when `ensureContainer` is called concurrently from multiple processes? Each provider implementation must handle race conditions gracefully (e.g., catching "already exists" exceptions).
- What happens when a portable query expression uses a function not supported by the target provider (e.g., `ends_with` on DynamoDB)? The SDK must raise a clear error at translation time, before executing the query.
- What happens when a parameter referenced in the expression is missing from the parameters map? The SDK must raise a validation error before sending the query.
- What happens when the query expression is empty or null? The SDK must return all items (no filter), equivalent to a full scan.
- What happens when a field name in the expression is a reserved word in the target provider (e.g., `status` in DynamoDB)? The SDK must handle escaping/quoting automatically.
- What happens when the expression references a field that does not exist on some documents? Items where the field is absent should not match equality/comparison conditions.
- What happens when a document exceeds the SDK's uniform size limit? The SDK must reject it with a clear error before sending the request to the provider.
- What happens when TTL is set on a provider that does not support row-level TTL? The SDK must fail fast with a clear error.
- What happens when a provider's native quota (e.g., partition size) is exceeded? The SDK must surface the provider error in a provider-neutral format so applications can handle it uniformly.
- What happens when a query with a result limit spans multiple pages? The limit applies to the total result count, not per-page.
- What happens when ORDER BY is requested on a field that is not indexed by the provider? Performance may degrade; the SDK should allow the query but may log a diagnostic warning.
- How does the SDK handle multi-tenant isolation patterns? The SDK does not enforce tenant isolation but supports partition key schemes that enable tenant scoping. Applications can use the existing `partitionKey` query scope (FR-039) to restrict queries to a single tenant's data.

## Portability Defaults

Portability is the default mode of the SDK.

- The SDK’s primary surface is a **portable contract**: features and behaviors that are intended to work the same across supported providers.
- Where providers cannot behave identically, the SDK MUST either:
	- normalize behavior into a consistent portable outcome, or
	- clearly flag the behavior difference and require explicit user choice to proceed in a non-portable way.
- Provider-specific features and provider-specific behaviors MUST be exposed only through **explicit opt-in via SDK configuration**. Code-level escape hatches (e.g., accessing the underlying native client, hooking into query execution, injecting async callbacks) are not supported.
- Diagnostics and tracing opt-ins MUST be expressed through SDK configuration only, not through code-level hooks or callbacks.
- Opting into provider-specific behavior MUST be visible in configuration (no hidden automatic upgrades to provider-specific semantics).

## API Scope

### Synchronous APIs Only (v1)

The initial SDK version exposes **synchronous (blocking) APIs only**.

- All operations (`read`, `upsert`, `delete`, `query`, `ensureDatabase`, `ensureContainer`, `provisionSchema`) return results synchronously.
- **Async APIs are explicitly out of scope for v1.** Reactive or non-blocking variants introduce cross-provider incompatibilities (e.g., Reactor vs. CompletableFuture vs. ListenableFuture) that cannot be abstracted without leaking provider-specific execution models.
- Applications that require async behavior may wrap SDK calls using their own executor or async framework.

### Escape Hatch Policy

The SDK enforces a strict no-code-escape-hatch policy to preserve portability:

- **No code-level escape hatches.** The SDK does not expose the underlying native provider client, provider-specific query hooks, async callbacks, or low-level execution interceptors via its public API.
- **Diagnostics and tracing via configuration only.** Observability features (correlation IDs, cost metrics, DEBUG-level log output) are configured at the SDK level; they cannot be injected or overridden through code-level hooks.
- Unrestricted code-driven escape hatches undermine portability because they encourage application code to depend on provider-specific types, execution models, or behaviors, making it impossible to switch providers without modifying application code.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The SDK MUST allow selecting the target provider (Cosmos DB, DynamoDB, Spanner) through configuration only, without requiring application code changes.
- **FR-002**: The SDK MUST expose a single, provider-neutral client abstraction for core operations: read-by-key, upsert/replace-by-key, delete-by-key, and read-query.
- **FR-003**: The SDK MUST define a portable “resource addressing” scheme that can uniquely identify a logical database/namespace and a logical collection (container/table) for all supported providers.
- **FR-004**: The SDK MUST define a portable key representation that can express the minimum key material required by each provider, and it MUST validate key completeness before issuing a request.
- **FR-005**: The SDK MUST support a portable document payload for common operations and MUST preserve user-provided data fields through write/read cycles.
- **FR-006**: The SDK MUST support paging for read-queries, including requesting a page size and returning a continuation token (or equivalent) when more results exist.
- **FR-007**: The SDK MUST provide explicit capability discovery so applications can determine whether an advanced feature or behavior is supported by the selected provider.
- **FR-008**: When an operation cannot be provided with the same behavior across providers, the SDK MUST clearly flag the difference in a provider-neutral way (documentation and/or structured metadata) before users rely on it.
- **FR-009**: When a requested operation requires an unsupported capability, the SDK MUST fail fast with a structured, actionable error (not a silent no-op).
- **FR-010**: Each operation MUST support caller-controlled timeout and cancellation.
- **FR-011**: The SDK MUST expose retry behavior controls and MUST default to retrying only operations that are safe to retry.
- **FR-012**: The SDK MUST expose diagnostic metadata for each operation (at minimum: provider identifier, operation name, duration, and a correlation/request identifier when available).
- **FR-013**: The SDK MUST provide a standardized, provider-neutral error model that categorizes failures and includes a “retryable” signal.
- **FR-014**: Errors MUST preserve relevant provider details (e.g., provider error codes and request identifiers) in a sanitized form suitable for logging and troubleshooting.
- **FR-015**: The SDK MUST NOT expose the underlying native provider client via its public API. All provider-specific behaviors are accessed through explicit capability-gated SDK features or SDK configuration. Code-level access to provider internals is not supported in v1.
- **FR-016**: Each provider integration MUST be implemented as a provider adapter that conforms to the same contract and can be validated via the shared conformance tests.
- **FR-017**: The SDK MUST provide a conformance test suite that can be executed against each supported provider to verify portability of the minimum contract.
- **FR-018**: The SDK MUST document a compatibility and support policy that states which providers are supported and what “portable” means within the SDK’s guarantees.
- **FR-019**: The SDK MUST define the “portable contract” as the default behavior for all provider-neutral APIs, and it MUST NOT require provider-specific code for the portable contract.
- **FR-020**: Provider-specific features/behaviors MUST be available only via explicit opt-in through SDK configuration and MUST NOT change default portable behavior unless the user explicitly enables them. Code-level escape hatches (hooks, interceptors, direct native client access) are not part of the public API.
- **FR-021**: When a provider-specific feature/behavior is enabled, the SDK MUST make it obvious to the user that portability may be reduced (e.g., via metadata, documentation, and/or structured warnings).

#### Portable Resource Provisioning Requirements

- **FR-033**: The SDK MUST provide `ensureDatabase(String database)` and `ensureContainer(ResourceAddress address)` methods on the public client interface, enabling applications to create required database and collection/container/table resources without provider-specific code.
- **FR-034**: `ensureDatabase` and `ensureContainer` MUST be idempotent: calling them when the resource already exists MUST succeed silently without error.
- **FR-035**: When a provider does not have an explicit concept of a database/namespace (e.g., DynamoDB), `ensureDatabase` MUST succeed as a no-op.
- **FR-036**: `ensureContainer` MUST create the collection/container/table with the SDK's standard schema conventions (partition key, sort key, data column) appropriate for each provider.
- **FR-037**: Provisioning methods MUST handle concurrent creation race conditions gracefully (e.g., catching "resource already exists" exceptions from the underlying provider SDK).
- **FR-038**: Provisioning methods MUST be exposed in the SPI as default no-op methods, allowing providers to override with their specific implementation while maintaining backward compatibility.

#### Bulk Schema Provisioning Requirements

- **FR-043**: The SDK MUST provide `provisionSchema(Map<String, List<String>> schema)` on both the public client and SPI interfaces, enabling applications to provision all databases and containers in a single call.
- **FR-044**: The default SPI implementation of `provisionSchema` MUST create databases in parallel (Phase 1), wait for all to complete, then create containers in parallel (Phase 2), using a bounded thread pool (max 10 threads).
- **FR-045**: Providers MAY override `provisionSchema` for provider-specific optimizations while maintaining the same contract.

#### Cloud Authentication Requirements

- **FR-046**: The Cosmos DB provider MUST support `DefaultAzureCredential` as a fallback when no account key is provided, enabling Managed Identity, Azure CLI, environment variable, and other credential types in the DefaultAzureCredential chain.
- **FR-047**: The SDK MUST NOT introduce a required dependency on management or ARM SDKs (e.g., `azure-resourcemanager-cosmos`). Database and container creation is performed through each provider's standard data-plane SDK and is subject to the caller's runtime permissions (e.g., RBAC role assignments).
- **FR-047a**: When `ensureDatabase` or `ensureContainer` is called and the caller lacks sufficient permissions to create the resource, the SDK MUST fail with a clear, structured authorization error. The operation MUST NOT silently succeed or perform a no-op when the resource does not exist and cannot be created.
- **FR-048**: Advanced provisioning support (e.g., custom throughput, indexing policies, ARM-based control-plane operations) is a **future consideration** and is not part of v1. Applications requiring advanced provisioning should use provider SDKs or infrastructure-as-code tools directly.

#### Partition-Key-Scoped Query Requirements

- **FR-039**: `QueryRequest` MUST support an optional `partitionKey(String value)` builder method that specifies the partition key value to scope the query to.
- **FR-040**: When `partitionKey` is set on a query request, each provider adapter MUST use its native efficient mechanism to scope the query to items with that partition key value:
  - **Cosmos DB**: Set `PartitionKey` on `CosmosQueryRequestOptions` to execute a single-partition query.
  - **DynamoDB**: Add a WHERE condition on the partition key column (`partitionKey`) in PartiQL to filter to items with the matching partition value.
  - **Spanner**: Add a WHERE condition on the `partitionKey` column in GoogleSQL.
- **FR-041**: When `partitionKey` is not set (null), the query MUST behave as a cross-partition query, maintaining backward compatibility with existing behavior.
- **FR-042**: `partitionKey` MUST be combinable with both portable expressions and native expressions — the partition scope narrows the search space, and any filter expression is applied within that scope.

#### Portable Query Expression Requirements

- **FR-022**: The SDK MUST provide a portable query expression syntax using a SQL-subset WHERE clause that supports the following operators: `=`, `<>`, `<`, `>`, `<=`, `>=`, `AND`, `OR`, `NOT`, `IN`, `BETWEEN`.
- **FR-023**: The SDK MUST provide portable function names that each provider adapter translates to native equivalents: `starts_with(field, value)`, `contains(field, value)`, `field_exists(field)`, `string_length(field)`, `collection_size(field)`.
- **FR-024**: The SDK MUST support named parameters using `@paramName` syntax in portable expressions. Parameters are supplied as a name-to-value map alongside the expression.
- **FR-025**: Each provider adapter MUST translate portable expressions into the provider's native query format: Cosmos DB SQL, DynamoDB PartiQL, and Spanner GoogleSQL.
- **FR-026**: The SDK MUST translate field references automatically, adding any required prefixes, aliases, or escaping for the target provider (e.g., `c.` prefix for Cosmos DB, `#name` placeholders for DynamoDB reserved words, double-quoted table names for DynamoDB PartiQL).
- **FR-027**: The SDK MUST support a native expression mode where expressions are passed through directly to the provider without translation, allowing access to provider-specific query features.
- **FR-028**: The SDK MUST validate portable expressions before translation, raising clear errors for: unsupported functions for the target provider, missing parameters, and malformed syntax.
- **FR-029**: When a portable expression is empty or null, the SDK MUST return all items in the collection (equivalent to a full scan).
- **FR-030**: The SDK MUST clearly distinguish between portable expressions and native expressions in the query request, preventing accidental cross-provider execution of native syntax.
- **FR-031**: The SDK MUST support literal values in expressions: strings (single-quoted), numbers (integer and decimal), booleans (`true`/`false`), and `NULL`.
- **FR-032**: The SDK MUST preserve operator precedence: `NOT` binds tightest, then `AND`, then `OR`. Parentheses MUST be supported for explicit grouping.

#### Provider Constants Centralization Requirements

- **FR-049**: Each provider adapter MUST centralize all hard-coded string literals — including configuration keys, field names, query fragments, error messages, and default values — into a provider-specific constants class (e.g., `CosmosConstants`, `DynamoConstants`). Magic strings scattered across implementation classes are not permitted.
- **FR-050**: Operation name strings used in diagnostics, error context, and log lines MUST be defined in a single shared `OperationNames` class in the `hyperscaledb-api` module. Provider adapters MUST reference `OperationNames` constants rather than re-declaring the same strings locally. Provider-specific operation variants (e.g., DynamoDB scan sub-types) that have no equivalent in other providers MAY be defined in the provider's own constants class.
- **FR-051**: Each provider adapter MUST emit structured `DEBUG`-level diagnostic log lines on every successful data-plane operation, capturing provider-native telemetry without requiring a failure to trigger diagnostics. At minimum:
- **FR-052**: Every Java source file in all modules (main and test) MUST include the standard Microsoft MIT copyright header as the first two lines of the file:
  ```
  // Copyright (c) Microsoft Corporation. All rights reserved.
  // Licensed under the MIT License.
  ```
  - **Item operations** (`create`, `read`, `update`, `upsert`, `delete`): log the provider's request correlation ID and cost metric (Cosmos: `activityId` + `requestCharge` RU; DynamoDB: `requestId` + `capacityUnits`).
  - **Query operations**: log cost metric, result count for the page, and whether more pages exist.
  - Log output MUST NOT include secrets, credentials, or user document contents.

#### Result Set Control Requirements

- **FR-052**: `QueryRequest` MUST support an optional result limit (Top N) that restricts the maximum number of items returned by a query. When set, the query returns at most N matching items.
- **FR-053**: `QueryRequest` MUST support an optional ORDER BY clause that specifies one or more fields and an explicit sort direction (ascending or descending) for each field.
- **FR-054**: ORDER BY MUST be a capability-gated feature. When ORDER BY is requested on a provider that does not support it, the SDK MUST raise a clear error at translation time before executing the query.
- **FR-055**: Result limit (Top N) MUST be combinable with filter expressions, partition key scoping, and ORDER BY. The filter and partition scope narrow the result set; ORDER BY controls the sort; and the limit caps the number of returned items.

#### Document TTL and Write Metadata Requirements

- **FR-056**: The SDK MUST support setting a time-to-live (TTL) duration (in seconds) on individual documents during create or upsert operations. When set, the provider MUST automatically remove the document after the specified duration.
- **FR-057**: Row-level TTL MUST be a capability-gated feature. When TTL is set on a provider that does not support row-level TTL, the SDK MUST raise a clear error indicating the capability is unavailable.
- **FR-058**: When reading a document, the SDK MUST return available document metadata — including approximate remaining TTL (when set) and last write timestamp — in a portable metadata envelope alongside the document payload.
- **FR-059**: Document metadata retrieval MUST be optional and opt-in. Applications that do not request metadata MUST NOT incur additional overhead or behavioral changes.

#### Uniform Document Size and Quota Limit Requirements

- **FR-060**: The SDK MUST define a uniform maximum document size that is enforced consistently across all providers. The lowest common denominator is currently DynamoDB's 400 KB item size limit, so the SDK's uniform limit MUST be 400 KB, ensuring documents accepted by the SDK are storable on every provider.
- **FR-061**: The SDK MUST validate document size against the uniform limit before sending the request to the provider and MUST reject oversized documents with a clear, actionable error.
- **FR-062**: The SDK MUST define and document uniform quota limits for provider resources (e.g., maximum logical partition size) so that applications can anticipate constraints regardless of the selected provider.
- **FR-063**: When a provider-specific quota limit is reached (e.g., partition size exceeded, throughput exhausted), the SDK MUST surface the failure through the standard provider-neutral error model with clear categorization and actionable guidance.
- **FR-064**: The SDK MUST expose the configured uniform document size limit and documented quota limits programmatically so applications can perform pre-validation or display limits to end users.

### Portable Operator and Function Reference

The following operators and functions form the portable query subset, available on all supported providers:

**Universal Operators**: `=`, `<>`, `<`, `>`, `<=`, `>=`, `AND`, `OR`, `NOT`, `IN (...)`, `BETWEEN ... AND ...`

**Portable Functions**:

| Portable Name | Description |
|---------------|-------------|
| `starts_with(field, value)` | True if field value starts with the given string |
| `contains(field, value)` | True if field value contains the given substring |
| `field_exists(field)` | True if the field is present and non-null |
| `string_length(field)` | Returns the character length of a string field |
| `collection_size(field)` | Returns the number of elements in an array/list field |

**Capability-Gated Features** (available on some providers, flagged at translation time if unsupported):

| Feature | Available On |
|---------|-------------|
| `ends_with(field, value)` | Cosmos DB, Spanner |
| `LIKE` pattern matching | Cosmos DB, Spanner |
| `LOWER(field)` / `UPPER(field)` | Cosmos DB, Spanner |
| Regex matching | Cosmos DB, Spanner |
| `ORDER BY` (with ASC/DESC direction) | Cosmos DB, Spanner |
| `TOP N` / result limit | Cosmos DB, DynamoDB, Spanner |
| Row-level TTL | Cosmos DB, DynamoDB |
| Write timestamp metadata | Cosmos DB, DynamoDB, Spanner |

### Key Entities *(include if feature involves data)*

- **Provider**: The selected database service (Cosmos DB, DynamoDB, Spanner) plus its identity used for diagnostics and error reporting.
- **Client Configuration**: The set of settings that selects provider and supplies connection/auth details.
- **Resource Address**: The portable identifiers for logical database/namespace and collection.
- **Key**: The portable representation of the minimum key parts required to uniquely identify a record.
- **Document**: A portable, JSON-like payload used for common operations.
- **Query**: A provider-neutral request to retrieve data, including parameters and paging controls.
- **Portable Expression**: A text string using the SQL-subset syntax and portable function names. Represents the WHERE clause of a query without any provider-specific syntax.
- **Expression Parameters**: A map of named parameter values (`@paramName` → value) that are bound to the expression before execution.
- **Expression Translator**: A component within each provider adapter that converts a portable expression into the provider's native query format.
- **Native Expression**: A text string using provider-specific syntax, passed through without translation. Tagged to prevent cross-provider misuse.
- **Resource Provisioning**: The ability to create database and collection resources portably using the provider's data-plane SDK. `ensureDatabase` creates a database/namespace; `ensureContainer` creates a collection/container/table with the SDK's standard schema. `provisionSchema` bulk-creates multiple databases and containers in parallel via a single call. All operations are subject to the caller's runtime permissions; insufficient permissions result in a clear authorization failure. The SDK does not depend on management or ARM SDKs for provisioning.
- **Query Page**: A single page of results and an optional continuation token.
- **Capability**: A named feature/behavior that can be supported or unsupported by a provider.
- **Error**: A provider-neutral categorization of failures with retryability and provider details.
- **Document Metadata**: An optional envelope of system-managed properties returned alongside a document, including approximate remaining TTL and last write timestamp. Not all metadata fields are available on all providers.
- **Result Limit**: An optional constraint on the maximum number of items a query returns (Top N). Applied after filtering and partition scoping.
- **Sort Order**: An optional specification of one or more fields and their sort direction (ascending or descending) for query results. Capability-gated to providers that support ORDER BY.
- **Quota Limit**: A provider-neutral constraint on resource usage (e.g., maximum logical partition size, throughput caps) that the SDK documents and surfaces uniformly across all providers.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can run the same CRUD + query sample application against Cosmos DB, DynamoDB, and Spanner by changing configuration only, in under 10 minutes per provider.
- **SC-002**: The conformance test suite passes at least 95% of scenarios for each supported provider, with any exceptions explicitly documented as portability gaps.
- **SC-003**: In 100% of cases where an operation relies on an unsupported capability, the SDK returns a structured, actionable error and exposes the capability state that explains the failure.
- **SC-004**: For failures in production-like environments, a developer can identify the provider, operation, and correlation/request identifier from SDK diagnostics in under 5 minutes.
- **SC-005**: Users report improved confidence in portability (at least 80% positive feedback in internal/beta surveys) due to consistent behaviors and clearly flagged differences.
- **SC-006**: A single portable query expression written by a developer produces correct, equivalent results on all three supported providers without modification.
- **SC-007**: All five portable functions (`starts_with`, `contains`, `field_exists`, `string_length`, `collection_size`) translate correctly and return accurate results on every provider.
- **SC-008**: Attempting to use an unsupported capability-gated query feature on a provider that lacks it produces a clear, descriptive error message before executing the query.
- **SC-009**: Native expression mode allows full access to each provider's proprietary query features without interference from the portable expression translator.
- **SC-010**: Complex expressions combining 3 or more conditions with mixed boolean logic (`AND`, `OR`, `NOT`, parentheses) produce correct results on all providers.
- **SC-011**: A developer can provision database and collection resources using `ensureDatabase` and `ensureContainer` without any provider-specific code, and the same provisioning code works across all providers by changing configuration only.
- **SC-012**: Calling `ensureDatabase` and `ensureContainer` on resources that already exist succeeds idempotently without error on all providers.
- **SC-015**: `provisionSchema` creates all specified databases and containers in parallel, equivalent to individual `ensureDatabase`/`ensureContainer` calls, on all supported providers.
- **SC-016**: The Cosmos DB provider authenticates via `DefaultAzureCredential` when no account key is provided. When the caller holds sufficient RBAC permissions for control-plane operations, `ensureDatabase` succeeds; when permissions are insufficient, the SDK returns a clear, structured authorization failure rather than silently succeeding or hanging.
- **SC-013**: A query with `partitionKey` set returns only items within that partition on all supported providers.
- **SC-014**: A query with both `partitionKey` and a filter expression correctly scopes to the partition first, then applies the filter, on all supported providers.
- **SC-017**: All operation name strings used in diagnostics, error context, and log lines across all provider adapters are sourced from `OperationNames` in `hyperscaledb-api`. No provider adapter re-declares a shared operation name string locally; duplicates that would cause log-correlation ambiguity are caught by `OperationNamesTest` at compile/test time.
- **SC-018**: On every successful data-plane operation, the SDK emits a `DEBUG`-level diagnostic log line capturing the provider's native correlation ID and cost metric. A developer can correlate SDK log output with Azure portal Activity IDs or AWS CloudTrail request IDs without requiring a failure to trigger the diagnostic.
- **SC-019**: A query with a result limit of N returns at most N items on all supported providers, regardless of how many items match the filter.
- **SC-020**: A query specifying ORDER BY with a descending sort direction returns items in reverse order on all providers that support ORDER BY. Providers that do not support ORDER BY produce a clear, actionable error.
- **SC-021**: A document created with a TTL of T seconds is automatically removed after approximately T seconds on all providers that support row-level TTL.
- **SC-022**: Reading a document returns write timestamp metadata on all providers that support it, enabling applications to determine data freshness within 1 second of accuracy.
- **SC-023**: A document within the SDK's uniform size limit can be stored and retrieved identically on all supported providers. A document exceeding the uniform limit is rejected with a clear, consistent error on every provider, regardless of individual provider native limits.
- **SC-024**: When a provider-specific quota limit is reached (e.g., partition size exceeded), the SDK surfaces a provider-neutral error with clear categorization and actionable guidance, consistent across all providers.
- **SC-025**: Every Java source file (main and test) across all modules carries the standard Microsoft copyright header (`// Copyright (c) Microsoft Corporation. All rights reserved.` / `// Licensed under the MIT License.`) as its first two lines. A `LICENSE` file exists at the repository root with the full MIT license text. Both are verifiable by inspection of any file in the repository.

## Assumptions

- The initial MVP targets a portable core (CRUD + read-query + paging) and treats higher-level semantics (e.g., complex transactions, stored procedures, triggers) as capability-gated or provider-specific.
- “Write once, run anywhere” means “same application code for the portable contract”; provider-specific configuration and credentials are expected to vary.
- When providers differ in unavoidable ways, the SDK's responsibility is to (1) make differences visible, and (2) provide safe defaults and clear guidance, not to hide differences.
- The DynamoDB adapter will use PartiQL (`executeStatement`) as the primary backend for portable query expressions rather than native Scan + FilterExpression. PartiQL provides SQL-like syntax closer to Cosmos DB and Spanner, simplifying translation. Performance is equivalent.
- Nested property access (e.g., `address.city`) is limited to single-level dot notation in the portable subset. Deeply nested or array-indexed access is provider-specific.
- The portable query subset targets WHERE-clause filtering only. Projections (SELECT specific fields), aggregations (COUNT, SUM, etc.), and joins are outside the current scope.
- `field_exists` maps to `IS_DEFINED` on Cosmos DB, `IS NOT MISSING` on DynamoDB PartiQL, and `IS NOT NULL` on Spanner. This is a semantic approximation: on Spanner, a column always exists in the schema, so the check tests for non-null values.
- Queries MUST support partition-key-scoped execution. When a partition key value is specified on a query request, the SDK MUST use each provider's native efficient mechanism to scope the query to that partition only (e.g., Cosmos DB `setPartitionKey()` on query options, DynamoDB PartiQL WHERE condition on the partition key column). Queries without a partition key scope may still result in cross-partition scans. Applications SHOULD use `Key.of(partitionKey, sortKey)` to co-locate related documents and then scope queries by partition key for efficient retrieval.
- `ensureDatabase`, `ensureContainer`, and `provisionSchema` are convenience methods for development and startup scenarios. They create resources with the SDK's standard schema defaults using the provider's data-plane SDK, and are subject to the caller's runtime permissions (e.g., RBAC role assignments). They are not intended for advanced provisioning (e.g., custom throughput, indexing policies, ARM-based control-plane operations). For production provisioning with fine-grained control, developers should use provider SDKs or infrastructure-as-code tools directly. Advanced provisioning support is a future consideration outside v1 scope.
- The SDK does not introduce a dependency on management or ARM SDKs (e.g., `azure-resourcemanager-cosmos`). Provisioning operations (`ensureDatabase`, `ensureContainer`) use the provider's standard data-plane SDK and succeed only when the caller holds sufficient runtime permissions. When operating in RBAC/`DefaultAzureCredential` mode, `ensureDatabase` requires the caller to hold an appropriate control-plane role (e.g., Cosmos DB Operator). If the required role is not assigned, the SDK returns a clear authorization failure. Advanced provisioning requiring ARM access is a future consideration outside v1 scope.
- **SDK versions (current)**: Azure Cosmos DB SDK 4.78.0, AWS SDK v2 2.34.0, Azure Identity 1.18.2. Minimum Java version is 17. These versions represent the latest stable releases validated against this SDK; newer versions may be adopted as long as the portable contract is preserved. Management and ARM SDK dependencies (e.g., `azure-resourcemanager-cosmos`, `azure-core-management`) are not part of the SDK's required dependency set.
- **Dependency security**: Transitive dependency versions are managed in the root `pom.xml` `dependencyManagement` section and explicit overrides in child poms to resolve known CVEs: `jackson-core` ≥ 2.18.6 (GHSA-72hv-8253-57qq), `logback-classic`/`logback-core` ≥ 1.5.25 (CVE-2024-12798, CVE-2024-12801, CVE-2025-11226, CVE-2026-1225), `netty-codec-http` ≥ 4.2.8.Final (CVE-2025-67735). IDE CVE scanner (Mend.io) warnings that persist after overrides are documented as false positives in `.mend/mend.yml`; the actual resolved versions are confirmed safe via `mvn dependency:tree`.
- **Test infrastructure**: Mockito's Byte Buddy instrumentation engine does not officially support Java versions beyond 22. Provider test modules that mock SDK exception classes (e.g., `CosmosException`, `DynamoDbException`) MUST configure `maven-surefire-plugin` with `-Dnet.bytebuddy.experimental=true` in `argLine` to enable mocking on Java 23+. This is set in the `hyperscaledb-provider-cosmos` and `hyperscaledb-provider-dynamo` poms.
- **Operation name constants**: The `OperationNames` class in `hyperscaledb-api` is the canonical source for all shared operation name strings. It is on the classpath of every provider via the `providers → hyperscaledb-api` dependency chain. IDE "unused field" warnings on constants classes are suppressed via `@SuppressWarnings("unused")` because single-file IDE analysis cannot see cross-file usages.
- **Diagnostics log format**: Success-path diagnostic log lines use the prefix `cosmos.diagnostics` or `dynamo.diagnostics` followed by key=value pairs: `op`, `db`, `col`, and provider-specific fields (`activityId`/`requestId`, `requestCharge`/`capacityUnits`, `statusCode`/`itemCount`/`hasMore`). Log lines are emitted at `DEBUG` level only and contain no secrets or document contents.
- Properties files containing credentials or connection secrets (e.g., `*.properties` with endpoint/key values) MUST be gitignored and MUST NOT be committed to source control. Template files (`*.properties.template`) with placeholder values are provided so users can copy them, fill in their credentials, and keep the result local-only.
- Cleanup scripts for removing provider resources (containers, tables, databases) created during sample runs are provided under `hyperscaledb-samples/scripts/` for each supported provider, in both Bash and PowerShell variants.
- Sample application output banners use fixed-width ASCII box-drawing characters (printable ASCII only, no Unicode box-drawing code points) to ensure consistent rendering across all terminal environments and operating systems.
- **Row-level TTL scope**: The SDK supports row-level (document-level) TTL only. Cell-level TTL (per-field expiration) as found in Cassandra is not supported because the native TTL engines in Cosmos DB and DynamoDB operate at the row/item level. Applications migrating from Cassandra cell-level TTL should evaluate whether row-level TTL is sufficient for their use case.
- **TTL precision**: TTL expiration is approximate. Providers may enforce TTL with varying granularity (e.g., Cosmos DB checks TTL in the background periodically, DynamoDB typically deletes within 48 hours of expiration). The SDK does not guarantee exact-second expiration precision.
- **Write timestamp source**: Write timestamps are sourced from the provider's system metadata (e.g., Cosmos DB `_ts`, DynamoDB stream record timestamps). The SDK does not maintain its own write timestamps. Precision and availability may vary by provider.
- **Uniform document size limit**: The SDK enforces a single maximum document size of 400 KB across all providers, driven by DynamoDB's 400 KB item size limit as the lowest common denominator. Cosmos DB natively supports up to 2 MB (or 10 MB for eligible accounts) and Spanner supports up to 10 MB, but the SDK enforces the 400 KB ceiling to guarantee cross-provider portability. Applications needing to store larger documents should use provider-specific escape hatches or external storage patterns.
- **Uniform quota limits**: The SDK documents and surfaces provider quota constraints (e.g., logical partition size limits, throughput caps) in a uniform way. While exact quota values may differ by provider, the SDK ensures that quota-related failures are reported through the standard error model with consistent categorization.
- **Multi-tenancy patterns**: The SDK does not enforce tenant isolation. Multi-tenant applications can use partition key schemes to scope data by tenant (e.g., including an organization code in the partition key value) and use the existing `partitionKey` query scope (FR-039) to restrict queries to a single tenant's data. For stronger isolation (per-tenant encryption, noisy-neighbor protection), applications should use collection-per-tenant or account-per-tenant patterns with provider fleet management features. This is a deployment architecture decision, not an SDK-level concern.
- Every Java source file in all modules (main and test) carries the standard Microsoft MIT copyright header as its first two lines. This applies to all 117 Java files across `hyperscaledb-api`, `hyperscaledb-conformance`, `hyperscaledb-provider-cosmos`, `hyperscaledb-provider-dynamo`, `hyperscaledb-provider-spanner`, and `hyperscaledb-samples`. A `LICENSE` file at the repository root contains the full MIT license text. The per-file header is:
  ```
  // Copyright (c) Microsoft Corporation. All rights reserved.
  // Licensed under the MIT License.
  ```

## Acceptance Checklist

This checklist is used to accept the feature as “done” at the spec level.

### Portability (P1)

- [ ] The same application code can perform write, read-by-key, delete-by-key, and read-query against each supported provider by changing configuration only.
- [ ] Read-queries support paging with a page size control and a continuation token (or clearly documented equivalent).
- [ ] Default usage of the provider-neutral APIs uses the portable contract and does not require provider-specific code paths.

### Capability Signaling (P2)

- [ ] The SDK exposes a way to determine whether a capability/behavior is supported before attempting an operation that depends on it.
- [ ] Attempting an operation requiring an unsupported capability fails fast with a structured, actionable error.
- [ ] Any known cross-provider behavior differences in the portable surface are clearly flagged for users.
- [ ] Provider-specific features/behaviors require explicit opt-in and are clearly denoted as reducing portability.

### Errors & Diagnostics (P3)

- [ ] Failures are categorized in a provider-neutral way and indicate whether they are retryable.
- [ ] Errors include provider and operation context and preserve provider identifiers/codes in sanitized form.
- [ ] Diagnostics are available for each operation (provider id, operation name, duration, correlation/request id when available) and do not leak secrets.

### Conformance & Confidence

- [ ] A shared conformance test suite exists and can be executed against each supported provider.
- [ ] Each provider adapter is validated against the same conformance suite for the minimum portable contract.

### Portable Query Expressions

- [ ] A portable query expression using the SQL-subset syntax and named `@param` parameters produces correct, equivalent results on all supported providers.
- [ ] All five portable functions (`starts_with`, `contains`, `field_exists`, `string_length`, `collection_size`) translate correctly to each provider's native equivalents.
- [ ] Capability-gated query features (e.g., `LIKE`, `ORDER BY`, `ends_with`) raise clear errors at translation time on providers that do not support them.
- [ ] Native expression mode passes expressions through to the provider without translation and is clearly distinguished from portable expressions.
- [ ] Field names that are reserved words in the target provider are escaped/quoted automatically by the translator.

### Partition-Key-Scoped Queries

- [ ] A query with `partitionKey` set returns only items within the specified partition on all supported providers.
- [ ] A query combining `partitionKey` with a portable expression filters within the partition on all providers.
- [ ] Queries without `partitionKey` continue to work as cross-partition scans (backward compatible).
- [ ] The sample application demonstrates partition-key-scoped queries with correctly partitioned data (e.g., positions partitioned by portfolioId).

### Result Set Control (Top N / ORDER BY)

- [ ] A query with a result limit returns at most N items on all supported providers.
- [ ] A query with ORDER BY and explicit sort direction (ASC/DESC) returns correctly ordered results on providers that support it.
- [ ] A query combining result limit with ORDER BY returns the top/bottom N items in the specified order.
- [ ] A query combining result limit with filter expressions and partition key scoping works correctly.
- [ ] Requesting ORDER BY on a provider that does not support it raises a clear, capability-gated error at translation time.

### Document TTL and Write Metadata

- [ ] A document created with a TTL value is automatically removed after the specified duration on providers that support row-level TTL.
- [ ] Reading a document with TTL returns the approximate remaining TTL in the document metadata.
- [ ] Reading a document returns the write timestamp in the document metadata on providers that support it.
- [ ] Setting TTL on a provider that does not support row-level TTL raises a clear, capability-gated error.
- [ ] Documents without TTL set do not expire and return absent/no-expiration TTL metadata.
- [ ] Document metadata retrieval is opt-in and does not affect applications that do not request it.

### Uniform Document Size and Quota Limits

- [ ] A document within the SDK's uniform size limit is accepted and persisted on every supported provider.
- [ ] A document exceeding the SDK's uniform size limit is rejected with a clear, consistent error on every provider, even if the provider's native limit is larger.
- [ ] The SDK's uniform document size limit and quota limits are exposed programmatically for application pre-validation.
- [ ] When a provider-specific quota limit is reached, the SDK surfaces a provider-neutral error with clear categorization and actionable guidance.
- [ ] Quota-related errors are consistent in format across all providers.

### Escape Hatch Policy

- [ ] The SDK does not expose the underlying native provider client or any code-level hooks via its public API.
- [ ] Diagnostics and tracing configurations are exposed through SDK configuration only and do not require code-level hooks.
- [ ] Provider-specific opt-in behaviors are accessible only through SDK configuration, not through code injection or native client access.

### Resource Provisioning

- [ ] `ensureDatabase` creates a database/namespace if it does not exist, or succeeds silently if it already exists, on all supported providers.
- [ ] `ensureContainer` creates a collection/container/table with the SDK's standard schema if it does not exist, or succeeds silently if it already exists, on all supported providers.
- [ ] Provisioning requires no provider-specific code in the application; the same calls work for Cosmos DB, DynamoDB, and Spanner.
- [ ] Providers where a concept does not apply (e.g., DynamoDB has no explicit database) handle the call as a no-op without error.
- [ ] `provisionSchema` bulk-provisions multiple databases and containers in parallel via a single call, equivalent to individual `ensureDatabase`/`ensureContainer` calls.

### Cloud Authentication

- [ ] The Cosmos DB provider authenticates via `DefaultAzureCredential` when no account key is configured, supporting Managed Identity, Azure CLI, and environment variable credentials.
- [ ] The SDK does not depend on management or ARM SDKs. Provisioning operations are executed through the provider's standard data-plane SDK.
- [ ] When `ensureDatabase` or `ensureContainer` is called and the caller lacks sufficient RBAC permissions, the SDK returns a clear, structured authorization failure error.

### Code Quality & Developer Experience

- [ ] Each provider adapter has a dedicated constants class (e.g., `CosmosConstants`, `DynamoConstants`) that centralizes all magic strings — config keys, field names, query fragments, error messages, and default values. No hard-coded string literals are scattered across implementation classes.
- [ ] All operation name strings used in diagnostics, error context, and log lines are sourced from `OperationNames` in `hyperscaledb-api`. Provider adapters do not re-declare shared operation name strings locally.
- [ ] Each provider adapter emits structured `DEBUG`-level diagnostic log lines on every successful data-plane operation (item ops and query ops), capturing provider-native correlation IDs and cost metrics without requiring a failure to trigger diagnostics.
- [ ] `OperationNames` is covered by a unit test (`OperationNamesTest`) that verifies every constant has the expected value, none are null or blank, and all 9 are unique (preventing silent log-correlation ambiguity).
- [ ] `CosmosConstants` is covered by `CosmosConstantsTest` verifying every field value, including connection mode defaults, consistency level, document field names, partition key path, page size, query defaults, and error messages.
- [ ] `DynamoConstants` is covered by `DynamoConstantsTest` verifying every field value, plus cross-cutting assertions that DynamoDB-specific operation variant names are unique and do not collide with any shared `OperationNames` constant.
- [ ] `CosmosErrorMappingTest` covers all HTTP status code → category mappings, retryability flags, provider details (including `activityId` and `requestCharge`), and a parameterized sub-status code test covering real-world Cosmos sub-status values (0, 1002, 1008, 1022, 5300).
- [ ] `DynamoErrorMappingTest` uses `OperationNames.*` constants for all operation name assertions, covering error code mappings, status code fallbacks, retryability, provider details, and cause preservation.
- [ ] `DiagnosticsConformanceTest` uses `OperationNames.*` for operation name assertions in all three providers' test subclasses.
- [ ] Provider poms configure `maven-surefire-plugin` with `-Dnet.bytebuddy.experimental=true` to enable Mockito mocking of SDK exception classes on Java 23+.
- [ ] Transitive CVE dependencies (`jackson-core`, `logback-core`, `netty-codec-http`) are pinned to patched versions in both `<dependencies>` and `<dependencyManagement>` of each provider pom. Confirmed safe via `mvn dependency:tree`.
- [ ] A `.mend/mend.yml` suppression config documents false-positive CVE findings from the IDE Mend.io scanner, with justification for each suppression.
- [ ] Properties template files (`*.properties.template`) are provided for each sample scenario, enabling users to copy and fill in credentials locally. Actual properties files containing credentials are gitignored and never committed.
- [ ] Cleanup scripts (`cleanup-cosmos.sh`, `cleanup-cosmos.ps1`, `cleanup-dynamo.sh`, `cleanup-dynamo.ps1`) exist under `hyperscaledb-samples/scripts/` and successfully remove all provider resources created by sample runs.
- [ ] Every Java source file (main and test) in all modules carries the standard Microsoft copyright header as the first two lines: `// Copyright (c) Microsoft Corporation. All rights reserved.` followed by `// Licensed under the MIT License.`
- [ ] A `LICENSE` file exists at the repository root containing the full MIT license text with `Copyright (c) Microsoft Corporation. All rights reserved.`

