# Implementation Plan: Hyperscale DB SDK — Portable Query Expression Language, Resource Provisioning & Partition-Key-Scoped Queries

**Branch**: `001-clouddb-sdk` | **Date**: 2026-01-24 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-clouddb-sdk/spec.md` (FR-022 through FR-038, SC-006 through SC-012)

## Summary

Add a portable query expression language to the Hyperscale DB SDK. Developers write WHERE-clause filters using a SQL-subset syntax with named `@param` parameters and five portable functions (`starts_with`, `contains`, `field_exists`, `string_length`, `collection_size`). Each provider adapter translates portable expressions into the provider's native query format:

- **Cosmos DB** → Cosmos SQL (`SELECT * FROM c WHERE c.<field> ...`)
- **DynamoDB** → PartiQL (`SELECT * FROM "<table>" WHERE <field> ...`)
- **Spanner** → GoogleSQL (`SELECT * FROM <table> WHERE <field> ...`)

A native expression mode allows provider-specific syntax to bypass the translator. Expression validation catches unsupported functions, missing parameters, and malformed syntax before execution.

Additionally, the SDK provides portable resource provisioning via `ensureDatabase(String)` and `ensureContainer(ResourceAddress)`. These methods allow applications to create database and collection resources without provider-specific code:

- **Cosmos DB** → `createDatabaseIfNotExists` + `createContainerIfNotExists` (partition key: `/partitionKey`)
- **DynamoDB** → No-op for database (no explicit concept); `CreateTable` with hash key (`partitionKey`) + sort key (`sortKey`), PAY_PER_REQUEST billing
- **Spanner** → No-op for database; DDL `CREATE TABLE` with `partitionKey STRING(MAX)`, `sortKey STRING(MAX)`, `data STRING(MAX)`, primary key `(partitionKey, sortKey)`

Provisioning methods are idempotent and handle concurrent creation race conditions gracefully.

Finally, the SDK supports **partition-key-scoped queries** via an optional `partitionKey` field on `QueryRequest`. When set, each provider uses its native efficient mechanism to scope the query to items sharing that partition key value:

- **Cosmos DB** → `CosmosQueryRequestOptions.setPartitionKey(new PartitionKey(value))` for single-partition queries
- **DynamoDB** → WHERE condition on `partitionKey` column in PartiQL to filter to matching partition
- **Spanner** → WHERE condition on `partitionKey` column in GoogleSQL

This eliminates cross-partition scans when the application data model co-locates related documents under a shared partition key (e.g., positions partitioned by portfolioId).

### What Already Exists

The SDK is fully implemented with CRUD + query + paging + conformance tests (47 passing). However, `QueryRequest.expression()` is currently an opaque string that each provider interprets in its own native syntax. **No portable expression code exists**: no AST, no parser, no translator. DynamoDB currently uses Scan + FilterExpression (not PartiQL).

### What Must Change

1. New expression parser and AST types in `hyperscaledb-api`
2. `ExpressionTranslator` SPI interface and per-provider implementations
3. `QueryRequest` gains `nativeExpression` field; `expression` becomes the portable input
4. DynamoDB backend switches from Scan + FilterExpression to PartiQL `executeStatement`
5. New `Capability` constants for query DSL features
6. Conformance tests for expression translation across all providers
7. `ensureDatabase` and `ensureContainer` methods on `HyperscaleDbClient` (public API) and `HyperscaleDbProviderClient` (SPI with default no-ops)
8. Per-provider provisioning implementations (Cosmos, DynamoDB, Spanner)
9. Provisioning delegation with diagnostics/timing in `DefaultHyperscaleDbClient`
10. `QueryRequest` gains optional `partitionKey` field for partition-scoped queries
11. Provider query methods use native partition-scoping when `partitionKey` is set
12. Sample app data model updated to use `Key.of(partitionKey, sortKey)` for co-location

## Technical Context

**Language/Version**: Java 17 LTS (Eclipse Adoptium Temurin-17.0.10.7-hotspot)
**Primary Dependencies**: Jackson 2.17.0, SLF4J 2.0.12, Azure Cosmos SDK 4.60.0, AWS SDK v2 2.25.16 (DynamoDB + DynamoDB Enhanced), Google Cloud Spanner 6.62.0
**Storage**: Cosmos DB (NoSQL), DynamoDB, Spanner (via provider SDKs)
**Testing**: JUnit 5.10.2, Mockito 5.11.0, Maven Surefire/Failsafe
**Target Platform**: JVM 17+ (server-side)
**Project Type**: Multi-module Maven library (7 modules)
**Performance Goals**: Expression translation overhead < 1ms per query (thin wrapper principle)
**Constraints**: No runtime dependencies beyond Jackson for AST serialization; parser must be hand-written (no ANTLR/grammar-generator dependency)
**Scale/Scope**: ~25 new/modified Java source files, ~30 new test cases for expression translation, provisioning API across 7 files

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
hyperscaledb-api/src/main/java/com/hyperscaledb/api/
├── HyperscaleDbClient.java              # MODIFIED: add ensureDatabase + ensureContainer
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

hyperscaledb-api/src/main/java/com/hyperscaledb/api/internal/
└── DefaultHyperscaleDbClient.java       # MODIFIED: provisioning delegation with diagnostics/timing

hyperscaledb-api/src/main/java/com/hyperscaledb/api/spi/  (NOTE: SPI interface lives in hyperscaledb-api)
└── HyperscaleDbProviderClient.java      # MODIFIED: add default no-op ensureDatabase + ensureContainer

hyperscaledb-provider-cosmos/src/main/java/com/hyperscaledb/provider/cosmos/
├── CosmosProviderClient.java       # MODIFIED: use translator for portable expressions + provisioning
├── CosmosExpressionTranslator.java # NEW: AST → Cosmos SQL
└── CosmosCapabilities.java         # MODIFIED: add query DSL capabilities

hyperscaledb-provider-dynamo/src/main/java/com/hyperscaledb/provider/dynamo/
├── DynamoProviderClient.java       # MODIFIED: switch to PartiQL + provisioning (CreateTable)
├── DynamoExpressionTranslator.java # NEW: AST → DynamoDB PartiQL
└── DynamoCapabilities.java         # MODIFIED: add query DSL capabilities

hyperscaledb-provider-spanner/src/main/java/com/hyperscaledb/provider/spanner/
├── SpannerProviderClient.java      # MODIFIED: implement query with translator + provisioning (DDL)
├── SpannerExpressionTranslator.java# NEW: AST → Spanner GoogleSQL
└── SpannerCapabilities.java        # MODIFIED: add query DSL capabilities

hyperscaledb-conformance/src/test/java/com/hyperscaledb/conformance/
├── ExpressionParserTest.java       # NEW: parser unit tests
├── ExpressionTranslationTest.java  # NEW: per-provider translation tests
└── PortableQueryConformanceTest.java # NEW: cross-provider query equivalence tests
```

**Structure Decision**: Expression types and parser live in `hyperscaledb-api` (no new module needed — they are part of the public API surface). `ExpressionTranslator` SPI interface lives in `hyperscaledb-api/spi`. Each provider module adds its own translator implementation. This avoids adding a new module and keeps the dependency graph unchanged.

## Complexity Tracking

No constitution violations to justify. The design adds ~15 new types to `hyperscaledb-api`, which is consistent with the thin wrapper principle (lightweight data types and a single parser, not a re-implementation of database query processing). The provisioning API (`ensureDatabase`/`ensureContainer`) adds 2 methods to the public client and SPI with default no-op implementations, maintaining the thin wrapper principle by delegating directly to each provider's native idempotent creation calls.
