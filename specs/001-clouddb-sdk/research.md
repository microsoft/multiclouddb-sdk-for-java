# Research: Hyperscale DB SDK

This document resolves key design choices for the Hyperscale DB SDK and records rationale + alternatives.

## Decision 1: Implementation language and packaging
- Decision: Implement the initial SDK in **Java** (targeting Java 17 LTS) and publish as Maven artifacts.
- Rationale: This repository is the Java SDK implementation; the three official provider SDKs are mature on Java, and a Java-first portable contract is the fastest path to a credible conformance suite.
- Alternatives considered:
  - Start in a different language first: would delay Java adoption and duplicate contract/testing work.
  - Multi-language from day one: too much surface area and build/release complexity for the MVP.

## Decision 2: Provider SDKs to wrap (Java)
- Decision:
  - Cosmos DB: `com.azure:azure-cosmos` (Cosmos DB Java SDK v4)
  - DynamoDB: `software.amazon.awssdk:dynamodb` (AWS SDK for Java v2)
  - Spanner: `com.google.cloud:google-cloud-spanner`
- Rationale: Officially supported, widely used SDKs with stable auth/transport and operational semantics. Adapters should delegate I/O + auth to these SDKs.
- Alternatives considered:
  - Non-official community clients: higher operational risk and weaker long-term support.

## Decision 3: API style (sync vs async)
- Decision: Provide a **sync-first** portable contract for the MVP, with an optional future async surface using `CompletableFuture`.
- Rationale: A sync contract minimizes the portable surface area and makes conformance testing simpler. Where a provider SDK is async/reactive internally, the adapter should encapsulate that complexity.
- Alternatives considered:
  - Async-first: forces a lowest-common-denominator async model and complicates cancellation semantics.
  - Dual sync+async from day one: doubles the public surface and test burden while the contract is still evolving.

## Decision 4: Portable data representation
- Decision: Portable document payload is JSON-like: objects, arrays, strings, numbers, booleans, null.
- Rationale: Maps well to Cosmos documents, DynamoDB items (with conversion), and Spanner rows (with mapping).
- Alternatives considered:
  - Strongly typed models: harder to keep language-neutral across future FFI.
  - Provider-native encodings only: harms portability.

## Decision 5: Key model
- Decision: Represent keys as an explicit structured object that can express:
  - `id` (logical identifier)
  - optional `partition` component(s)
  - optional `composite` components
- Rationale: Supports partitioned keys (Cosmos), partition+sort keys (Dynamo), and primary key columns (Spanner) without forcing provider conditionals in application code.
- Alternatives considered:
  - Single string key: insufficient for Dynamo/Spanner.
  - Separate APIs per provider: violates portability-first default.

## Decision 6: Query contract and paging
- Decision: Provide a portable `query()` with:
  - a query expression (portable form and/or provider-native string)
  - parameters
  - page size
  - continuation token
- Rationale: Users need cross-provider query + paging; continuation tokens are the only scalable portable paging mechanism.
- Alternatives considered:
  - Offset-based paging: non-portable and often inefficient.

## Decision 7: Capabilities + portability warnings
- Decision: Capabilities are first-class and discoverable; non-portable behaviors require explicit opt-in and MUST emit a portability warning signal.
- Rationale: Prevents “it worked on Cosmos” surprises and preserves trust.
- Alternatives considered:
  - Best-effort behavior with docs only: too easy to miss and too risky.

## Decision 8: Configuration-only portability
- Decision: Provider selection and environment differences are configuration-only for the portable contract. Provider-specific opt-ins SHOULD be config-driven when possible.
- Rationale: Enables "deploy anywhere" without code changes.
- Alternatives considered:
  - Provider conditionals in user code: defeats purpose.

## Decision 9: Testing approach
- Decision: Provide a shared contract test suite and run it against each adapter.
- Rationale: Portability is only credible if verified.
- Alternatives considered:
  - Only unit tests: insufficient to validate real provider behaviors.

