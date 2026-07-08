# Implementation Plan: Multicloud DB SDK — Portable Query Expression Language, Resource Provisioning & Partition-Key-Scoped Queries

**Branch**: `001-clouddb-sdk` | **Date**: 2026-01-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-clouddb-sdk/spec.md` (FR-022 through FR-038, SC-006 through SC-012)

## Summary

Add a portable query expression language to the Multicloud DB SDK. Developers write WHERE-clause filters using a SQL-subset syntax with named `@param` parameters and five portable functions (`starts_with`, `contains`, `field_exists`, `string_length`, `collection_size`). Each provider adapter translates portable expressions into the provider's native query format:

- **Cosmos DB** → Cosmos SQL (`SELECT * FROM c WHERE c.<field> ...`)
- **DynamoDB** → PartiQL (`SELECT * FROM "<table>" WHERE <field> ...`)
- **Spanner** → GoogleSQL (`SELECT * FROM <table> WHERE <field> ...`)

A native expression mode allows provider-specific syntax to bypass the translator. Expression validation catches unsupported functions, missing parameters, and malformed syntax before execution.

Additionally, the SDK provides portable resource provisioning via `ensureDatabase(String)`, `ensureContainer(ResourceAddress)`, and `provisionSchema(Map<String, List<String>>)`. The first two methods create individual database and collection resources. `provisionSchema` is a higher-level bulk API that creates all databases in parallel, waits for completion, then creates all containers in parallel using a bounded thread pool. Applications can provision their entire schema with a single call:

- **Cosmos DB** → `createDatabaseIfNotExists` + `createContainerIfNotExists` (partition key: `/partitionKey`); in RBAC mode, uses Azure Resource Manager SDK for database creation
- **DynamoDB** → No-op for database (no explicit concept); `CreateTable` with hash key (`partitionKey`) + sort key (`sortKey`), PAY_PER_REQUEST billing
- **Spanner** → No-op for database; DDL `CREATE TABLE` with `partitionKey STRING(MAX)`, `sortKey STRING(MAX)`, `data STRING(MAX)`, primary key `(partitionKey, sortKey)`

The Cosmos DB provider also supports `DefaultAzureCredential` as a fallback when no account key is provided, enabling Managed Identity, Azure CLI, and environment variable authentication. When using RBAC credentials, the provider uses `azure-resourcemanager-cosmos` (Azure Resource Manager SDK) for database creation because Cosmos data-plane RBAC does not support control-plane operations.

Provisioning methods are idempotent and handle concurrent creation race conditions gracefully.

Finally, the SDK supports **partition-key-scoped queries** via an optional `partitionKey` field on `QueryRequest`. When set, each provider uses its native efficient mechanism to scope the query to items sharing that partition key value:

- **Cosmos DB** → `CosmosQueryRequestOptions.setPartitionKey(new PartitionKey(value))` for single-partition queries
- **DynamoDB** → WHERE condition on `partitionKey` column in PartiQL to filter to matching partition
- **Spanner** → WHERE condition on `partitionKey` column in GoogleSQL

This eliminates cross-partition scans when the application data model co-locates related documents under a shared partition key (e.g., positions partitioned by portfolioId).

### What Already Exists

The SDK is fully implemented with CRUD + query + paging + conformance tests (47 passing). However, `QueryRequest.expression()` is currently an opaque string that each provider interprets in its own native syntax. **No portable expression code exists**: no AST, no parser, no translator. DynamoDB currently uses Scan + FilterExpression (not PartiQL).

### What Must Change

1. New expression parser and AST types in `multiclouddb-api`
2. `ExpressionTranslator` SPI interface and per-provider implementations
3. `QueryRequest` gains `nativeExpression` field; `expression` becomes the portable input
4. DynamoDB backend switches from Scan + FilterExpression to PartiQL `executeStatement`
5. New `Capability` constants for query DSL features
6. Conformance tests for expression translation across all providers
7. `ensureDatabase` and `ensureContainer` methods on `MulticloudDbClient` (public API) and `MulticloudDbProviderClient` (SPI with default no-ops)
8. Per-provider provisioning implementations (Cosmos, DynamoDB, Spanner)
9. Provisioning delegation with diagnostics/timing in `DefaultMulticloudDbClient`
10. `QueryRequest` gains optional `partitionKey` field for partition-scoped queries
11. Provider query methods use native partition-scoping when `partitionKey` is set
12. Sample app data model updated to use `Key.of(partitionKey, sortKey)` for co-location

## Technical Context

**Language/Version**: Java 17 LTS (Eclipse Adoptium Temurin-17.0.10.7-hotspot)
**Primary Dependencies**: Jackson 2.17.0, SLF4J 2.0.12, Azure Cosmos SDK 4.60.0, Azure Identity 1.12.0, Azure Resource Manager Cosmos 2.51.0, Azure Core Management 1.17.0, AWS SDK v2 2.25.16 (DynamoDB + DynamoDB Enhanced), Google Cloud Spanner 6.62.0
**Storage**: Cosmos DB (NoSQL), DynamoDB, Spanner (via provider SDKs)
**Testing**: JUnit 5.10.2, Mockito 5.11.0, Maven Surefire/Failsafe
**Target Platform**: JVM 17+ (server-side)
**Project Type**: Multi-module Maven library (7 modules)
**Performance Goals**: Expression translation overhead < 1ms per query (thin wrapper principle)
**Constraints**: No runtime dependencies beyond Jackson for AST serialization; parser must be hand-written (no ANTLR/grammar-generator dependency)
**Scale/Scope**: ~25 new/modified Java source files, ~30 new test cases for expression translation, provisioning API across 7 files, cloud authentication (DefaultAzureCredential + ARM management SDK) in Cosmos provider

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| **0 — Portability-First** | ✅ PASS | Portable expression syntax is the default. Native mode requires explicit opt-in via `nativeExpression`. Same expression → equivalent results on all providers. |
| **1 — Thin Wrapper** | ✅ PASS | Expression translator is a lightweight string-to-string transform (< 1ms). All I/O and auth remain delegated to provider SDKs. Native client escape hatch preserved. |
| **2 — Capability-Based** | ✅ PASS | Capability-gated features (`LIKE`, `ORDER BY`, `ends_with`, regex, `LOWER`/`UPPER`) guarded by capability checks. Unsupported features fail fast at translation time with typed error before query execution. |
| **3 — Consistent Surface** | ✅ PASS | Same expression syntax, same parameter model, same function names across all providers. No provider-specific branches in application code for portable queries. |
| **3.1 — Config-Only Portability** | ✅ PASS | Provider switch requires only configuration changes. Portable expressions need zero code changes. |
| **4 — Explicit Reliability** | ✅ PASS | Per-request timeout/cancellation unchanged. Expression validation fails fast before any I/O. |
| **5 — Diagnostics** | ✅ PASS | Translation diagnostics (original expression, translated expression, provider) available via existing diagnostic hooks. No secrets exposed. |
| **5.1 — Layered Diagnostics** | ✅ PASS | Portable: expression validation errors include field/function/operator context. Provider-specific: native translated expression available for debugging. |

**Gate result**: ALL PASS — no violations.

## Project Structure

### Documentation (this feature)

```text
specs/001-clouddb-sdk/
├── plan.md                              # This file
├── research.md                          # Phase 0: General SDK + query DSL research
├── query-filter-research.md             # Phase 0: Deep query research (956 lines)
├── provider-sdk-surface-comparison.md   # Provider SDK surface analysis
├── data-model.md                        # Phase 1: Entity model (updated with query types)
├── quickstart.md                        # Phase 1: Usage examples (updated with portable queries)
├── contracts/
│   └── openapi.yaml                     # Phase 1: Contract (updated with query expression types)
├── checklists/                          # Quality checklists
└── tasks.md                             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
multiclouddb-api/src/main/java/com/multiclouddb/api/
├── MulticloudDbClient.java              # MODIFIED: add ensureDatabase + ensureContainer + provisionSchema
├── QueryRequest.java               # MODIFIED: add nativeExpression field + partitionKey field
├── QueryPage.java                  # Unchanged (already supports warnings)
├── Capability.java                 # MODIFIED: add query DSL capabilities
├── query/                          # NEW: portable expression types
│   ├── Expression.java             #   Sealed interface — AST root
│   ├── ComparisonExpression.java   #   field op value/param
│   ├── LogicalExpression.java      #   AND/OR of sub-expressions
│   ├── NotExpression.java          #   NOT wrapper
│   ├── FunctionCallExpression.java #   starts_with, contains, etc.
│   ├── InExpression.java           #   field IN (values)
│   ├── BetweenExpression.java      #   field BETWEEN a AND b
│   ├── FieldRef.java               #   field reference (supports dot notation)
│   ├── Literal.java                #   string/number/boolean/null literal
│   ├── Parameter.java              #   @paramName reference
│   ├── ComparisonOp.java           #   enum: EQ, NE, LT, GT, LE, GE
│   ├── LogicalOp.java              #   enum: AND, OR
│   ├── PortableFunction.java       #   enum: STARTS_WITH, CONTAINS, FIELD_EXISTS, STRING_LENGTH, COLLECTION_SIZE
│   ├── ExpressionParser.java       #   Hand-written recursive-descent parser
│   └── ExpressionValidator.java    #   Validates against provider capabilities
├── spi/
│   └── ExpressionTranslator.java   # NEW: SPI — translate Expression AST → native string

