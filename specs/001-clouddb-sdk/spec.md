# Feature Specification: Hyperscale DB SDK (Unifying Database Client)

**Feature Branch**: `001-clouddb-sdk`  
**Created**: 2026-01-23  
**Status**: Draft  
**Input**: User description: "We are building a new SDK called \"Hyperscale DB SDK\" which will abstract SDK/client driver semantics for three different cloud native database services: Azure Cosmos DB, AWS DynamoDB, and Google Spanner. The goal will be to abstract the best elements of all three and create a unifying experience that allows code written using the SDK to be \"written once, run anyway\" (in any cloud, with only config dictating which underlying database is used). The SDK should give its users confidence in its portability by ensuring that both feature sets and behaviours (where possible) are the same (or clearly flagged when potential differences are possible)."

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

## Portability Defaults

Portability is the default mode of the SDK.

- The SDK’s primary surface is a **portable contract**: features and behaviors that are intended to work the same across supported providers.
- Where providers cannot behave identically, the SDK MUST either:
	- normalize behavior into a consistent portable outcome, or
	- clearly flag the behavior difference and require explicit user choice to proceed in a non-portable way.
- Provider-specific features and provider-specific behaviors MUST be exposed only through **explicit opt-in** (extensions/hooks/escape hatch) so that “happy path” usage remains seamless and portable by default.
- Opting into provider-specific behavior MUST be visible in code or configuration (no hidden automatic upgrades to provider-specific semantics).

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
- **FR-015**: The SDK MUST provide an escape hatch that allows advanced users to access the underlying native provider client when needed.
- **FR-016**: Each provider integration MUST be implemented as a provider adapter that conforms to the same contract and can be validated via the shared conformance tests.
- **FR-017**: The SDK MUST provide a conformance test suite that can be executed against each supported provider to verify portability of the minimum contract.
- **FR-018**: The SDK MUST document a compatibility and support policy that states which providers are supported and what “portable” means within the SDK’s guarantees.
- **FR-019**: The SDK MUST define the “portable contract” as the default behavior for all provider-neutral APIs, and it MUST NOT require provider-specific code for the portable contract.
- **FR-020**: Provider-specific features/behaviors MUST be available only via explicit opt-in (extensions/hooks/escape hatch) and MUST NOT change default portable behavior unless the user explicitly enables them.
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
- **FR-047**: When using `DefaultAzureCredential` (RBAC mode), the Cosmos DB provider MUST use the Azure Resource Manager SDK (`azure-resourcemanager-cosmos`) for database creation, because Cosmos data-plane RBAC does not support control-plane operations.
- **FR-048**: The management SDK configuration (`subscriptionId`, `resourceGroupName`) MUST be optional; when absent, provisioning falls back to data-plane calls with a warning log.

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
  - **Item operations** (`create`, `read`, `update`, `upsert`, `delete`): log the provider's request correlation ID and cost metric (Cosmos: `activityId` + `requestCharge` RU; DynamoDB: `requestId` + `capacityUnits`).
  - **Query operations**: log cost metric, result count for the page, and whether more pages exist.
  - Log output MUST NOT include secrets, credentials, or user document contents.

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
| `ORDER BY` | Cosmos DB, Spanner |

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
- **Resource Provisioning**: The ability to create database and collection resources portably. `ensureDatabase` creates a database/namespace; `ensureContainer` creates a collection/container/table with the SDK's standard schema. `provisionSchema` bulk-creates multiple databases and containers in parallel via a single call.
- **Query Page**: A single page of results and an optional continuation token.
- **Capability**: A named feature/behavior that can be supported or unsupported by a provider.
- **Error**: A provider-neutral categorization of failures with retryability and provider details.

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
- **SC-016**: The Cosmos DB provider authenticates via `DefaultAzureCredential` when no account key is provided, and uses the Azure Resource Manager SDK for database creation in RBAC mode.
- **SC-013**: A query with `partitionKey` set returns only items within that partition on all supported providers.
- **SC-014**: A query with both `partitionKey` and a filter expression correctly scopes to the partition first, then applies the filter, on all supported providers.
- **SC-017**: All operation name strings used in diagnostics, error context, and log lines across all provider adapters are sourced from `OperationNames` in `hyperscaledb-api`. No provider adapter re-declares a shared operation name string locally; duplicates that would cause log-correlation ambiguity are caught by `OperationNamesTest` at compile/test time.
- **SC-018**: On every successful data-plane operation, the SDK emits a `DEBUG`-level diagnostic log line capturing the provider's native correlation ID and cost metric. A developer can correlate SDK log output with Azure portal Activity IDs or AWS CloudTrail request IDs without requiring a failure to trigger the diagnostic.

## Assumptions