## Decision 10: Portable query expression syntax
- Decision: Use a SQL-subset WHERE clause syntax with named `@paramName` parameters and five portable functions.
- Rationale: SQL WHERE syntax is familiar to most developers and is natively supported by Cosmos DB (SQL API) and Spanner (GoogleSQL). DynamoDB supports it via PartiQL. A common subset avoids inventing a new DSL while remaining translatable to all three providers. See `query-filter-research.md` for the full 956-line analysis.
- Alternatives considered:
  - Builder/fluent API only (no string syntax): less expressive for complex boolean logic, harder to compose dynamically, worse developer ergonomics for ad-hoc queries.
  - Provider-native passthrough only (status quo): defeats the "write once, run anywhere" goal for queries. Developers must learn three different expression languages.
  - Full SQL (SELECT, FROM, WHERE, ORDER BY, GROUP BY): too ambitious for MVP. ORDER BY and aggregations are not portable to DynamoDB. The WHERE-only subset covers the core filtering use case.
  - LINQ-style expression trees: Java lacks LINQ; would require heavy bytecode manipulation or annotation processing.

## Decision 11: DynamoDB query backend — PartiQL over native expressions
- Decision: Use DynamoDB PartiQL (`executeStatement`) as the backend for portable query expressions, replacing the current Scan + FilterExpression approach.
- Rationale: PartiQL provides SQL-like syntax that maps closely to the portable expression language, simplifying translation. Named `@param` parameters translate to positional `?` parameters. Field references need only table name quoting. Performance is equivalent per AWS documentation. The native Scan + FilterExpression approach requires a completely different parameter model (`:value` prefixed), expression attribute names (`#name` for reserved words), and a separate expression syntax.
- Alternatives considered:
  - Keep Scan + FilterExpression: requires a fundamentally different translator with separate attribute naming/value systems. More complex to maintain. Does not support future SQL-like features (projections, subqueries).
  - Use DynamoDB Query API: requires knowing the key schema and building KeyConditionExpression separately. Only works when filtering on the partition key + sort key. Not suitable as a general portable query backend.

## Decision 12: Portable function set
- Decision: Five portable functions available on all providers: `starts_with`, `contains`, `field_exists`, `string_length`, `collection_size`.
- Rationale: These five functions have direct equivalents in all three providers (see operator/function matrix in `query-filter-research.md`). Translation is straightforward:
  - `starts_with` → `STARTSWITH` (Cosmos), `begins_with` (DynamoDB PartiQL), `STARTS_WITH` (Spanner)
  - `contains` → `CONTAINS` (Cosmos), `contains` (DynamoDB PartiQL), `STRPOS(...) > 0` (Spanner)
  - `field_exists` → `IS_DEFINED` (Cosmos), `IS NOT MISSING` (DynamoDB PartiQL), `IS NOT NULL` (Spanner)
  - `string_length` → `LENGTH` (Cosmos), `char_length` (DynamoDB PartiQL), `CHAR_LENGTH` (Spanner)
  - `collection_size` → `ARRAY_LENGTH` (Cosmos), `size` (DynamoDB PartiQL), `ARRAY_LENGTH` (Spanner)
- Alternatives considered:
  - Larger function set including `ends_with`, `LOWER`, `UPPER`, regex: not supported on DynamoDB. Moved to capability-gated features.
  - Smaller function set (operators only, no functions): too restrictive for real filtering use cases.

## Decision 13: Expression representation — parsed AST vs string passthrough
- Decision: Parse portable expressions into a typed AST (sealed interface hierarchy), then translate the AST to each provider's native string.
- Rationale: An AST enables validation before execution (missing params, unsupported functions, malformed syntax), makes translation a clean tree-walk, and supports future features like expression optimization or static analysis. The parser is a simple hand-written recursive-descent parser (no external dependencies).
- Alternatives considered:
  - Regex-based string rewriting: fragile, cannot handle nested parentheses or operator precedence correctly, no validation.
  - Direct string passthrough with provider-specific formatting: would require each provider to re-parse the expression, duplicating effort.
  - ANTLR or other grammar generator: adds a build-time dependency and generated code. The grammar is simple enough (WHERE clause subset) that a hand-written parser is straightforward and more maintainable.