multiclouddb-api/src/main/java/com/multiclouddb/api/internal/
└── DefaultMulticloudDbClient.java       # MODIFIED: provisioning delegation (ensureDatabase/ensureContainer/provisionSchema) with diagnostics/timing

multiclouddb-api/src/main/java/com/multiclouddb/api/spi/  (NOTE: SPI interface lives in multiclouddb-api)
└── MulticloudDbProviderClient.java      # MODIFIED: add default no-op ensureDatabase + ensureContainer; add default parallel provisionSchema

multiclouddb-provider-cosmos/src/main/java/com/multiclouddb/provider/cosmos/
├── CosmosProviderClient.java       # MODIFIED: use translator for portable expressions + provisioning + DefaultAzureCredential + ARM management SDK
├── CosmosExpressionTranslator.java # NEW: AST → Cosmos SQL
└── CosmosCapabilities.java         # MODIFIED: add query DSL capabilities

multiclouddb-provider-dynamo/src/main/java/com/multiclouddb/provider/dynamo/
├── DynamoProviderClient.java       # MODIFIED: switch to PartiQL + provisioning (CreateTable)
├── DynamoExpressionTranslator.java # NEW: AST → DynamoDB PartiQL
└── DynamoCapabilities.java         # MODIFIED: add query DSL capabilities