- The initial MVP targets a portable core (CRUD + read-query + paging) and treats higher-level semantics (e.g., complex transactions, stored procedures, triggers) as capability-gated or provider-specific.
- “Write once, run anywhere” means “same application code for the portable contract”; provider-specific configuration and credentials are expected to vary.
- When providers differ in unavoidable ways, the SDK's responsibility is to (1) make differences visible, and (2) provide safe defaults and clear guidance, not to hide differences.
- The DynamoDB adapter will use PartiQL (`executeStatement`) as the primary backend for portable query expressions rather than native Scan + FilterExpression. PartiQL provides SQL-like syntax closer to Cosmos DB and Spanner, simplifying translation. Performance is equivalent.
- Nested property access (e.g., `address.city`) is limited to single-level dot notation in the portable subset. Deeply nested or array-indexed access is provider-specific.
- The portable query subset targets WHERE-clause filtering only. Projections (SELECT specific fields), aggregations (COUNT, SUM, etc.), and joins are outside the current scope.
- `field_exists` maps to `IS_DEFINED` on Cosmos DB, `IS NOT MISSING` on DynamoDB PartiQL, and `IS NOT NULL` on Spanner. This is a semantic approximation: on Spanner, a column always exists in the schema, so the check tests for non-null values.
- Queries MUST support partition-key-scoped execution. When a partition key value is specified on a query request, the SDK MUST use each provider's native efficient mechanism to scope the query to that partition only (e.g., Cosmos DB `setPartitionKey()` on query options, DynamoDB PartiQL WHERE condition on the partition key column). Queries without a partition key scope may still result in cross-partition scans. Applications SHOULD use `Key.of(partitionKey, sortKey)` to co-locate related documents and then scope queries by partition key for efficient retrieval.
- `ensureDatabase`, `ensureContainer`, and `provisionSchema` are convenience methods for development and startup scenarios. They create resources with the SDK's standard schema defaults and are not intended for advanced provisioning (e.g., custom throughput, indexing policies, TTL settings). For production provisioning with fine-grained control, developers should use provider SDKs directly or infrastructure-as-code tools.
- The Cosmos DB provider uses the Azure Resource Manager management SDK (`azure-resourcemanager-cosmos`) for database creation when operating in RBAC/`DefaultAzureCredential` mode, because Cosmos data-plane RBAC does not have permissions for control-plane operations like creating databases. Key-based authentication continues to use data-plane calls.
- **SDK versions (current)**: Azure Cosmos DB SDK 4.78.0, AWS SDK v2 2.34.0, Azure Resource Manager Cosmos 2.54.1, Azure Identity 1.18.2, Azure Core Management 1.19.3. Minimum Java version is 17. These versions represent the latest stable releases validated against this SDK; newer versions may be adopted as long as the portable contract is preserved.
- **Dependency security**: Transitive dependency versions are managed in the root `pom.xml` `dependencyManagement` section and explicit overrides in child poms to resolve known CVEs: `jackson-core` ≥ 2.18.6 (GHSA-72hv-8253-57qq), `logback-classic`/`logback-core` ≥ 1.5.25 (CVE-2024-12798, CVE-2024-12801, CVE-2025-11226, CVE-2026-1225), `netty-codec-http` ≥ 4.2.8.Final (CVE-2025-67735). IDE CVE scanner (Mend.io) warnings that persist after overrides are documented as false positives in `.mend/mend.yml`; the actual resolved versions are confirmed safe via `mvn dependency:tree`.
- **Test infrastructure**: Mockito's Byte Buddy instrumentation engine does not officially support Java versions beyond 22. Provider test modules that mock SDK exception classes (e.g., `CosmosException`, `DynamoDbException`) MUST configure `maven-surefire-plugin` with `-Dnet.bytebuddy.experimental=true` in `argLine` to enable mocking on Java 23+. This is set in the `hyperscaledb-provider-cosmos` and `hyperscaledb-provider-dynamo` poms.
- **Operation name constants**: The `OperationNames` class in `hyperscaledb-api` is the canonical source for all shared operation name strings. It is on the classpath of every provider via the `providers → hyperscaledb-spi → hyperscaledb-api` dependency chain. IDE "unused field" warnings on constants classes are suppressed via `@SuppressWarnings("unused")` because single-file IDE analysis cannot see cross-file usages.
- **Diagnostics log format**: Success-path diagnostic log lines use the prefix `cosmos.diagnostics` or `dynamo.diagnostics` followed by key=value pairs: `op`, `db`, `col`, and provider-specific fields (`activityId`/`requestId`, `requestCharge`/`capacityUnits`, `statusCode`/`itemCount`/`hasMore`). Log lines are emitted at `DEBUG` level only and contain no secrets or document contents.
- Properties files containing credentials or connection secrets (e.g., `*.properties` with endpoint/key values) MUST be gitignored and MUST NOT be committed to source control. Template files (`*.properties.template`) with placeholder values are provided so users can copy them, fill in their credentials, and keep the result local-only.
- Cleanup scripts for removing provider resources (containers, tables, databases) created during sample runs are provided under `hyperscaledb-samples/scripts/` for each supported provider, in both Bash and PowerShell variants.
- Sample application output banners use fixed-width ASCII box-drawing characters (printable ASCII only, no Unicode box-drawing code points) to ensure consistent rendering across all terminal environments and operating systems.

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

### Escape Hatch

- [ ] Advanced users can access the underlying provider-native client when required, without breaking the portable surface.

### Resource Provisioning

- [ ] `ensureDatabase` creates a database/namespace if it does not exist, or succeeds silently if it already exists, on all supported providers.
- [ ] `ensureContainer` creates a collection/container/table with the SDK's standard schema if it does not exist, or succeeds silently if it already exists, on all supported providers.
- [ ] Provisioning requires no provider-specific code in the application; the same calls work for Cosmos DB, DynamoDB, and Spanner.
- [ ] Providers where a concept does not apply (e.g., DynamoDB has no explicit database) handle the call as a no-op without error.
- [ ] `provisionSchema` bulk-provisions multiple databases and containers in parallel via a single call, equivalent to individual `ensureDatabase`/`ensureContainer` calls.

### Cloud Authentication

- [ ] The Cosmos DB provider authenticates via `DefaultAzureCredential` when no account key is configured, supporting Managed Identity, Azure CLI, and environment variable credentials.
- [ ] In RBAC mode, the Cosmos DB provider uses the Azure Resource Manager SDK for database creation (data-plane RBAC cannot create databases).
- [ ] Management SDK config (`subscriptionId`, `resourceGroupName`) is optional; when absent, provisioning falls back to data-plane calls with a warning.

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