## Decision 14: Native expression mode
- Decision: `QueryRequest` gains a separate `nativeExpression` field (mutually exclusive with `expression`). When `nativeExpression` is set, the expression is passed through to the provider without translation.
- Rationale: Developers need an escape hatch for provider-specific query features (`LIKE` on Cosmos, regex on Spanner, DynamoDB-specific PartiQL). A separate field prevents accidental cross-provider execution and makes the non-portable intent explicit in code.
- Alternatives considered:
  - A flag on the existing `expression` field (e.g., `isNative=true`): less explicit, easy to forget, and mixes portable and native expressions in the same field.
  - A separate `nativeQuery()` method on `HyperscaleDbClient`: changes the client interface. Keeping it in `QueryRequest` preserves the existing client API shape.

## Decision 15: Capability-gated query features
- Decision: Features available on only some providers (`LIKE`, `ORDER BY`, `ends_with`, regex, `LOWER`/`UPPER`) are guarded by new `Capability` constants. The expression validator checks capabilities at translation time and fails fast with a typed error if the feature is unsupported.
- Rationale: Follows the constitution's capability-based API principle. Developers can check capabilities before building expressions, and get clear errors if they use unsupported features.
- Alternatives considered:
  - Silently ignoring unsupported features: violates the "no false promises" principle.
  - Throwing at execution time (after sending to provider): too late; the error should be caught before any I/O.

## Appendix: Java provider semantics (Cosmos v4, DynamoDB v2, Spanner) and what can be normalized

This appendix is **non-normative**. It records Java SDK behaviors that impact the portable contract, focusing on:
- pagination / continuation tokens
- retries / timeouts
- cancellation

### Portable contract guidance (what the SDK can safely promise)

- **Continuation tokens are opaque and provider-specific**
  - Treat `Query.continuation_token` and `QueryPage.continuation_token` as opaque bytes encoded to a string.
  - The token MUST only be used with the *same provider*, *same resource*, and *logically same query* (expression + parameters + index/ordering semantics).
  - Tokens may be large and may contain provider state; do not parse them and do not log them by default.

- **`page_size` is a preference, not a guarantee**
  - Treat `Query.page_size` as “preferred maximum items per page”. Providers may return fewer (or sometimes more) items.
  - Callers must rely on `continuation_token` (when supported) to resume rather than assuming fixed-size pages.

- **Retries must be idempotency-aware and avoid “double retry”**
  - Prefer configuring provider SDK retry knobs rather than wrapping everything in an additional retry loop.
  - If the portable SDK adds retries, it MUST only do so for operations the adapter can prove are idempotent under the request options.

- **Timeout and cancellation are best-effort**
  - A portable “deadline” should be treated as best-effort cancellation: it may stop the *caller’s wait* before the provider stops work.
  - Streaming operations can surface errors during iteration; cancellation can return partial results.

### Pagination / continuation tokens

#### Azure Cosmos DB (Java SDK v4: `com.azure:azure-cosmos`)

- Queries return paged results via `CosmosPagedFlux<T>` / `CosmosPagedIterable<T>`.
- The SDK supports resuming by token using `byPage(continuationToken)` and `byPage(continuationToken, preferredPageSize)`.
- The page size parameter is explicitly “preferred”; the service may or may not honor it.
- Cosmos continuation tokens are opaque strings. They can be bounded in size via query request options (continuation token limit), but the contract must still treat them as non-portable.

**Normalization implication**: Cosmos can implement `Query.continuation_token` natively.

#### AWS DynamoDB (AWS SDK for Java v2: `software.amazon.awssdk:dynamodb`)

- Pagination is key-based:
  - responses include a `LastEvaluatedKey` (structured map of key attribute values)
  - callers provide `ExclusiveStartKey` to continue
- There is no single “token string” in the SDK; portability requires serializing the structured key to an opaque string.
- Scan/query pagination is not a stable snapshot in the face of concurrent writes; results may be inconsistent across pages.