multiclouddb-provider-spanner/src/main/java/com/multiclouddb/provider/spanner/
├── SpannerProviderClient.java      # MODIFIED: implement query with translator + provisioning (DDL)
├── SpannerExpressionTranslator.java# NEW: AST → Spanner GoogleSQL
└── SpannerCapabilities.java        # MODIFIED: add query DSL capabilities

multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/
├── ExpressionParserTest.java       # NEW: parser unit tests
├── ExpressionTranslationTest.java  # NEW: per-provider translation tests
└── PortableQueryConformanceTest.java # NEW: cross-provider query equivalence tests
```

**Structure Decision**: Expression types and parser live in `multiclouddb-api` (no new module needed — they are part of the public API surface). `ExpressionTranslator` SPI interface lives in `multiclouddb-api/spi`. Each provider module adds its own translator implementation. This avoids adding a new module and keeps the dependency graph unchanged.

## Complexity Tracking

No constitution violations to justify. The design adds ~15 new types to `multiclouddb-api`, which is consistent with the thin wrapper principle (lightweight data types and a single parser, not a re-implementation of database query processing). The provisioning API (`ensureDatabase`/`ensureContainer`/`provisionSchema`) adds 3 methods to the public client and SPI with default implementations (no-op for ensureDatabase/ensureContainer, parallel executor for provisionSchema), maintaining the thin wrapper principle by delegating directly to each provider's native idempotent creation calls. The Cosmos provider has additional complexity for `DefaultAzureCredential` support and ARM management SDK integration for RBAC-mode provisioning, but this is provider-internal and does not affect the portable surface.



---


## Change Feed (US14) — Planning Addendum (2026-06)

**Scope**: deliver a portable pull-mode change feed across Cosmos / Dynamo / Spanner as a thin wrapper over each provider's native incremental-read API. FR-065, FR-067, FR-068 (basic capability), FR-070 are shipped in full. FR-066 partials and FR-068 sub-capabilities are deferred — see spec.md *Implementation status* notes.

### Surface

Three primitives on `MulticloudDbClient`:

1. `List<ChangeFeedCursor> listCursors(ResourceAddress addr)` — returns one cursor per provider-internal partition, anchored at the live tip.
2. `ChangeFeedPage readChanges(ResourceAddress addr, ChangeFeedCursor cursor)` — drains one bounded page (events + a refreshed cursor + `hasMore` + `isTerminal`).
3. `readChanges(ResourceAddress addr, ChangeFeedCursor cursor, OperationOptions opts)` — accepts `OperationOptions` for forward-compatibility; **v1 providers do not honour any option field**.

Plus two cursor factories: `ChangeFeedCursor.now()` (live-tip sentinel) and `ChangeFeedCursor.fromToken(String)` (resume from a previously persisted token). Tokens are opaque Base64URL JSON carrying provider id, resource binding, anchor, partition list, and an `issuedAt` epoch-ms with a 24h portable age cap (CursorTokenCodec.MAX_TOKEN_AGE_MILLIS).

### Per-provider mapping

| Provider | Native API | Notes |
|---|---|---|
| Cosmos | `CosmosContainer.queryChangeFeed(CosmosChangeFeedRequestOptions, JsonNode.class)` + `getFeedRanges()` | Always reads in All-Versions-and-Deletes (AVAD) mode and unwraps the AVAD envelope (`{current, previous, metadata}`) so `ChangeEvent.type()` and `ChangeEvent.data()` match the Dynamo / Spanner contract. The Cosmos container the caller targets must be provisioned with an AVAD change-feed policy (`ChangeFeedPolicy.createAllVersionsAndDeletesPolicy`); a non-AVAD container surfaces a Cosmos 400 BadRequest through the SDK's normalised error envelope on the first read. 410 GONE → `CursorExpiredException(PROVIDER_TRIMMED)`. Under the opt-in path (`ChangeFeedConfig.extendedRetention`), `ensureContainer(...)` drives provisioning end-to-end — creating the container with the AVAD `ChangeFeedPolicy` carrying the requested retention — and the Continuous-Backup-required failure is re-mapped into `UNSUPPORTED_CAPABILITY(continuous_backup_required)`. |
| Dynamo | `DynamoDbStreams.getRecords(GetRecordsRequest)` with `ShardIteratorType=AT_SEQUENCE_NUMBER`/`LATEST` | Requires the table to have a stream enabled (`StreamSpecification` with `NEW_AND_OLD_IMAGES`). `TrimmedDataAccessException` → `CursorExpiredException(PROVIDER_TRIMMED)`. |
| Spanner | `READ_<stream>(start_timestamp, end_timestamp, partition_token, heartbeat_milliseconds)` TVF in single-use read-only TX | Each `data_change_record.mod` → one `ChangeEvent`. `child_partitions_record` rows rotate the partition set in place (no cursor re-bootstrap required). `OUT_OF_RANGE` → `CursorExpiredException(PROVIDER_TRIMMED)`. Requires `CREATE CHANGE STREAM <name> FOR <collection> OPTIONS (value_capture_type='NEW_ROW')` provisioned out-of-band by default; under the opt-in path (`ChangeFeedConfig.extendedRetention`), `ensureContainer(...)` drives the SDK to emit the DDL itself, including the requested `OPTIONS (retention_period = '…')` clause, and the stream name honours the `changeStream.<collection>` connection-key override so the producer and reader resolve the same stream. |

### Conformance coverage

`multiclouddb-conformance/.../us14/ChangeFeedConformanceTest.java` (and provider-specific `Cosmos|Dynamo|SpannerChangeFeedConformanceTest`) cover FR-cf-001 … FR-cf-014:

- live-tip semantics (`now()` ignores prior events)
- ordering within a partition
- DELETE event surface (Cosmos AVAD + Dynamo + Spanner; Cosmos LatestVersion is *waived* — see FR-068 deferral)
- continuation resume across re-bootstrap (`toToken()` → persist → `fromToken()`)
- expired-token diagnostics (`CursorExpiredException` with `reason=PROVIDER_TRIMMED` from a 24h-old token, plus the codec-side `TOKEN_AGED_OUT` aged-token path)
- per-partition cursor mint (`listCursors` returns ≥1 cursor)
- error normalization (capability missing, provider trimmed, unsupported resource)

### Deferred work tracked here

- **FR-066(a) / FR-066(b)**: `FROM_BEGINNING` and `AT_TIMESTAMP` cursor anchors. Cursor codec is already forward-compatible (the anchor field is a string-encoded enum); add two enum constants + provider-side mapping + capability flag.
- **FR-068 sub-capabilities**: `CHANGE_FEED_FROM_BEGINNING`, `CHANGE_FEED_FROM_TIMESTAMP`, `CHANGE_FEED_NEW_IMAGE`. Adding these requires declaring per-provider support in all three `*Capabilities` classes plus matching conformance assertions. Delete-detection is universally supported in v1 (Cosmos AVAD is mandatory, Dynamo and Spanner emit deletes natively), so no separate `CHANGE_FEED_DELETE_DETECTION` flag is planned.
- **FR-069 logical partition scope**: add `listCursors(addr, partitionKeyValue)`; back it with `CosmosChangeFeedRequestOptions.createForProcessingFromContinuation(...)` constrained to the matching feed range on Cosmos, server-side filter on Dynamo, and `WHERE partition_key_token = ?` on Spanner. Track as `Capability.CHANGE_FEED_LOGICAL_PARTITION_SCOPE`.
- **Multi-thread change-feed e2e demonstrator** (tracked as T173): a worker-pool fixture in `multiclouddb-e2e/` that fan-outs `listCursors(addr)` to N workers and drains them in parallel against the live emulator, asserting (a) no duplicate `eventID` across workers and (b) `nextCursor`s remain individually resumable. The conformance suite covers single-cursor semantics; this fixture covers the recommended deployment pattern documented in `docs/guide.md`.
- **OperationOptions.timeout() enforcement on the change-feed path** (tracked as T174): v1 emits a one-shot `WARN` when a caller supplies `options.timeout()` because no built-in provider honours it (Cosmos / Dynamo / Spanner each have their own page-fetch budgets). A future release should bound the wall-clock of `readChanges` per-call so callers can compose timeouts uniformly with CRUD ops.


### Change Feed (US14) — Planning Addendum (2026-11): extended retention opt-in

**Scope delta vs the 2026-06 addendum**: the extended-retention capability
shipped with PR #84 reverses the earlier "SDK does not provision change
substrates" policy along a narrow, opt-in path:

- New API surface: `Capability.EXTENDED_CHANGE_FEED_HISTORY` (declared
  `_CAP` on Cosmos and Spanner, `_UNSUPPORTED` on Dynamo) and the
  `ChangeFeedConfig` value class with builder validation enforcing
  `extendedRetention > 24h`.
- Build-time gate: `MulticloudDbClientFactory.create(...)` rejects
  `(opt-in) + (provider missing capability)` with
  `UNSUPPORTED_CAPABILITY(extended_retention_unavailable)` before any
  change-feed-substrate I/O. The provider client paid for by
  `adapter.createClient(...)` is `close()`-d on gate-throw to avoid
  leaking control-plane channels. A defence-in-depth mirror gate fires in
  the Dynamo SPI constructor so direct-instantiation paths cannot bypass
  the factory.
- Cosmos: `CosmosProviderClient.ensureContainer(...)` provisions the
  container with `ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(d)`;
  Continuous-Backup-required errors are re-mapped to
  `UNSUPPORTED_CAPABILITY(continuous_backup_required)` — but only when
  the caller opted in, preserving the bit-for-bit v1 default-path
  behaviour.
- Spanner: `SpannerProviderClient.ensureContainer(...)` emits
  `CREATE CHANGE STREAM <name> FOR <collection> OPTIONS (...,
  retention_period = '…')` using a `Duration → 'Nd' / 'Nh' / 'Nm' / 'Ns'`
  formatter that rounds sub-second residue *up* so 24h+1ms never
  silently collapses to the 24h baseline.

**Deferred work tracked here (new)**:

- **Behavioural conformance for extended retention**: the
  `CosmosChangeFeedExtendedRetentionConformanceTest /
  SpannerChangeFeedExtendedRetentionConformanceTest` shape is deferred
  because (a) the Cosmos emulator caps AVAD retention at 10 minutes and
  rejects the [1d, 30d] domain the spec requires, and (b) the Spanner
  emulator silently ignores `OPTIONS (retention_period = …)` on the
  `CREATE CHANGE STREAM` DDL. The build-time gate is covered by an
  abstract API-module test (`MulticloudDbClientFactoryExtendedRetentionGateTest`)
  that exercises the routing via a fake SPI adapter, and the per-provider
  capability declaration is covered by the bumped `CapabilitiesConformanceTest`
  + per-provider `isSupported(EXTENDED_CHANGE_FEED_HISTORY)` assertions in
  `Cosmos/Dynamo/SpannerCapabilitiesTest`. The end-to-end "set 7d,
  observe events older than 24h are still readable" assertion is owed to a
  live-cloud nightly fixture, not the emulator suite.
### Spanner upsert flip — rationale

Earlier `Unreleased` builds executed `upsert()` as `Mutation.newReplaceBuilder(...)` to match Cosmos / Dynamo full-document-replace semantics on read. With the change-feed reader in place, that flip became a portability bug: Spanner change streams under `value_capture_type='NEW_ROW'` surfaced an `INSERT` record for every `upsert()` call, regardless of whether the row pre-existed — Cosmos AVAD and Dynamo `NEW_AND_OLD_IMAGES` correctly distinguish CREATE from UPDATE on the same upsert call. The v1 fix flips the mutation back to `Mutation.newInsertOrUpdateBuilder(...)` and keeps the existing `FIELD_DATA` field-set tracking so the read-path full-document-replace contract is preserved without using a destructive replace. The Spanner change-feed reader applies the same `FIELD_DATA` filter to its payload so `ChangeEvent.data()` does not leak stale columns from earlier writes. See `docs/changelog.md` Spanner `[Unreleased]` → *Fixed* for the user-facing note.