**Normalization implication**: Dynamo can implement `Query.continuation_token`, but only as an adapter-defined serialization of `LastEvaluatedKey`.

#### Google Cloud Spanner (Java client: `com.google.cloud:google-cloud-spanner`)

- SQL query results are streamed (`ResultSet`); there is no user-facing “page token” for row continuation.
- The Spanner client does use internal resumability mechanisms for streaming reads/queries on retry, but those are not exposed as a resume token for application-level pagination.

**Normalization implication**: Spanner should advertise a capability like “native continuation token paging: unsupported”.

### Timeouts

#### DynamoDB (AWS SDK v2)

- The AWS SDK distinguishes:
  - **total API call timeout** (covers all retries), and
  - **per-attempt timeout** (bounds each individual HTTP attempt)
- This model is important for portability: “timeout” can mean either “deadline for the whole operation” or “cap each attempt so retries remain possible”.

**Normalization implication**: a single portable timeout should map to a total-call deadline where possible, and adapters may optionally support a separate per-attempt timeout.

#### Spanner

- Spanner supports configuring deadlines per RPC (e.g., execute query, read, commit, rollback) via a call-context timeout configurator.
- This is not a single global knob; it is method/RPC-scoped.

**Normalization implication**: a portable timeout can be mapped per operation type (query vs commit), but the adapter may need to translate a single “timeout” into the relevant RPC deadline(s).

#### Cosmos

- Cosmos has explicit retry bounding for throttling via `ThrottlingRetryOptions` (see below), which effectively caps how long the client will wait and retry after 429s.
- Per-request deadlines don’t map 1:1 with Spanner RPC deadlines or AWS attempt/total timeouts; treat portable timeouts as best-effort.

### Retries

#### Cosmos: throttling and non-idempotent write retries

- Throttling (HTTP 429) retries are first-class and configurable via `ThrottlingRetryOptions`.
  - The SDK waits according to server `Retry-After`.
  - Defaults bound retries by max attempts and a max cumulative retry wait.
- Retries for non-idempotent writes are **explicitly opt-in** via `NonIdempotentWriteRetryOptions`.
  - Default is disabled.
  - Enabling can change conflict/condition-failure behavior (e.g., by using an internal tracking id); it can also increase RU/payload overhead.

**Normalization implication**: the portable contract should:
- expose throttling retry controls as a portable knob (max retry wait/attempts), and
- require explicit opt-in for “retry non-idempotent writes” (and emit portability warnings when enabled).

#### DynamoDB (AWS SDK v2)

- Retries are handled by the SDK retry strategy and interact with total/per-attempt timeouts.
- Some errors (throttling/provisioned throughput) are retryable; others (validation/conditional check failures) are not.

**Normalization implication**: adapters should use the provider’s retry strategy configuration and surface retryability through `HyperscaleDbError.retryable`.

#### Spanner

- Retry settings are configured per RPC/method. Idempotent methods typically have retries configured by default; non-idempotent methods typically do not.
- Transaction contention often surfaces as `ABORTED` and is commonly retryable at the transaction level.

**Normalization implication**: the portable retry model must represent “retryable transient failures” distinctly from “conflict/condition failures”, and Spanner adapters must treat ABORTED as retryable where safe.

### Cancellation

#### Spanner cancellation gotchas

- Asynchronous read/query cancellation is best-effort and does not guarantee immediate termination; the read may still complete, fail for another reason, or return incomplete results.
- Cancelling a commit operation aborts the entire transaction.

**Normalization implication**: cancellation should be treated as a best-effort signal, and the portable SDK must document that partial results are possible for streaming reads/queries.

#### Cosmos and DynamoDB cancellation gotchas

- Both providers can surface errors during iteration over paged results (reactive paging in Cosmos; paginators/iterables in AWS). Cancellation/timeout can occur mid-stream.

**Normalization implication**: portable `query()` must be allowed to fail during page retrieval/iteration, not just at “query start”.
