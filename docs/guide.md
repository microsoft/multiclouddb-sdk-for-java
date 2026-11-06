# Multicloud DB SDK - Developer Guide

Comprehensive reference for the Multicloud DB SDK's key management, CRUD operations,
query DSL, and provider-specific storage behavior. For a quick-start tutorial
and architecture overview, see the [Getting Started](getting-started.md) guide. For the
portable API surface and error mapping reference, see
[Compatibility](compatibility.md).

The Map boundary accepts null, boolean, string, supported finite numbers, Lists
and string-keyed Maps. Binary/embedded objects are not supported inputs.
Applications may explicitly map bytes to ordinary text; the SDK does not infer
or decode Base64. Do not mutate input containers during a synchronous write;
the SDK neither mutates nor retains them after return. See the
[provider mapping table](#provider-mapping-and-serialization)
for the existing per-provider schema and numeric limitations.

---

## Table of Contents

- [Key & Partition Key](#key--partition-key)
  - [The Key Model](#the-key-model)
  - [When to Use Each Key Form](#when-to-use-each-key-form)
  - [Provider Storage Mapping](#provider-storage-mapping)
    - [Azure Cosmos DB](#azure-cosmos-db)
    - [Amazon DynamoDB](#amazon-dynamodb)
    - [Google Cloud Spanner](#google-cloud-spanner)
  - [Partition Key Strategy Guide](#partition-key-strategy-guide)
  - [Why MulticloudDbKey Is an Explicit Parameter](#why-multiclouddbkey-is-an-explicit-parameter)
- [Resource Addressing](#resource-addressing)
  - [Provider Physical Mapping](#provider-physical-mapping)
  - [Database-per-Tenant Pattern](#database-per-tenant-pattern)
  - [Provisioning Resources with provisionSchema()](#provisioning-resources-with-provisionschema)
- [CRUD Semantics](#crud-semantics)
  - [Application-owned mapping](#application-owned-mapping)
  - [create - Insert Only](#create---insert-only)
  - [read - Point Read](#read---point-read)
  - [update - Replace Existing](#update---replace-existing)
  - [upsert - Create or Replace](#upsert---create-or-replace)
  - [delete - Idempotent Delete](#delete---idempotent-delete-silent-on-missing-key)
  - [Document Field Injection](#document-field-injection)
- [Query DSL](#query-dsl)
  - [Portable Expressions](#portable-expressions)
  - [Parameter Binding](#parameter-binding)
  - [Portable Functions](#portable-functions)
  - [Pagination (Continuation Tokens)](#pagination-continuation-tokens)
  - [Full Scan (No Filter)](#full-scan-no-filter)
  - [Expression-Based Filtering](#expression-based-filtering)
  - [Multi-Condition Queries](#multi-condition-queries)
  - [Native Expression Passthrough](#native-expression-passthrough)
  - [Translation Pipeline](#translation-pipeline)
- [Querying with Partition Keys](#querying-with-partition-keys)
  - [Why Partition Keys Matter for Queries](#why-partition-keys-matter-for-queries)
  - [Combining Expression Filters with Key Design](#combining-expression-filters-with-key-design)
  - [Partition-Scoped Queries with QueryRequest.partitionKey()](#partition-scoped-queries-with-queryrequestpartitionkey)
- [Multi-Tenant Architecture Patterns](#multi-tenant-architecture-patterns)
- [Error Handling](#error-handling)
- [Result Set Control](#result-set-control)
  - [Checking Capabilities](#checking-capabilities)
  - [Limiting Results](#limiting-results)
  - [Ordering Results](#ordering-results)
  - [Combining with Native Expressions](#combining-with-native-expressions)
- [Change Feeds](#change-feeds)
  - [When to use change feeds](#when-to-use-change-feeds)
  - [The three primitives](#the-three-primitives)
  - [Provider provisioning prerequisites](#provider-provisioning-prerequisites)
  - [Reading a change feed (single-thread)](#reading-a-change-feed-single-thread)
  - [Persisting cursors across restarts](#persisting-cursors-across-restarts)
  - [Recovering from an expired cursor](#recovering-from-an-expired-cursor)
  - [Multi-threaded pattern (one worker per cursor)](#multi-threaded-pattern-one-worker-per-cursor)
  - [Provider semantics & caveats](#provider-semantics--caveats)
- [Extending change-feed history beyond 24 hours](#extending-change-feed-history-beyond-24-hours)
  - [Fail-fast capability gate](#fail-fast-capability-gate)
  - [How the opt-in is honoured](#how-the-opt-in-is-honoured)
  - [Cost callout — read before opting in](#cost-callout--read-before-opting-in)
- [Document TTL (Time-to-Live)](#document-ttl-time-to-live)
  - [Prerequisite: Enable Container-Level TTL](#prerequisite-enable-container-level-ttl)
  - [Writing with TTL](#writing-with-ttl)
  - [Checking TTL Support](#checking-ttl-support)
- [Document Metadata](#document-metadata)
  - [Reading Metadata](#reading-metadata)
  - [Provider Metadata Availability](#provider-metadata-availability)
  - [System Property Stripping](#system-property-stripping)
- [Document Size Enforcement](#document-size-enforcement)
- [Provider Diagnostics](#provider-diagnostics)
  - [Query Diagnostics](#query-diagnostics)
  - [Provider Field Availability](#provider-field-availability)
  - [Structured Logging](#structured-logging)
- [Customizing the User-Agent](#customizing-the-user-agent)
  - [Why Set a Suffix?](#why-set-a-suffix)
  - [Configuring the Suffix](#configuring-the-suffix)
  - [Resulting Header Format](#resulting-header-format)
  - [Validation Rules](#validation-rules)

---

## Key & Partition Key

### The Key Model

Every document stored through Multicloud DB is identified by a **`MulticloudDbKey`** - a portable
representation of the minimum key material needed to uniquely identify a record
across all three supported providers.

```java
import com.multiclouddb.api.MulticloudDbKey;

// 1. Simple partition-key-only key
MulticloudDbKey simple = MulticloudDbKey.of("order-123");

// 2. Partition key + sort key
MulticloudDbKey partitioned = MulticloudDbKey.of("customer-456", "order-123");

// 3. Partition + sort + composite components (future extensibility)
MulticloudDbKey composite = MulticloudDbKey.of("customer-456", "order-123",
    Map.of("region", "us-east", "year", "2025"));
```

The three fields:

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `partitionKey` | `String` | **Yes** (must be non-empty) | Distribution/hash key that determines which physical partition stores the item |
| `sortKey` | `String` | No (nullable) | Item identifier within a partition; maps to DynamoDB sort/range key, Cosmos DB `id`, or Spanner's second primary-key column |
| `components` | `Map<String,String>` | No (empty by default) | Additional composite key dimensions (extensibility) |

### When to Use Each Key Form

#### `MulticloudDbKey.of(partitionKey)` - Simple Key

Use when each document is independent and you don't need to group or co-locate
related documents.

```java
// A standalone configuration document
MulticloudDbKey configKey = MulticloudDbKey.of("app-settings");
client.upsert(address, configKey, settingsDoc);
```

**Provider behavior:**
- **Cosmos DB**: Uses `partitionKey` as **both** the document id and the partition key
  (effectively a single-partition-per-document layout).
- **DynamoDB**: Uses `partitionKey` as the hash key only - no sort key is written.
- **Spanner**: Uses `partitionKey` as both the `partitionKey` column and the `sortKey` column.

**Best for**: Lookup tables, configuration documents, singleton records.

#### `MulticloudDbKey.of(partitionKey, sortKey)` - Partition Key + Sort Key

Use when documents naturally belong to a group and you want to co-locate them
for efficient querying. The `partitionKey` defines the co-location group;
the `sortKey` uniquely identifies the document within the partition.

```java
// Positions within a portfolio - portfolioId is the partition key
MulticloudDbKey positionKey = MulticloudDbKey.of("portfolio-alpha", "position-001");

// Orders within a customer - customerId is the partition key
MulticloudDbKey orderKey = MulticloudDbKey.of("customer-456", "order-789");

// Events within a session - sessionId is the partition key
MulticloudDbKey eventKey = MulticloudDbKey.of("session-abc", "event-42");
```

**Provider behavior:**
- **Cosmos DB**: `sortKey` → document's `id` field; `partitionKey` → stored as the
  `partitionKey` field and used as the Cosmos DB partition key value. Documents
  sharing the same `partitionKey` value are co-located in the same logical partition.
- **DynamoDB**: `partitionKey` → hash key attribute (`"partitionKey"`); `sortKey` → sort key
  attribute (`"sortKey"`). Together they form the composite primary key.
- **Spanner**: `partitionKey` → `partitionKey` column; `sortKey` → `sortKey` column.
  Both form the composite primary key.

**Best for**: Parent-child relationships, multi-tenant data, event streams,
any scenario where you frequently query "all items within group X."

#### `MulticloudDbKey.of(pk, pk)` - Partition Key Equals Sort Key

A common pattern where the document's sort key equals its partition key. Each
document occupies its own partition. This is the simplest layout and is
appropriate when you don't need to group documents.

```java
// Each portfolio is its own partition
MulticloudDbKey portfolioKey = MulticloudDbKey.of("portfolio-alpha", "portfolio-alpha");
```

**Provider behavior**: Identical to `MulticloudDbKey.of(partitionKey, sortKey)` with an explicit
sort key value, but every document is in its own partition (no co-location).

**Best for**: Top-level entities (tenants, portfolios, users) where you access
documents primarily by their ID rather than querying within a group.

### Provider Storage Mapping

The `MulticloudDbKey` type is **portable** - you write `MulticloudDbKey.of(partitionKey, sortKey)` and each
provider maps it to its native key model. Here's exactly what happens in each:

#### Azure Cosmos DB

```
MulticloudDbKey.of("portfolio-alpha", "pos-1")
         │                  │
         ▼                  ▼
   ┌──────────────────────────────┐
   │  Cosmos DB Container         │
   │                              │
   │  Document JSON:              │
   │  {                           │
   │    "id": "pos-1",            │  ← key.sortKey()
   │    "partitionKey": "portfolio-alpha", │  ← key.partitionKey()
   │    ... other fields ...      │
   │  }                           │
   │                              │
   │  Point read uses:            │
   │    container.readItem(       │
   │      "pos-1",                │  ← sortKey (document id)
   │      new PartitionKey(       │
   │        "portfolio-alpha")    │  ← partitionKey value
   │    )                         │
   └──────────────────────────────┘
```

If `key.sortKey()` is null (i.e., `MulticloudDbKey.of(partitionKey)` was used), Cosmos uses
`key.partitionKey()` as both the document id and the partition key. This means every
document gets its own logical partition.

> **Note**: The container must be created with `/partitionKey` as the partition
> key path. The SDK merges a `"partitionKey"` field into the JSON document
> automatically before upsert.

#### Amazon DynamoDB

```
MulticloudDbKey.of("portfolio-alpha", "pos-1")
         │                  │
         ▼                  ▼
   ┌──────────────────────────────┐
   │  DynamoDB Table              │
   │  (database__collection)      │
   │                              │
   │  Item attributes:            │
   │    "partitionKey": "portfolio-alpha" │  ← Hash key (partition key)
   │    "sortKey":      "pos-1"   │  ← Sort key (range key)
   │    ... other attributes ...  │
   │                              │
   │  Point read uses:            │
   │    GetItem with:             │
   │      { "partitionKey": "portfolio-alpha", │
   │        "sortKey": "pos-1" }  │
   └──────────────────────────────┘
```

If `key.sortKey()` is null, no `"sortKey"` attribute is written or used in
reads. The table must be created with only a `"partitionKey"` hash key.

> **Note**: DynamoDB has no native "database" concept. The `ResourceAddress`
> database and collection are composed into a single table name using the
> pattern `database__collection` (double underscore separator).

#### Google Cloud Spanner

```
MulticloudDbKey.of("portfolio-alpha", "pos-1")
         │                  │
         ▼                  ▼
   ┌──────────────────────────────┐
   │  Spanner Table               │
   │                              │
   │  Row columns:                │
   │    partitionKey: "portfolio-alpha" │  ← Primary key col 1
   │    sortKey:      "pos-1"     │  ← Primary key col 2
   │    ... other columns ...     │
   │                              │
   │  Point read uses:            │
   │    SELECT * FROM table       │
   │    WHERE partitionKey = @pk  │
   │      AND sortKey = @sk       │
   └──────────────────────────────┘
```

If `key.sortKey()` is null, `sortKey` defaults to `key.partitionKey()` - giving a
composite primary key of `(pk, pk)`. Spanner always requires both columns
because the table DDL defines `PRIMARY KEY (partitionKey, sortKey)`.

### Partition Key Strategy Guide

Choosing the right partition key is the most important design decision for
performance and scalability. Here are common strategies:

| Strategy | Key Pattern | When to Use | Example |
|----------|------------|-------------|---------|
| **Entity-per-partition** | `MulticloudDbKey.of(pk, pk)` | Simple lookups, no parent-child queries | Tenants, configuration docs |
| **Parent-child grouping** | `MulticloudDbKey.of(parentId, childId)` | Query all items within a parent | Positions within a portfolio |
| **Tenant isolation** | `MulticloudDbKey.of(tenantId, docId)` | Multi-tenant with per-tenant queries | SaaS applications |
| **Time-based grouping** | `MulticloudDbKey.of(dateKey, eventId)` | Time-range queries, event streams | Logs partitioned by `2025-03` |
| **Category grouping** | `MulticloudDbKey.of(category, itemId)` | Filtered queries within a category | Products by department |
| **Composite** | `MulticloudDbKey.of(compositeValue, id)` | Multiple dimensions in partition | `region#category` |

#### Choosing Between Entity-per-Partition and Grouped Partitions

**Entity-per-partition** (`MulticloudDbKey.of(pk, pk)`):
- Every document gets its own partition
- Optimal for point reads - always a single-partition operation
- Queries for "all items of type X" require a **cross-partition scan**
- Works well when you don't need to query within groups

**Grouped partitions** (`MulticloudDbKey.of(parentId, childId)`):
- Related documents share a partition
- Point reads still work (the SDK resolves the full key)
- Queries within the group can be **partition-scoped** - much more efficient
- Requires knowing the partition key value at query time

**Rule of thumb**: If you frequently query "give me all X within Y", use Y
as the partition key. If you only read documents by their ID, use the
entity-per-partition pattern.

---

### Why MulticloudDbKey Is an Explicit Parameter

Every CRUD operation requires an explicit `MulticloudDbKey` parameter - the SDK never
extracts key material from the document body. This is a deliberate design
choice.

**Why not extract from the document?**

Some database SDKs (notably the Azure Cosmos DB Java SDK) can extract the
partition key and document ID automatically because the container's metadata
tells the SDK which JSON path holds the partition key, and `id` is a fixed
Cosmos convention. Multicloud DB cannot replicate this approach portably because the
key field names differ across providers.

When a provider writes a document, it injects key fields using **different
names**:

| Provider | `MulticloudDbKey.partitionKey()` stored as | `MulticloudDbKey.sortKey()` stored as |
|----------|-------------------------------|---------------------------|
| **Cosmos DB** | `partitionKey` (custom field) | `id` (built-in Cosmos field) |
| **DynamoDB** | `partitionKey` (hash key attribute) | `sortKey` (range key attribute) |
| **Spanner** | `partitionKey` (primary key column) | `sortKey` (primary key column) |

This means a document read back from **Cosmos DB** looks like:

```json
{"id": "pos-42", "partitionKey": "tenant-1", "name": "Alpha Fund"}
```

while the same document read back from **DynamoDB** looks like:

```json
{"sortKey": "pos-42", "partitionKey": "tenant-1", "name": "Alpha Fund"}
```

A convention-based overload - `upsert(address, document)` - would need to look
for `sortKey` on DynamoDB/Spanner but `id` on Cosmos DB. That requires
provider-aware extraction logic in what is supposed to be a provider-agnostic
interface, which defeats the purpose of a portable abstraction.

**Additional reasons for the explicit MulticloudDbKey design:**

| Concern | Explicit MulticloudDbKey | Extracted from Document |
|---------|-------------|------------------------|
| **`read()` / `delete()`** | Works - no document needed | Impossible - no document to extract from |
| **Consistency** | All 5 operations use the same signature pattern | Writes differ from reads/deletes |
| **Compile-time safety** | Missing key = compiler error | Missing field = runtime error in provider |
| **Key ≠ document** | Key can differ from document fields (remapping, replication) | Key must match document content |
| **Source of truth** | Key is authoritative; providers overwrite document fields | Ambiguous when key fields and document disagree |

The explicit MulticloudDbKey keeps the API **uniform** (same pattern for all operations),
**safe** (compiler-enforced), and **portable** (no provider-specific field name
assumptions). Each provider maps the MulticloudDbKey to its native key representation
internally - application code never needs to know which field name is used on
which backend.

---

## Resource Addressing

A `ResourceAddress` identifies the target database and collection for any
operation:

```java
import com.multiclouddb.api.ResourceAddress;

ResourceAddress addr = new ResourceAddress("my-database", "my-collection");
```

Both `database` and `collection` are required (non-empty strings). The portable
address maps to physical storage differently per provider.

### Provider Physical Mapping

| Part | Cosmos DB | DynamoDB | Spanner |
|------|-----------|----------|---------|
| `database` | Cosmos database name | Encoded into table name | N/A (database set in config) |
| `collection` | Cosmos container name | Encoded into table name | Spanner table name |
| **Physical** | `database / container` | Table: `database__collection` | Table within the configured DB |

#### Cosmos DB

Each `ResourceAddress` maps to a separate Cosmos DB **database** and
**container**. This provides true database-level isolation between tenants.

```
ResourceAddress("acme-risk-db", "portfolios")
  → Cosmos DB database: "acme-risk-db"
  → Cosmos container:   "portfolios"
```

#### DynamoDB

Since DynamoDB has no native database concept, the database dimension is encoded
into the table name using a double-underscore separator:

```
ResourceAddress("acme-risk-db", "portfolios")
  → DynamoDB table: "acme-risk-db__portfolios"
```

#### Spanner

Spanner uses the database configured at client creation time. The `database`
dimension in `ResourceAddress` is not used for physical addressing (Spanner has
a single database per client). Only the `collection` maps to a table name.

```
ResourceAddress("acme-risk-db", "portfolios")
  → Spanner table: "portfolios" (within the configured database)
```

### Database-per-Tenant Pattern

A common architecture for multi-tenant SaaS applications is to give each tenant
its own logical "database" using `ResourceAddress`:

```java
public ResourceAddress addressFor(String tenantId, String collection) {
    String database = tenantId + "-risk-db";
    return new ResourceAddress(database, collection);
}

// Tenant "acme-capital" accessing their portfolios:
ResourceAddress acmePortfolios = addressFor("acme-capital", "portfolios");
// → Cosmos: database="acme-capital-risk-db", container="portfolios"
// → DynamoDB: table="acme-capital-risk-db__portfolios"
// → Spanner: table="portfolios" (single database)
```

This pattern provides **complete data isolation** at the database level for
Cosmos DB and at the table level for DynamoDB - without any provider-specific
code.

### Provisioning Resources with `provisionSchema()`

When your application starts, it typically needs to ensure that all required
databases and containers/tables exist. The SDK provides `provisionSchema()` to
do this efficiently in a single call - with internal parallelism so that
application code does not need to manage threading.

```java
import java.util.*;

// Define the full schema: database name → list of collections
Map<String, List<String>> schema = new LinkedHashMap<>();
schema.put("admin-db",    List.of("tenants"));
schema.put("acme-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts"));
schema.put("shared-db",    List.of("market_data"));

// Provision everything - databases then containers, both phases in parallel
client.provisionSchema(schema);
```

**How it works internally:**

1. **Phase 1 - Databases**: All databases are created concurrently using a
   bounded thread pool (max 10 threads). The SDK waits for all database
   creations to complete before proceeding.
2. **Phase 2 - Containers**: All containers/tables are created concurrently
   using the same thread pool.

This two-phase approach ensures that databases exist before their containers
are created, while maximising throughput within each phase.

**Provider behavior:**

| Provider | Database Phase | Container/Table Phase |
|----------|---------------|----------------------|
| **Cosmos DB** (cloud) | Creates via Azure Resource Manager SDK (parallel) | Creates via data-plane `createContainerIfNotExists` (parallel) |
| **Cosmos DB** (emulator) | Creates via data-plane `createDatabaseIfNotExists` (parallel) | Creates via data-plane `createContainerIfNotExists` (parallel) |
| **DynamoDB** | No-op (no native database concept) | Creates tables named `database__collection` (parallel), waits for ACTIVE |
| **Spanner** | No-op (database configured at client construction) | Creates tables with DDL (parallel) |

**When to use `provisionSchema()` vs individual calls:**

| Approach | Use When |
|----------|----------|
| `provisionSchema(schema)` | Provisioning multiple databases/containers at startup - the SDK handles all parallelism |
| `ensureDatabase(name)` | Creating a single database on demand (e.g., new tenant onboarding) |
| `ensureContainer(address)` | Creating a single container on demand |

Providers may override `provisionSchema()` with provider-specific optimisations,
but the default SPI implementation works correctly for all providers.

---

## CRUD Semantics

These examples describe the coordinated development Map API and use
`import java.util.Map;`. Released beta callers must migrate point-read/feed
payload access and recompile; see [application-owned mapping](#application-owned-mapping).
Query Map/Object contracts and existing provider-specific update behavior are
not changed by this increment.

### Application-owned mapping

Applications explicitly map Java objects to `Map<String, Object>` and map returned
data back themselves. Multicloud does not discover POJOs, invoke a POJO mapper
or ship a mapping interface, codec hierarchy or serializer module. Separate
[JSON syntax callbacks](#application-owned-json-processing) handle cursor
persistence, not POJO mapping or provider storage encoding.

For example, use an application-configured Jackson mapper to save and read a
customer. Jackson is an optional **application dependency**, not an SDK requirement
or adapter. Applications own naming, dates, generics, coercion, unknown fields,
errors and mapper lifetime:

```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

ObjectMapper mapper = applicationConfiguredMapper;
Map<String, Object> fields = mapper.convertValue(customer,
        new TypeReference<Map<String, Object>>() {});
client.upsert(address, key, fields);
DocumentResult result = client.read(address, key);
if (result == null) throw new IllegalStateException("Document not found.");
Customer restored = mapper.convertValue(result.document(), Customer.class);
```

Keep these points in mind:

- Check for a null result before mapping a missing point read; see
  [migration details](#migrating-from-beta2).
- Accepted numeric input types do not guarantee identical precision across
  providers; see [supported values and numeric precision](#supported-values-and-numeric-precision).
- Do not mutate inputs during a synchronous write; see
  [input and result ownership](#input-and-result-ownership).

#### Manual mapping

The JDK-only [PlainCustomerMapping example](https://github.com/microsoft/multiclouddb-sdk-for-java/blob/b2c58ff1a277d8918b4497bea699410c3a2ad746/multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/PlainCustomerMapping.java)
illustrates handwritten mapping:

```java
PlainCustomerMapping mapping = new PlainCustomerMapping();
PlainCustomerMapping.Customer customer = new PlainCustomerMapping.Customer("Grace", 7);
client.create(address, key, mapping.toMap(customer));
DocumentResult result = client.read(address, key);
if (result == null) throw new IllegalStateException("Document not found.");
PlainCustomerMapping.Customer restored = mapping.fromMap(result.document());
```

#### Supported values and numeric precision

The document root must be a non-null Map; create, update and upsert reject a null
root with `INVALID_REQUEST` before native I/O. Empty Maps and nested null values
remain supported. Values allow non-null string-keyed Maps, ordered Lists, Strings, Booleans, null,
and exactly Byte, Short, Integer, Long, Float, Double, BigInteger and BigDecimal.
Float/Double must be finite. Cycles, arrays, sets, arbitrary POJOs, invalid keys
and other Number implementations fail with `INVALID_REQUEST` before native I/O.
Shared acyclic subtrees are valid. Missing keys differ from present nulls; use
`containsKey`. Equality follows JDK Map/List/Number semantics.

**Conservative portability recommendation:** supply integers as `Integer` or
`Long` within **-9,007,199,254,740,991 through +9,007,199,254,740,991, inclusive**.
This lowest-common-denominator (LCD) recommendation uses the JSON/binary64
interoperability interval in [RFC 8259 section 6](https://www.rfc-editor.org/rfc/rfc8259#section-6).
It is not a new validation bound or a native storage cap; values outside it are
not necessarily rejected or damaged. It does not guarantee lossless behavior
for every accepted Number class or every read, query and change-feed path,
nor restoration of the original Java subtype.

For fractional values, magnitude is not decimal precision: even `0.1` is not
exactly representable in binary floating point. **When exact large integers,
decimal values or scale matter, explicitly map BigInteger/BigDecimal to String
in the application and parse them on read.** Converting an already-rounded
Float/Double to String cannot recover lost precision. String storage changes
numeric comparison, ordering and aggregation semantics (for example, ordinary
string ordering places `"10"` before `"2"`).

Provider schema and representation rules still apply; see
[provider mapping and serialization](#provider-mapping-and-serialization) and
[Spanner JSON compatibility](#spanner-json-unicode-compatibility). In particular,
Spanner change feeds can retain nested Map/List fields as marked JSON STRINGs
rather than decoded containers. The LCD recommendation does not promise a common
nested feed shape. The [write-depth contract and native size limits](#document-size-enforcement)
also apply; the integer recommendation is not a new numeric validation bound.

For example, choose String before calling the SDK:

```java
BigInteger largeInteger = new BigInteger("9007199254740993");
BigDecimal amount = new BigDecimal("123.4500");
Map<String, Object> input = Map.of(
        "largeInteger", largeInteger.toString(),
        "amount", amount.toString());
client.upsert(address, key, input);
DocumentResult result = client.read(address, key);
if (result == null) throw new IllegalStateException("Document not found.");
BigInteger restoredInteger = new BigInteger((String) result.document().get("largeInteger"));
BigDecimal restoredAmount = new BigDecimal((String) result.document().get("amount"));
```

Keep query/filter values consistent with the chosen String representation.

#### Input and result ownership

Callers must not mutate inputs or nested containers during the **entire
synchronous write**. The SDK neither mutates them nor retains them after return.
Point-read/feed Maps and Lists are detached and mutable. QueryPage retains an
unmodifiable list and top-level row Maps, with nested mutability. Released
numeric conversion behavior remains. Writes follow the
[32-level nesting contract and native size enforcement](#document-size-enforcement).

#### Change-feed payloads

ChangeEvent data may be partial, a before-image, an array, a scalar or null.
Use `event.data() != null` to check for a payload. Top-level null means no
payload is available; an explicit top-level null is not a separate state.
A non-null empty Map is a payload with no included fields, and null-valued Map
entries remain present (`containsKey` distinguishes them from missing fields).
An event without data is still valid: its key, type, timestamp and provider
event ID remain available. Inspect the selected payload rather than assume a
full DTO. There is only the five-argument constructor; no separate presence
boolean or accessor is required.

| Provider | Payload selection and absence |
|---|---|
| Cosmos | Prefer `current` for create/update and `previous` for delete; use the other image when the preferred image is null/missing. Keep a supplied empty body, including AVAD tombstones. If neither image exists in an AVAD envelope, data is null while envelope/metadata identity and timestamps are retained. The existing bare-document fallback remains a payload. |
| DynamoDB | Use the native `hasNewImage()` / `hasOldImage()` presence flags for the selected image (new for create/update, old for delete). An absent SDK auto-constructed empty map becomes null; a supplied empty image remains an empty Map. A native `NULL` attribute remains a null-valued field, not an absent image. |
| Spanner | Respect `value_capture_type` and modification type: `NEW_VALUES` / `NEW_ROW` do not collect old images, so delete data is null even when `old_values` is `{}`. Modes collecting old values can supply a delete before-image. Missing fields, SQL null and selected JSON null produce null; a collected `{}` or an object emptied by `FIELD_DATA` filtering remains an empty Map. Existing numeric decoding/filtering is unchanged. |

For Spanner updates in old-and-new capture modes, a missing/SQL-null new image
retains the existing old-image fallback; a selected JSON null is not replaced
with another image. If capture metadata is absent or unrecognized, the reader
retains its legacy available-field selection rather than guessing a new mode.
See [Spanner capture types](https://cloud.google.com/spanner/docs/change-streams#value-capture-type).
These rules normalize available native images, not a guarantee of complete
post-images for every event or native configuration.

#### Migrating from beta.2

Against released API **0.1.0-beta.2**, write signatures remain Maps,
`DocumentResult.document()` changes from ObjectNode to Map, and
`ChangeEvent.data()` changes from JsonNode to Object. A missing point read still
returns null. QueryPage remains `List<Map<String, Object>>`, including nested
scalar/array values and existing unsupported top-level result errors.

Some no-image change-feed paths that previously synthesized an empty object or
returned a Cosmos transport envelope now return `data() == null`. The earlier
API already allowed null data; this is an image-normalization change, not newly
permitted nullability. Check `event.data() != null` before mapping a payload.
A supplied non-null empty Map still represents a payload without fields, and
`containsKey` distinguishes a null-valued field from an absent field. Events
without a payload still retain their key/type/timestamp/provider event ID.

**Breaking input validation:** values previously implicitly serialized by a
provider mapper (sets, arrays, POJOs, enums, UUIDs, dates and custom Number
implementations) and non-finite floating-point values are now rejected with
`INVALID_REQUEST`. Explicitly map them to the supported Map/List/scalar vocabulary
in the application; there is no implicit `toString()` fallback. A null document
root is also invalid for create/update/upsert; empty Maps and nested nulls remain valid.

Write nesting is now limited to **32 Map/List levels below the root Map**, rather
than the previous 1000-container traversal limit that included the root. The
existing 399 KiB logical-size estimate has been removed; native size rejection
may happen after I/O and differs by provider. Optional payload warnings are
advisory only, not a replacement storage-size guarantee. See
[exact validation and native size limits](#document-size-enforcement).
Cursor and read/query/feed parser limits are unchanged.

The API no longer supplies Jackson transitively for compilation or through JPMS.
Applications that use Jackson must declare their own Jackson dependencies and
appropriate `requires` directives (for example, `requires com.fasterxml.jackson.databind;`).
Spanner now declares its explicit internal Gson 2.15 dependency; this does not add a
JSON-library requirement to the common API.

Cursor persistence now takes explicit callbacks: `cursor.toToken(serialize)`
and `ChangeFeedCursor.fromToken(token, deserialize)`. Existing v1 JSON tokens
remain readable with a compatible adapter; no binary format or legacy decoder
is introduced. No client requires JSON callback configuration for construction,
CRUD, queries or in-memory cursor use. Spanner's stored/change-stream JSON is
handled internally by its provider, not application callbacks.

#### Provider mapping and serialization

| Provider | Multicloud mapping | Encoding and limitations retained |
|---|---|---|
| Cosmos | Detached request Maps; `Map.class` point reads; root-only system-field filtering | Native Cosmos JSON/wire encoding, key/id/TTL overwrite and decimal normalization. Feed bodies do not use point-read filtering |
| DynamoDB | Direct Map/List/scalar to AttributeValue and back | Native encoding; dotted N values read as Double, others as Integer then Long. Existing exponent/overflow limitations and unsupported native binary null fallback remain |
| Spanner | Typed mutations/rows; nested Map/List values use marked JSON STRINGs; FIELD_DATA selects visible fields | Protobuf/gRPC and required persisted JSON. Integer/Long use INT64, Float/Double use FLOAT64; root Byte/Short/BigInteger/BigDecimal retain STRING fallback. Native BYTES rows retain Base64 text conversion |

Spanner's JSON marker, FIELD_DATA and update transaction are existing storage
contracts, not redundant serialization to remove. In change feeds, `column_types`
identifies every retained scalar INT64 field after FIELD_DATA filtering; an
absent, null or filtered earlier column does not skip later fields. In-range integer
strings and integral numbers become Long without rounding; null/absence remain.
Missing/null/empty type metadata retains legacy representation. Malformed
declarations or invalid/overflowing declared INT64 values report
`PROVIDER_ERROR/readChanges/invalid_document_payload`. Other types and nested
numeric-looking strings are not inferred to be integers.

The API does not require a JSON library, but native provider SDKs may still depend
on one. These dependencies are not isolated from your application's dependencies.

JPMS applications require
`com.multiclouddb.api`; opening DTO packages to an application mapper is the
application's responsibility.

##### Spanner JSON Unicode compatibility

Spanner's internal Gson 2.15 reader uses strict parsing and rejects **unpaired
UTF-16 surrogates**, whether present as raw Java code units or JSON escapes such
as `\uD800` or `\uDC00`. These are malformed UTF-16, not ordinary Korean text,
valid emoji surrogate pairs, or a string-length limitation. Valid Unicode
continues to be supported. This does not add a common Map-input restriction or
change Cosmos/DynamoDB data paths, and does not establish that their services
preserve malformed Unicode.

**Existing data can be affected.** In point-read and query results, legacy
marked JSON STRINGs containing escaped lone surrogates now use the existing
parse-failure fallback: the entire raw marker-prefixed text (including the
leading U+0001 and `mcdb:json:` prefix) is returned instead of a Map/List or
other decoded value. Native JSON-column parse failures return the original JSON
text. Plain, unmarked STRING columns are not parsed by this reader; native
UTF-8 transport can still reject or replace malformed Java strings, so their
round-trip preservation is not guaranteed.

Other existing parse-failure policies remain in force:

- Invalid `FIELD_DATA` metadata disables field filtering on row reads, including
  the legacy null-column behavior; updates retain the incomplete-metadata policy.
  Change-feed metadata failures strip `FIELD_DATA` but keep the other fields.
- Change-feed image JSON failures return `{"raw": originalImageJson}` when no
  INT64 columns are declared; with INT64 declarations they fail with
  `PROVIDER_ERROR/readChanges/invalid_document_payload`. Key JSON failures use
  `malformed_envelope`, and invalid column-type declarations use
  `invalid_document_payload`. Nested marker STRINGs are not recursively decoded
  in change-feed images, so this is not a universal rejection of every event
  containing such stored text.

Inspect and correct affected stored values and metadata before upgrading; do not
assume all existing data is unaffected. Escaping a lone surrogate as `\uXXXX`
does not make it acceptable to the strict reader. Prefer valid Unicode at the
application boundary rather than relying on parser or transport replacement.

Both internal JSON readers explicitly retain a **1000-container nesting limit**,
rather than Gson's default 255. This counts from each parsed JSON root, separately
from the common write validator's 32 nested Map/List levels below the document
root. The metadata reader still requires a flat field-name array.
Native database limits remain separate: Spanner's native JSON-column nesting
limit does not apply to JSON encoded inside the SDK's marked STRING columns.

### Application-owned JSON processing

For common cursor persistence, this is **JSON to/from neutral values**, separate
from the optional POJO mapping above. Supply `Function<Object, String> serialize` and
`BiFunction<String, Class<?>, Object> deserialize`. The SDK requests only
`Object.class` (string-keyed Map, List, supported numbers, String, Boolean or
null). No POJO target is requested. The SDK validates the returned neutral
structures without copying their containers, then extracts immutable cursor state.
Do not modify returned Maps/Lists during the entire `fromToken` call. After it
returns, the cursor retains none of those containers and is unaffected by their
later modification. Validation is not a concurrent-mutation snapshot.

Both functions must be thread-safe, deterministic, and must not mutate or retain
their arguments. Pass the corresponding function explicitly to each persistence
call; the cursor does not retain it, and there is no global registry or client
callback configuration. Every provider can use independent application functions.
Only persistence/restoration needs them; `now()`, `listCursors`, client
construction and in-memory cursor use need none.
Even persisting a `now()` sentinel explicitly requires the corresponding function.

The application selects and configures its JSON library. This example uses
Jackson 2.18.6 as an **application dependency** with the released compatibility
profile; it is not an SDK fallback or a promise about arbitrary mapper defaults.

Configure the mapper and callbacks once and reuse them across operations.
Complete all mapper configuration before first use; do not reconfigure a shared
mapper while it is in use.

```java
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BiFunction;
import java.util.function.Function;

ObjectMapper json = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(1000).maxNumberLength(1000)
                .maxStringLength(20_000_000).maxNameLength(50_000).build())
        .build())
        .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .disable(DeserializationFeature.USE_LONG_FOR_INTS);
Function<Object, String> serialize = value -> {
    try {
        return json.writeValueAsString(value);
    } catch (IOException failure) {
        throw new UncheckedIOException(failure);
    }
};
BiFunction<String, Class<?>, Object> deserialize = (text, target) -> {
    if (target != Object.class) {
        throw new IllegalArgumentException("Unsupported JSON target.");
    }
    try (var parser = json.createParser(text)) {
        if (parser.nextToken() == null) return null;
        return json.readValue(parser, target);
    } catch (IOException failure) {
        throw new UncheckedIOException(failure);
    }
};
```

Use the initialized callbacks to persist and restore cursors:

```java
String saved = cursor.toToken(serialize);
ChangeFeedCursor resumed = ChangeFeedCursor.fromToken(saved, deserialize);
```

Keep this mapper separate from DTO-specific modules, coercions and polymorphic
typing. The compatibility profile has these deliberately specific behaviors:

- Object roots, arrays, scalars and null are supported. Integer tokens use
  Integer, Long, then BigInteger by range; floating tokens use Double (including
  overflow to infinity on read and negative zero). This does **not** relax finite
  Float/Double write validation or promise decimal fidelity across providers.
- Duplicate object keys keep the last value. One root is consumed; trailing
  content is ignored, and empty Object input produces null before cursor-shape checks.
  Comments, single quotes, unquoted names, leading-zero numbers, raw control
  characters and nonnumeric literals such as `NaN` are rejected. The SDK rejects
  a leading BOM, but permits one inside a string.
- Use the shown released parser constraints and its numeric-length accounting,
  not a total-token-character cap: decimal points, signs and exponent syntax
  do not all count like integer digits.
- Writers preserve keys, nulls, numeric values and string contents without type
  metadata. The SDK escapes isolated UTF-16 surrogates in cursor JSON before
  UTF-8/Base64URL encoding. Required JSON/wire encoding still occurs.

Signal **JSON processing failures** with `UncheckedIOException`. Cursor parse
failures become `CURSOR_EXPIRED/MALFORMED`.
Other callback exceptions, invalid result structures or a null serialized string
fail explicitly with `IllegalStateException` rather than being classified as
corrupted JSON. Runtime failures while accessing callback-returned containers or
values also use this sanitized contract-failure path, even if an accessor throws
`UncheckedIOException` or a cursor exception. Malformed retention fields remain
`CURSOR_EXPIRED/MALFORMED`; their values are not rendered into messages.
Diagnostics identify the serialize/deserialize stage and callback/access exception class, or a fixed
validation reason such as a null serializer result, unsupported neutral value,
non-string map key or cycle. Original callback messages, causes, suppressed
exceptions and payloads are omitted; keep application-side diagnostics if needed.

Spanner uses its own internal Gson reader/writer, independently of these cursor
functions. FIELD_DATA retains scalar lexical names (for example `2.00`, `1e+03`
and `-0`), and the marker, null handling and legacy corruption fallbacks remain:
invalid marked/native JSON can return raw text, invalid field metadata falls
back to legacy projection, and failed marked-container serialization retains the
logged textual fallback. These are storage compatibility rules, not a
universally portable JSON specification.

### create - Insert Only

`create()` inserts a new document. If a document with the same key already
exists, the operation **fails** with a conflict error. Use this when you need
insert-only semantics with duplicate detection.

```java
ResourceAddress addr = new ResourceAddress("mydb", "orders");
MulticloudDbKey key = MulticloudDbKey.of("customer-456", "order-123");
Map<String, Object> doc = Map.of("total", 99.95, "status", "pending");

client.create(addr, key, doc);   // Fails if document already exists
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `createItem()` | `putItem()` with `attribute_not_exists` condition | `newInsertBuilder` mutation |
| If exists | Throws CONFLICT | Throws CONFLICT | Throws CONFLICT |
| If not exists | Create | Create | Create |
| Return value | None (void) | None (void) | None (void) |

### read - Point Read

`read()` performs a single-document read using the full key. Returns a
`DocumentResult` wrapping a detached mutable Map, or `null` if not found.

```java
DocumentResult result = client.read(addr, MulticloudDbKey.of("customer-456", "order-123"));
if (result != null) {
    String status = (String) result.document().get("status");
}
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `readItem(id, partitionKey)` | `getItem(keyMap)` | `SELECT * WHERE partitionKey=@pk AND sortKey=@sk` |
| Not found | Returns `null` | Returns `null` | Returns `null` |
| Consistency | Session (default) | Eventual (default) | Strong |

Point reads are always efficient because the full key is provided. The provider
can go directly to the partition/item without scanning.

### update - Replace Existing

`update()` modifies an existing document. Cosmos and DynamoDB replace its body;
Spanner retains its existing partial-update transaction and FIELD_DATA merge.
If the document does **not** exist,
the operation **fails** with a not-found error. Use this when you need strict
update-only semantics.

```java
Map<String, Object> updated = Map.of("total", 109.95, "status", "shipped");

client.update(addr, MulticloudDbKey.of("customer-456", "order-123"), updated);   // Fails if not exists
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `replaceItem()` | `putItem()` with `attribute_exists` condition | `newUpdateBuilder` mutation |
| If exists | Replace | Replace | Update supplied fields |
| If not exists | Throws NOT_FOUND | Throws NOT_FOUND | Throws NOT_FOUND |
| Return value | None (void) | None (void) | None (void) |

### upsert - Create or Replace

`upsert()` creates a new document or **overwrites** an existing document with
the same key. It is an insert-or-update in all providers.

```java
ResourceAddress addr = new ResourceAddress("mydb", "orders");
MulticloudDbKey key = MulticloudDbKey.of("customer-456", "order-123");
Map<String, Object> doc = Map.of("total", 99.95, "status", "pending");

client.upsert(addr, key, doc);   // Creates or replaces the document
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `upsertItem()` | `putItem()` (no condition) | `insertOrUpdate` mutation |
| If exists | Replace | Replace | Replace |
| If not exists | Create | Create | Create |
| Return value | None (void) | None (void) | None (void) |

### delete - Idempotent Delete (silent on missing key)

`delete()` removes a document by key. If the document does not exist the
operation is a **silent no-op** — no exception is thrown. This is the LCD
across the supported providers: DynamoDB `DeleteItem` and Spanner
`Mutation.delete` are both idempotent natively, and the Cosmos provider
swallows the 404 to match. Callers can therefore retry deletes safely
without bespoke "ignore NOT_FOUND" handling.

```java
client.delete(addr, MulticloudDbKey.of("customer-456", "order-123"));
// Safe to call again — second invocation is also a no-op.
client.delete(addr, MulticloudDbKey.of("customer-456", "order-123"));
```

If you need to detect whether a key exists, use `read()` — it returns
`null` on every provider when the key does not exist, and does not mutate
state. `update()` also throws `NOT_FOUND` on a missing key, but it
requires a document body and **overwrites the existing document on hit**,
so it is not a safe pure existence probe.

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `deleteItem(id, partitionKey)` | `deleteItem` (no condition) | `Mutation.delete(table, key)` |
| Not found | 404 swallowed (silent) | Silent (native) | Silent (native) |
| Return value | None (void) | None (void) | None (void) |


### Document Field Injection

When you call `create()`, `update()`, or `upsert()`, the SDK **injects key
fields into the document** automatically. You don't need to manually set `"id"`
or `"partitionKey"` in your JSON - the provider handles this:

```java
Map<String, Object> doc = Map.of("name", "Alpha Fund", "type", "EQUITY");

// No need to set "id" or "partitionKey" in doc - injected by provider
client.upsert(addr, MulticloudDbKey.of("acme", "port-1"), doc);
```

**What gets injected per provider:**

| Provider | Injected Fields | Source |
|----------|----------------|--------|
| **Cosmos DB** | `"id"` = key.sortKey() or key.partitionKey(), `"partitionKey"` = key.partitionKey() | Merged into document JSON before write |
| **DynamoDB** | `"partitionKey"` = key.partitionKey(), `"sortKey"` = key.sortKey() | Added as DynamoDB item attributes |
| **Spanner** | `"partitionKey"` = key.partitionKey(), `"sortKey"` = key.sortKey() or key.partitionKey() | Written as Spanner row columns |

> **Important**: If your document JSON already contains an `"id"` field, it
> will be **overwritten** by the key parameter. The key is always the source
> of truth for the document's identity.

---

## Query DSL

### Portable Expressions

The SDK provides a **portable query expression language** - write a WHERE-clause
filter once and it's automatically translated to each provider's native query
syntax (Cosmos SQL, DynamoDB PartiQL, Spanner GoogleSQL).

```java
QueryRequest query = QueryRequest.builder()
    .expression("status = @status AND priority > @minPriority")
    .parameter("status", "active")
    .parameter("minPriority", 3)
    .maxPageSize(25)
    .build();

QueryPage page = client.query(addr, query);
for (Map<String, Object> item : page.items()) {
    System.out.println(item);
}
```

This produces the following native queries:

| Provider | Generated Query |
|----------|----------------|
| **Cosmos DB** | `SELECT * FROM c WHERE (c.status = @status AND c.priority > @minPriority)` |
| **DynamoDB** | `SELECT * FROM "table" WHERE (status = ? AND priority > ?)` |
| **Spanner** | `SELECT * FROM table WHERE (status = @status AND priority > @minPriority)` |

### Parameter Binding

Parameters use `@name` syntax in expressions and are passed as a
`Map<String, Object>`:

```java
// Using .parameter() builder method (recommended)
QueryRequest query = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(50)
    .build();

// Using .parameters() with a pre-built map
QueryRequest query2 = QueryRequest.builder()
    .expression("sector = @sector AND marketValue > @minValue")
    .parameters(Map.of(
        "sector", "Technology",
        "minValue", 100000.0
    ))
    .maxPageSize(25)
    .build();
```

**Supported parameter types**: `String`, `Integer`, `Long`, `Double`, `Float`,
`Boolean`. Parameters are bound safely - no SQL injection risk.

### Portable Functions

Five functions are supported across all providers:

| Function | Syntax | Cosmos SQL | DynamoDB PartiQL | Spanner GoogleSQL |
|----------|--------|------------|-----------------|-------------------|
| **STARTS_WITH** | `STARTS_WITH(field, @val)` | `STARTSWITH(c.field, @val)` | `begins_with(field, ?)` | `STARTS_WITH(field, @val)` |
| **CONTAINS** | `CONTAINS(field, @val)` | `CONTAINS(c.field, @val)` | `contains(field, ?)` | `STRPOS(field, @val) > 0` |
| **FIELD_EXISTS** | `FIELD_EXISTS(field)` | `IS_DEFINED(c.field)` | `attribute_exists(field)` | `field IS NOT NULL` |
| **STRING_LENGTH** | `STRING_LENGTH(field) > @n` | `LENGTH(c.field) > @n` | `size(field) > ?` | `CHAR_LENGTH(field) > @n` |
| **COLLECTION_SIZE** | `COLLECTION_SIZE(field) > @n` | `ARRAY_LENGTH(c.field) > @n` | `size(field) > ?` | `ARRAY_LENGTH(field) > @n` |

#### Function Examples

```java
// Find portfolios whose name starts with "Alpha"
QueryRequest q1 = QueryRequest.builder()
    .expression("STARTS_WITH(name, @prefix)")
    .parameter("prefix", "Alpha")
    .build();

// Find positions containing "Tech" in the sector field
QueryRequest q2 = QueryRequest.builder()
    .expression("CONTAINS(sector, @term)")
    .parameter("term", "Tech")
    .build();

// Find documents that have a "metadata" field
QueryRequest q3 = QueryRequest.builder()
    .expression("FIELD_EXISTS(metadata)")
    .build();

// Find names longer than 20 characters
QueryRequest q4 = QueryRequest.builder()
    .expression("STRING_LENGTH(name) > @minLen")
    .parameter("minLen", 20)
    .build();
```

### Pagination (Continuation Tokens)

All queries support cursor-based pagination using continuation tokens. Request
a page, then pass the continuation token to get the next page:

```java
// First page
QueryRequest firstPage = QueryRequest.builder()
    .expression("status = @status")
    .parameter("status", "active")
    .maxPageSize(25)
    .build();

QueryPage page = client.query(addr, firstPage);
processItems(page.items());

// Subsequent pages
while (page.continuationToken() != null) {
    QueryRequest nextPage = QueryRequest.builder()
        .expression("status = @status")
        .parameter("status", "active")
        .maxPageSize(25)
        .continuationToken(page.continuationToken())
        .build();
    page = client.query(addr, nextPage);
    processItems(page.items());
}
```

Continuation tokens are **opaque strings** - their format differs by provider
(Cosmos uses its own JSON token, DynamoDB uses an encoded last-evaluated-key,
Spanner uses a numeric offset). Never parse or construct them manually.

### Full Scan (No Filter)

To retrieve all documents in a collection (a full scan), omit the expression:

```java
QueryRequest scanAll = QueryRequest.builder()
    .maxPageSize(100)
    .build();

QueryPage page = client.query(addr, scanAll);
```

> **Warning**: Full scans read every document in the collection. For large
> datasets, this is expensive (cross-partition in Cosmos, full table scan in
> DynamoDB and Spanner). Always prefer expression-based filtering when possible.

### Expression-Based Filtering

Filter documents using field comparisons, logical operators, and functions:

```java
// Simple equality
QueryRequest q1 = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameter("pid", "portfolio-alpha")
    .build();

// Range query
QueryRequest q2 = QueryRequest.builder()
    .expression("marketValue BETWEEN @low AND @high")
    .parameter("low", 50000.0)
    .parameter("high", 200000.0)
    .build();

// Set membership
QueryRequest q3 = QueryRequest.builder()
    .expression("severity IN (@s1, @s2)")
    .parameter("s1", "HIGH")
    .parameter("s2", "CRITICAL")
    .build();

// Negation
QueryRequest q4 = QueryRequest.builder()
    .expression("NOT status = @excluded")
    .parameter("excluded", "CLOSED")
    .build();
```

### Multi-Condition Queries

Combine multiple conditions with `AND` and `OR`, using parentheses for grouping:

```java
// All active high-value tech positions
QueryRequest query = QueryRequest.builder()
    .expression(
        "portfolioId = @pid AND sector = @sector AND marketValue > @minValue"
    )
    .parameter("pid", "portfolio-alpha")
    .parameter("sector", "Technology")
    .parameter("minValue", 100000.0)
    .maxPageSize(50)
    .build();

// Complex boolean logic with grouping
QueryRequest complex = QueryRequest.builder()
    .expression(
        "(severity = @high OR severity = @critical) AND status = @open"
    )
    .parameter("high", "HIGH")
    .parameter("critical", "CRITICAL")
    .parameter("open", "OPEN")
    .build();
```

### Native Expression Passthrough

When you need provider-specific features not available in the portable DSL
(e.g., `LIKE`, `ORDER BY`, aggregate functions), bypass the portable layer
with `nativeExpression()`:

```java
// Cosmos DB - full SQL capability
QueryRequest cosmosQuery = QueryRequest.builder()
    .nativeExpression(
        "SELECT c.symbol, c.marketValue FROM c " +
        "WHERE c.portfolioId = @pid ORDER BY c.marketValue DESC"
    )
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(10)
    .build();

// DynamoDB - PartiQL syntax
QueryRequest dynamoQuery = QueryRequest.builder()
    .nativeExpression(
        "SELECT * FROM \"acme-risk-db__positions\" " +
        "WHERE begins_with(symbol, 'AA')"
    )
    .maxPageSize(10)
    .build();

// Spanner - GoogleSQL syntax
QueryRequest spannerQuery = QueryRequest.builder()
    .nativeExpression(
        "SELECT * FROM positions WHERE STARTS_WITH(symbol, 'AA') " +
        "ORDER BY marketValue DESC LIMIT 10"
    )
    .build();
```

> **Warning**: `expression` and `nativeExpression` are **mutually exclusive** -
> setting both throws an error. Native expressions break portability: switching
> providers requires rewriting the query.

### Translation Pipeline

When you use a portable `expression`, the SDK processes it through a four-stage
pipeline before execution:

```
┌───────────────────────────────────────────────────────────────────┐
│  1. PARSE     ExpressionParser converts the string to a typed    │
│               AST (Abstract Syntax Tree)                         │
│                                                                  │
│  2. VALIDATE  ExpressionValidator checks that all @parameters    │
│               are bound and function signatures are correct      │
│                                                                  │
│  3. TRANSLATE Provider-specific ExpressionTranslator converts    │
│               the AST to native query syntax                     │
│                                                                  │
│  4. EXECUTE   Provider runs the translated query against the     │
│               database with bound parameters                     │
└───────────────────────────────────────────────────────────────────┘
```

This is fully transparent - your application code only provides the portable
expression string and parameters.

---

## Querying with Partition Keys

### Why Partition Keys Matter for Queries

In distributed databases, the **partition key determines where data lives
physically**. This has a direct impact on query performance:

- **Partition-scoped query**: When the query filter includes the partition key
  value, the database can go directly to the relevant partition. This is
  **O(partition size)**, not O(total data).
- **Cross-partition query (fan-out)**: When the partition key is not included
  in the filter, the database must scan **all partitions**. This is expensive
  and may not be supported in all providers.

#### Cosmos DB

Cosmos DB partitions data by the `/partitionKey` path. When a query includes
`partitionKey = @value`, Cosmos reads only that partition. Without it, the query
fans out across all partitions (requires the `cosmos.crossPartitionQuery`
feature flag).

#### DynamoDB

DynamoDB uses the hash key (`partitionKey`) as the partition key. Queries within
a partition (using the hash key) are efficient. Without it, a full table **Scan**
is required.

#### Spanner

Spanner distributes data across splits based on the primary key prefix. The
`(partitionKey, sortKey)` composite primary key provides locality for documents
sharing the same `partitionKey` prefix.

### Combining Expression Filters with Key Design

The most efficient queries combine **good key design** with **expression
filtering**. Example: positions within a portfolio.

#### Approach 1: Entity-per-partition + Full Scan + Client-Side Filter

```java
// Key: MulticloudDbKey.of(positionId, positionId) - each position in its own partition

// Query: fetch ALL positions, filter in Java
List<Map<String, Object>> allPositions = client.query(addr,
    QueryRequest.builder().maxPageSize(200).build()
).items();

List<Map<String, Object>> filtered = allPositions.stream()
    .filter(p -> "portfolio-alpha".equals(p.get("portfolioId")))
    .toList();
```

**Problem**: Reads every position document, performs a cross-partition scan,
then discards most results. O(N) where N = total positions across all
portfolios.

#### Approach 2: Entity-per-partition + Expression Filter (Better)

```java
// Key: MulticloudDbKey.of(positionId, positionId) - still entity-per-partition

// Query: let the database filter - only matching documents are returned
QueryRequest query = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(50)
    .build();

List<Map<String, Object>> positions = client.query(addr, query).items();
```

**Better**: The database applies the filter - you only receive matching
documents. But this is still a cross-partition scan (the database still reads
all partitions, it just doesn't return non-matching documents).

#### Approach 3: Partition-per-parent + Expression Filter (Best)

```java
// Key: MulticloudDbKey.of(portfolioId, positionId) - positions grouped by portfolio

// Query: the database knows to read only the "portfolio-alpha" partition
QueryRequest query = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(50)
    .build();

List<Map<String, Object>> positions = client.query(addr, query).items();
```

**Best**: By using `portfolioId` as the partition key (first argument to
`MulticloudDbKey.of`), the database can scope the query to a single partition. Combined with
the expression filter, this is a **single-partition read** - the most efficient
pattern possible.

> **Note on DynamoDB**: When documents use `MulticloudDbKey.of(portfolioId, positionId)`,
> `portfolioId` becomes the hash key (`"partitionKey"` attribute) and
> `positionId` becomes the sort key (`"sortKey"` attribute). This gives you
> efficient single-partition queries by `portfolioId` with range queries on
> `positionId` within each partition.

### Partition-Scoped Queries with `QueryRequest.partitionKey()`

The SDK provides a **portable partition-scoping mechanism** via
`QueryRequest.partitionKey()`. Instead of relying on the query expression to
include the partition key implicitly, you can set it explicitly on the query
request. Each provider maps this to its native partition-scoping mechanism:

| Provider | What `.partitionKey(value)` Does |
|----------|----------------------------------|
| **Cosmos DB** | Sets `CosmosQueryRequestOptions.setPartitionKey(new PartitionKey(value))` - query reads only that logical partition |
| **DynamoDB** | Adds a `partitionKey = :pk` equality condition to the scan filter - restricts results to items sharing the given hash key value |
| **Spanner** | Adds a `partitionKey = @pk` equality condition to the SQL WHERE clause |

#### Basic Usage

```java
// Scope query to a specific partition (e.g., a portfolio)
QueryRequest query = QueryRequest.builder()
    .expression("sector = @sector")
    .parameter("sector", "Technology")
    .partitionKey("portfolio-alpha")
    .maxPageSize(50)
    .build();

// Only returns positions within "portfolio-alpha" that match sector = Technology
QueryPage page = client.query(positionsAddr, query);
```

#### Full Scan Within a Partition

Omit the expression to retrieve all documents in a single partition:

```java
// Get ALL positions in portfolio-alpha (no expression filter)
QueryRequest allInPartition = QueryRequest.builder()
    .partitionKey("portfolio-alpha")
    .maxPageSize(100)
    .build();

QueryPage page = client.query(positionsAddr, allInPartition);
```

This is far more efficient than a full cross-partition scan - it reads only
the documents belonging to `"portfolio-alpha"`.

#### Cross-Partition Query (Default)

When `partitionKey` is not set (or set to `null`), the query fans out across
all partitions - the same behavior as before:

```java
// No partitionKey → cross-partition scan
QueryRequest crossPartition = QueryRequest.builder()
    .expression("severity = @sev")
    .parameter("sev", "CRITICAL")
    .maxPageSize(25)
    .build();
```

#### Combining with Native Expressions

`partitionKey()` works with both portable and native expressions:

```java
// Portable expression + partition scoping
QueryRequest portable = QueryRequest.builder()
    .expression("marketValue > @min")
    .parameter("min", 100000.0)
    .partitionKey("portfolio-alpha")
    .build();

// Native Cosmos SQL + partition scoping
QueryRequest native = QueryRequest.builder()
    .nativeExpression("SELECT c.symbol, c.marketValue FROM c ORDER BY c.marketValue DESC")
    .partitionKey("portfolio-alpha")
    .build();
```

#### Performance Impact

| Query Type | Cosmos DB | DynamoDB | Spanner |
|------------|-----------|----------|---------|
| With `.partitionKey()` | Single-partition read | Filtered by hash key | Filtered by partition key |
| Without `.partitionKey()` | Cross-partition fan-out | Full table scan | Full table scan |
| **Cost difference** | 1 partition vs. N partitions | 1 filter vs. full scan | 1 filter vs. full scan |

> **Best practice**: Always set `.partitionKey()` when you know the partition
> value at query time. This is especially important for Cosmos DB, where
> cross-partition queries consume significantly more RU/s.

---

## Multi-Tenant Architecture Patterns

The SDK supports multi-tenant architectures through the combination of
`ResourceAddress` database isolation and partition key design:

```
┌──────────────────────────────────────────────────┐
│               Risk Platform                       │
│                                                   │
│  ┌─────────────────────────────────────────────┐ │
│  │ TenantManager.addressFor("acme", "portfolios")│
│  │   → ResourceAddress("acme-risk-db", "portfolios") │
│  └────────────────────┬────────────────────────┘ │
│                       │                           │
│      ┌────────────────┼────────────────┐         │
│      ▼                ▼                ▼         │
│  ┌─────────┐   ┌───────────┐   ┌───────────┐   │
│  │ Cosmos   │   │ DynamoDB  │   │ Spanner   │   │
│  │ DB:      │   │ Table:     │   │ Table:    │   │
│  │ acme-    │   │ acme-      │   │ portfolios│   │
│  │ risk-db  │   │ risk-db__  │   │           │   │
│  │ /portfolios│ │ portfolios │   │           │   │
│  └─────────┘   └───────────┘   └───────────┘   │
└──────────────────────────────────────────────────┘
```

Each tenant's data is completely isolated at the storage level:
- **Cosmos DB**: Separate database per tenant
- **DynamoDB**: Separate tables per tenant (encoded as `tenantDb__collection`)
- **Spanner**: Shares a physical database, but collection names provide logical separation

---

## Error Handling

All provider exceptions are caught and wrapped in `MulticloudDbException` with a
portable error category:

```java
try {
    client.upsert(addr, key, doc);
} catch (MulticloudDbException e) {
    MulticloudDbErrorCategory cat = e.error().category();
    if (MulticloudDbErrorCategory.THROTTLED.equals(cat)) {
        retryWithBackoff();
    } else if (MulticloudDbErrorCategory.NOT_FOUND.equals(cat)) {
        createResource();
    } else if (MulticloudDbErrorCategory.CONFLICT.equals(cat)) {
        resolveConflict();
    } else if (MulticloudDbErrorCategory.AUTHENTICATION_FAILED.equals(cat)) {
        refreshCredentials();
    } else {
        log.error("Operation failed: provider={}, operation={}, category={}, status={}, retryable={}",
                e.error().provider(), e.error().operation(), cat,
                e.error().statusCode(), e.error().retryable());
    }
}
```

For native exceptions handled by the provider error mappers, the SDK already
preserves the native message in both `e.getMessage()` and `e.error().message()`,
and the original exception in `e.getCause()`. `getMessage()` alone does **not**
format every structured field. Inside the catch block, inspect the envelope and
optional diagnostics directly:

```java
MulticloudDbError error = e.error(); // provider, operation, category, statusCode, retryable
Map<String, String> details = error.providerDetails();
OperationDiagnostics diagnostics = e.diagnostics();
String requestId = diagnostics == null ? details.get("requestId") : diagnostics.requestId();
String nativeMessage = error.message();
Throwable nativeCause = e.getCause();
```

Available details vary: Cosmos exposes activity ID as `requestId`, `subStatusCode`
and `requestCharge`; DynamoDB exposes `requestId`, `errorCode` and `serviceName`;
Spanner exposes `grpcStatus` and `errorMessage`, with no request ID supplied by
the current mapper. Missing information is not synthesized, and these fields
do not estimate document bytes. Native messages, causes and provider details
can contain sensitive information: apply application redaction/access policy
before logging them or a full exception. Raw exception logging is not
automatically safe.

Common write-validation errors instead identify the violated rule, actual Java
type and allowed type/condition, or observed depth and maximum (root Map depth
zero). They do not include document values, field names/Map keys or input
`toString()` output. This is separate from the
[cursor JSON callback boundary](#application-owned-json-processing), which
sanitizes callback failures and does not retain the original callback exception
or message. No new logging facility or general-purpose sanitizer is introduced.

See [compatibility.md](compatibility.md) for the full error category mapping
table across all providers.

---

## Result Set Control

The SDK supports portable `limit` (Top N) and `orderBy` on providers that declare the respective capabilities. Both are **capability-gated**: callers should check provider capabilities before using them. Unsupported options are not guaranteed to be silently ignored; providers may fail fast with `UNSUPPORTED_CAPABILITY` (for example, DynamoDB when `orderBy` is specified). Also note that DynamoDB's `result_limit` support is **per-page**, not a global Top N across the full logical result set.

### Checking Capabilities

```java
CapabilitySet caps = client.capabilities();
boolean canLimit  = caps.isSupported(Capability.RESULT_LIMIT);
boolean canOrder  = caps.isSupported(Capability.ORDER_BY);
```

| Capability | Cosmos DB | DynamoDB | Spanner |
|------------|:---------:|:--------:|:-------:|
| `result_limit` (Top N) | ✓ (`SELECT TOP N`) | ✓ (per-page) | ✓ (`LIMIT`) |
| `order_by` | ✓ | ✗ | ✓ |

> **Note:** DynamoDB's `result_limit=true` caps the _current scan page_, not
> the total result set. Use continuation tokens to iterate all matching items.

### Limiting Results

```java
QueryRequest q = QueryRequest.builder()
        .expression("score >= @min")
        .parameter("min", 80)
        .limit(10)                        // top 10 results
        .build();

QueryPage page = client.query(address, q);
System.out.println("Got " + page.items().size() + " items");
```

### Ordering Results

```java
QueryRequest q = QueryRequest.builder()
        .expression("portfolioId = @pid")
        .parameter("pid", "portfolio-42")
        .orderBy("marketValue", SortDirection.DESC)   // sort descending
        .orderBy("symbol",      SortDirection.ASC)    // secondary sort
        .limit(20)
        .build();
```

`SortOrder` validates field names against `[A-Za-z_][A-Za-z0-9_.]*` at construction time, rejecting any character that could be used for injection.

### Combining with Native Expressions

Handling of `orderBy` and `limit` with `nativeExpression` is provider-specific. For Cosmos DB and Spanner native passthrough, these fields are ignored and the native query string is sent as provided. For DynamoDB `ExecuteStatement`, `limit` is still applied, and setting `orderBy` causes an error. Use provider-native syntax for ordering/limiting when you need exact control over native query execution:

```java
// Cosmos DB native - full SQL control
QueryRequest q = QueryRequest.builder()
        .nativeExpression(
            "SELECT TOP 10 c.symbol, c.marketValue FROM c " +
            "ORDER BY c.marketValue DESC")
        .build();
```

---

## Change Feeds

Change feeds let you read the **ordered stream of modifications** (`CREATE`,
`UPDATE`, `DELETE`) to a collection, so downstream systems can react to data
changes without polling the full collection. The SDK exposes a single portable
model that works the same across Cosmos DB, DynamoDB Streams, and Spanner
change streams.

### When to use change feeds

Use the change-feed API when you need to:

- Materialise a downstream view (search index, cache, analytics warehouse)
  from an authoritative store.
- Trigger workflows when documents change (notifications, audit pipelines,
  webhooks).
- Replicate a subset of data to another system.

Change feeds are **not** a replacement for `query()`. They surface change
events in commit order, not snapshot semantics; if you need a consistent
point-in-time read of a whole collection, use `query()`.

### The three primitives

| Type / Method | Purpose |
|---|---|
| `ChangeFeedCursor` | An opaque, immutable position in the stream. Persist via `cursor.toToken(serialize)` / `ChangeFeedCursor.fromToken(token, deserialize)`; the token wire format is intentionally opaque and may evolve across SDK versions. |
| `ChangeFeedCursor.now()` | A provider-agnostic sentinel meaning "the live tip at the time of the next read". The first `readChanges` call hydrates it. |
| `client.listCursors(address)` | Discovers the current set of provider-side partitions and returns one cursor per partition, each positioned at the live tip. Use this to seed a multi-threaded reader. On Cosmos this fetches the feed ranges and runs a one-item warmup query per range to capture a live-tip continuation, so the RU cost scales with the partition count; cache the cursors for the lifetime of your reader rather than calling `listCursors` per page. |
| `client.readChanges(address, cursor)` | Drains **one page** of events from `cursor` and returns a `ChangeFeedPage` carrying `events`, `nextCursor`, `hasMore`, and `terminal` flags. |
| `CursorExpiredException` | Thrown when the SDK detects that a cursor can no longer be honoured (provider trimmed events, client-side 24h age cap, malformed/wrong-provider/wrong-resource token). |

Cursors are **opaque to your application**. Persist them as the
`String` returned by `cursor.toToken(serialize)` and restore them with
`ChangeFeedCursor.fromToken(token, deserialize)` — do not introspect the token
structure.

Check capability before you call:

```java
CapabilitySet caps = client.capabilities();
if (!caps.isSupported(Capability.CHANGE_FEED)) {
    throw new IllegalStateException(
            "Provider " + client.providerId() + " does not support change feeds");
}
```

### Provider provisioning prerequisites

The SDK does **not** auto-provision change-stream infrastructure on
`provisionSchema()` — you must configure it once per collection so that
CREATE / UPDATE / DELETE distinctions are preserved.

| Provider | What you must provision | How |
|---|---|---|
| **Azure Cosmos DB** | Container created with an **All-Versions-and-Deletes (AVAD)** change-feed policy, on an account that supports it. | Configure container settings in Azure Portal / ARM / Bicep; with the Java SDK, attach `ChangeFeedPolicy.createAllVersionsAndDeletesPolicy(Duration)` to the `CosmosContainerProperties` you pass to `createContainerIfNotExists`. |
| **Amazon DynamoDB** | Table stream enabled with `StreamSpecification(NEW_AND_OLD_IMAGES)`. | Enable via `UpdateTable` API or table console. The 24-hour retention is fixed by the service. |
| **Google Cloud Spanner** | `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = 'NEW_ROW')` DDL applied to the database. | Run as part of your schema migration. The default stream name resolved by the SDK is `<collection>_changes`; override per collection with the `changeStream.<collection>` connection key. |

Without these, the first `listCursors` or `readChanges` call surfaces a
portable `UNSUPPORTED_CAPABILITY` (or `PROVIDER_ERROR` on unhandled
shapes) carrying a provider-specific `reason` discriminator:

| Provider | Error class | `providerDetails.reason` |
|---|---|---|
| Cosmos | `MulticloudDbException(UNSUPPORTED_CAPABILITY)` mapped from a Cosmos 400 BadRequest whose message mentions AVAD / `ChangeFeedPolicy`. | `avad_not_enabled` |
| DynamoDB | `MulticloudDbException(UNSUPPORTED_CAPABILITY)` (table stream not enabled, or `StreamSpecification` not `NEW_AND_OLD_IMAGES`). | `stream_not_enabled` |
| Spanner | `MulticloudDbException(UNSUPPORTED_CAPABILITY)` (referenced change stream missing or pointed at the wrong table). | `stream_not_enabled` |

All three normalise to the same portable category, so a single
`catch (MulticloudDbException e)` branch on
`e.error().category() == UNSUPPORTED_CAPABILITY` works across providers;
inspect `e.error().providerDetails().get("reason")` only if the call
site needs to surface the specific provisioning hint.

### Reading a change feed (single-thread)

```java
ResourceAddress address = new ResourceAddress("appdb", "orders");

// Start from the live tip (skip historical events).
ChangeFeedCursor cursor = ChangeFeedCursor.now();

while (true) {
    ChangeFeedPage page = client.readChanges(address, cursor);

    for (ChangeEvent ev : page.events()) {
        System.out.printf("%s %s commitTs=%s%n",
                ev.type(), ev.key(), ev.commitTimestamp());
        applyDownstream(ev);
    }

    cursor = page.nextCursor();
    persist(cursor.toToken(serialize)); // save after every successful batch

    if (!page.hasMore()) {
        // Caught up. Sleep briefly to avoid hot-spinning the provider.
        Thread.sleep(500);
    }
}
```

### Persisting cursors across restarts

Use the `serialize` and `deserialize` functions from
[application-owned JSON processing](#application-owned-json-processing).

`ChangeFeedCursor` is engineered so a single `String` is the complete state.
Persist `cursor.toToken(serialize)` after **every successful** batch — typically in the
same transaction as the downstream side-effect to guarantee at-least-once
delivery:

```java
String token = cursor.toToken(serialize);
saveTokenToDurableStore(workerId, token);

// On restart:
ChangeFeedCursor restored = ChangeFeedCursor.fromToken(
        loadTokenFromDurableStore(workerId), deserialize);
```

The token carries:

- The provider id (`cosmos` / `dynamo` / `spanner`).
- The resource binding (`database/collection`).
- The per-partition continuation positions.
- An issued-at timestamp, refreshed every time the SDK returns a `nextCursor`.

If a restored token's issued-at is more than **24 hours** in the past, the
SDK throws `CursorExpiredException` client-side without contacting the
provider — see the [recovery guidance](#recovering-from-an-expired-cursor)
below.

### Recovering from an expired cursor

`CursorExpiredException` is thrown on `readChanges` (or eagerly on
`ChangeFeedCursor.fromToken` for client-side issues) when the cursor cannot
be honoured. The reason is exposed in
`exception.error().providerDetails().get("reason")`:

| `reason` | Cause | Recovery |
|---|---|---|
| `TOKEN_AGED_OUT` | The cursor's issued-at is > 24 h old. | Mint a fresh `ChangeFeedCursor.now()` and accept the gap. |
| `PROVIDER_TRIMMED` | The provider trimmed events the cursor was about to read (Cosmos 410, Dynamo `TrimmedDataAccessException`, Spanner partition outside retention). | Re-bootstrap with `listCursors()` and accept the gap. |
| `ITERATOR_EXPIRED` | A persisted server-side iterator handle aged out before its next read (today: DynamoDB Streams' ~5-minute inactivity window on a persisted shard iterator). Events at that position may still exist but the bookmark is no longer addressable. | Re-bootstrap with `listCursors()` from the live tip; records produced between the expired iterator's position and the new live tip will be skipped. |
| `MALFORMED` / `VERSION_UNSUPPORTED` | Token isn't a valid SDK token, or was minted by a newer codec. | Reject the token and start fresh. |
| `PROVIDER_MISMATCH` / `RESOURCE_MISMATCH` | Token was minted against a different provider or `database/collection`. | Operator / configuration error — do not silently restart; alert. |

The portable recovery pattern is:

```java
try {
    page = client.readChanges(address, cursor);
} catch (CursorExpiredException e) {
    String reason = e.error().providerDetails().getOrDefault("reason", "");
    if ("PROVIDER_MISMATCH".equals(reason) || "RESOURCE_MISMATCH".equals(reason)) {
        throw e; // configuration bug — fail loud.
    }
    // Best-effort restart at the live tip; downstream will get a gap.
    LOG.warn("Cursor expired ({}). Restarting from live tip.", reason);
    cursor = ChangeFeedCursor.now();
    continue;
}
```

### Multi-threaded pattern (one worker per cursor)

For throughput, scale horizontally by dedicating **one worker thread per
cursor** returned by `listCursors`. Each worker drives its own cursor; the
SDK does not require coordination between workers.

A complete runnable worker-pool example will ship in a follow-up release. The
sketch:

```java
List<ChangeFeedCursor> cursors = client.listCursors(address);
ExecutorService pool = Executors.newFixedThreadPool(cursors.size());
for (ChangeFeedCursor seed : cursors) {
    pool.submit(() -> runWorker(client, address, seed));
}

// In runWorker:
ChangeFeedCursor cursor = restoredOr(seed);
while (!Thread.currentThread().isInterrupted()) {
    ChangeFeedPage page = client.readChanges(address, cursor);
    for (ChangeEvent ev : page.events()) apply(ev);
    cursor = page.nextCursor();
    persistPerWorker(workerId, cursor.toToken(serialize));
    if (!page.hasMore()) Thread.sleep(500);
}
```

Provider-side partition splits and merges are absorbed transparently by the
SDK: the next cursor's token carries the updated set of partitions, so the
worker continues without re-listing.

### Provider semantics & caveats

| Concern | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Maximum history horizon | 24 h baseline; up to 30 d via opt-in (`ChangeFeedConfig.extendedRetention`) provisioning an AVAD `ChangeFeedPolicy` (Continuous Backup tier–capped) | **24 h fixed** (DynamoDB Streams is service-bounded) | 24 h baseline; up to 7 d via opt-in (`ChangeFeedConfig.extendedRetention`) emitted as `CREATE CHANGE STREAM ... OPTIONS(retention_period)` |
| `commitTimestamp` source | AVAD `metadata.crts`, falling back to `_ts` (second resolution) | `ApproximateCreationDateTime` (sub-second, approximate) | True commit timestamp (microsecond) |
| Emits `DELETE`? | Yes (AVAD policy required) | Yes (with `NEW_AND_OLD_IMAGES`) | Yes |
| CREATE vs UPDATE distinguishable? | Yes (via AVAD `metadata.operationType`) | Yes (via `OperationType`) | Yes (via `mod_type`) |
| Partition discovery cost | Cheap — `getFeedRanges()` is a metadata call | One `DescribeStream` per call | One bootstrap TVF call |

For applications that need history older than 24 h with cross-provider
portability, the default 24-hour baseline is the lowest common denominator
(DynamoDB Streams is service-bounded). To extend the floor on Cosmos and
Spanner — at the cost of losing parity with the Dynamo adapter — see
[Extending change-feed history beyond 24 hours](#extending-change-feed-history-beyond-24-hours)
below. The compatibility matrix in [compatibility.md](compatibility.md)
calls out the per-provider ceilings.

`commitTimestamp` is documented as "the provider's authoritative ordering
timestamp," not necessarily a true commit time. Use it for ordering and
checkpointing, not as a precise clock reading.

---

## Extending change-feed history beyond 24 hours

The portable change-feed read path ships with a 24-hour history floor on
every provider. To request a longer server-side retention window, opt in via
`ChangeFeedConfig.builder().extendedRetention(Duration)` on
`MulticloudDbClientConfig`:

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
        .provider(ProviderId.COSMOS)
        .connection("endpoint", "...")
        .changeFeed(ChangeFeedConfig.builder()
                .extendedRetention(Duration.ofDays(7))
                .build())
        .build();

try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {
    client.ensureContainer(ResourceAddress.of("orders", "events"));
    // ...
}
```

### Fail-fast capability gate

At client-build time the SDK reads the provider's `CapabilitySet` and refuses
to construct the client when the requested provider does not declare
`Capability.EXTENDED_CHANGE_FEED_HISTORY`:

| Provider | `EXTENDED_CHANGE_FEED_HISTORY` |
|---|---|
| Cosmos DB | ✅ supported |
| Spanner | ✅ supported |
| DynamoDB | ❌ not supported (server-side stream retention is fixed at 24 h) |

When the capability is absent the SDK throws
`MulticloudDbException` with category `UNSUPPORTED_CAPABILITY` and
`providerDetails.reason="extended_retention_unavailable"` — the gate throws
before any change-feed-substrate I/O is issued, and the underlying provider
client (already constructed by `adapter.createClient(...)`) is `close()`-d on
the same path so no control-plane channels leak.

### How the opt-in is honoured

| Provider | `ensureContainer()` behaviour | Failure → portable mapping |
|---|---|---|
| Cosmos DB | Sets an AVAD `ChangeFeedPolicy` on the container properties carrying the requested retention. | If the account lacks Continuous Backup, returns `UNSUPPORTED_CAPABILITY` (`reason=continuous_backup_required`). |
| Spanner | Emits `CREATE CHANGE STREAM <table>_changes FOR <table> OPTIONS (value_capture_type = 'NEW_ROW', retention_period = '<value>')` after the table-create (the `NEW_ROW` capture type matches what the SDK's change-feed reader requires for full-row payloads). If a stream of the same name already exists with a different retention, `ensureContainer()` reads back the active retention via `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and refuses to silently honour the mismatch — it throws `UNSUPPORTED_CAPABILITY` (`reason=extended_retention_not_enacted`, with both `requestedRetention` and `activeRetention` in `providerDetails`) so the divergence is loud rather than a silent retention drop. The stream name matches the reader's default convention so `listCursors()` / `readChanges()` transparently pick it up. | If the requested retention exceeds the database's native maximum, returns `UNSUPPORTED_CAPABILITY` (`reason=retention_exceeds_native_max`). |

`ChangeFeedConfig` defaults (`ChangeFeedConfig.defaults()`) leave behaviour
**bit-for-bit identical to v1** — the opt-in surface is purely additive.

### Cost callout — read before opting in

Extending the change-feed history window changes the bill differently on each
provider; the windows are **not interchangeable**. Plan the request against
the price driver that actually moves on your provider:

- **Cosmos DB** — extended retention requires Continuous Backup. The
  Continuous-Backup tiers (7-day and 30-day) are billed monthly per
  *provisioned-storage GB* — flat-rate by tier, not by change-volume.
  Verify your account is on the tier that covers the requested window before
  opting in.
- **Spanner** — change-stream retention beyond the default 7 days requires a
  database explicitly configured for extended retention. Cost scales with
  **change-data volume × retention** (writes that hit columns covered by the
  stream are billed for the full retention window). Wide tables with
  high-cardinality updates can produce a much larger bill at 30-day retention
  than at 1-day retention.
- **DynamoDB** — not applicable; server-side stream retention is fixed at
  24 h. For >24 h history with Dynamo today, drain the stream into a
  customer-provisioned Kafka cluster (outside the SDK).

The portable 24-hour baseline incurs no extra cost on any provider.

> **Roadmap — DynamoDB Kafka path:** A v1.x item will close the Dynamo
> capability gap by archiving DynamoDB Streams into a customer-provisioned
> Kafka cluster. Once shipped, callers will opt in to
> `ChangeFeedConfig.extendedRetention(...)` against Dynamo by additionally
> wiring Kafka broker configuration on `MulticloudDbClientConfig` — the
> consumer-facing `listCursors()` / `readChanges()` surface stays unchanged,
> so the only adapter-specific delta is the broker config at client-build
> time.

---
## Document TTL (Time-to-Live)

Set a per-document expiry at write time using `OperationOptions.ttlSeconds()`. Supported on `create()`, `upsert()`, and `update()`.

### Prerequisite: Enable Container-Level TTL

TTL is enforced at the provider level and requires the collection to be configured with TTL support before writing TTL-bearing documents.

| Provider | Required Setup |
|----------|----------------|
| Cosmos DB | Enable "Default Time to Live" on the container (set to `-1` for item-level control) |
| DynamoDB | Enable TTL on the table specifying `ttlExpiry` as the TTL attribute |
| Spanner | No native row-level TTL - `ROW_LEVEL_TTL=false`; `ttlSeconds` is silently ignored |

### Writing with TTL

```java
OperationOptions opts = OperationOptions.builder()
        .ttlSeconds(3_600)          // expire in 1 hour
        .build();

// TTL on create
client.create(address, key, doc, opts);

// TTL on upsert (create-or-replace)
client.upsert(address, key, doc, opts);

// TTL on update - carries TTL forward through the full-replace write
client.update(address, key, updatedDoc, opts);
```

### Checking TTL Support

```java
if (client.capabilities().isSupported(Capability.ROW_LEVEL_TTL)) {
    client.create(address, key, doc, OperationOptions.builder()
            .ttlSeconds(86_400)
            .build());
} else {
    // Provider will ignore ttlSeconds; write without TTL
    client.create(address, key, doc);
}
```

> **Important:** If you omit `ttlSeconds` on an `update()` call, the previously
> stored TTL attribute will be overwritten with no TTL (documents become
> permanent). Always pass the same `OperationOptions` on every write if you
> want TTL to persist.

---

## Document Metadata

Read write-metadata (last-modified timestamp, TTL expiry, version/ETag) by setting `includeMetadata(true)` on read operations. Metadata is **null by default** to avoid unnecessary overhead.

### Reading Metadata

```java
OperationOptions opts = OperationOptions.builder()
        .includeMetadata(true)
        .build();

DocumentResult result = client.read(address, key, opts);
DocumentMetadata meta = result.metadata();

if (meta != null) {
    System.out.println("Last modified : " + meta.lastModified());
    System.out.println("Expires at    : " + meta.ttlExpiry());    // null if no TTL
    System.out.println("Version/ETag  : " + meta.version());
}
```

### Provider Metadata Availability

| Field | Cosmos DB | DynamoDB | Spanner |
|-------|:---------:|:--------:|:-------:|
| `lastModified` | ✓ (from `_ts`) | ✗ | ✗ |
| `ttlExpiry` | ✗ | ✓ (stored attribute) | ✗ |
| `version` | ✓ (ETag) | ✗ | ✗ |

Fields the provider cannot supply are returned as `null`. Use
`Capability.WRITE_TIMESTAMP` to check before accessing `metadata()`.

```java
if (client.capabilities().isSupported(Capability.WRITE_TIMESTAMP)) {
    DocumentResult r = client.read(address, key,
            OperationOptions.builder().includeMetadata(true).build());
    System.out.println(r.metadata().lastModified());
}
```

### System Property Stripping

The `document()` field of `DocumentResult` is always stripped of provider
system properties (`_ts`, `_etag`, `_rid`, `_self`, `_attachments`, `partitionKey`) before being returned, ensuring the same document shape regardless of which provider it was read from.

---

## Document Size Enforcement

The former **399 KiB logical JSON estimate is no longer enforced**. It could not
reliably predict native storage size: keys/TTL, metadata, native encoding and
existing data all affect that size. The common validator does not serialize or
measure input to predict native acceptance.

Native SDKs/services independently decide storage acceptance. DynamoDB has a
[400 KiB item limit](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Constraints.html#limits-items),
Cosmos DB has a [2 MB item limit](https://learn.microsoft.com/en-us/azure/cosmos-db/concepts-limits#per-item-limits),
and Spanner applies [column, row and mutation/commit limits](https://cloud.google.com/spanner/quotas).
These measure different representations, not a common input-Map byte count.
The same input can therefore succeed on one provider and fail on another.
Spanner's partial `update()` preserves untouched columns and merges field
metadata, so its final stored/commit size cannot be determined from the supplied
fields alone. Cosmos and DynamoDB `update()` currently replace the document.

Native failures continue through the existing provider error normalization.
For example, Cosmos HTTP 413 and DynamoDB `ValidationException` map to
non-retryable `INVALID_REQUEST`; Spanner `INVALID_ARGUMENT` maps to
`INVALID_REQUEST` with native retryability. Do not treat every provider quota
error as a size error, or every `INVALID_REQUEST` as a size failure. Native
validation may occur after network I/O and incur cost. Inspect the normalized
category/status and documented provider constraints; do not blindly retry an
unchanged oversized request. No automatic chunking, offload or bypass option is
provided.

### Best-effort payload warnings

The dedicated SLF4J logger **`com.multiclouddb.size`** emits WARN events with marker
`size.payload-advisory` when an available native payload observation is
**at least 400 KiB (409,600 bytes)**. This threshold is an advisory reference
chosen with DynamoDB's item limit in mind, **not a measurement of that limit's
accounted size**. It does not reject writes, change results or request SDK retries.

| Provider | Observed signal | Timing and limits |
|---|---|---|
| Cosmos | `operation-max-response-payload` from completed, successful write diagnostics | After create/replace/upsert returns; response bodies are already enabled. This is the maximum payload observed in the operation, possibly across service interactions/retries, not an exact final-item size. Null/incomplete diagnostics or unavailable/zero measurements produce no size warning. |
| DynamoDB | No additional size observer | Native size failures and existing error normalization remain. Current writes use PutItem, not UpdateItem with ALL_NEW; response/capacity data does not supply final-item bytes. |
| Spanner | `commit-request-protobuf` from the existing CommitRequest | Before send, including commit attempts/retries and potentially multiple mutations. It includes request overhead, excludes unchanged columns in partial updates and does not measure final rows, indexes, change streams or commit accounting. CommitResponse does not contain final rows. |

This deliberate provider asymmetry is diagnostic, not a portable size guarantee.
No extra read or separate serialization is used to manufacture a measurement.
The first protobuf `getSerializedSize()` may traverse/encode fields before caching
the result; this is not a zero-overhead claim. The Spanner interceptor is installed
on the SDK-owned client configuration, not a general hook for independently
constructed native clients. No warning means neither native fit nor compatibility
with a provider that has not been deployed/tested. Stronger sizing and
partial-update accounting remain separate work.

Warnings contain only provider/operation, metric kind, byte count and fixed
advisory text, not documents, field names, keys, resource identifiers or full
native diagnostics. Existing native error/diagnostic logging policies are
unchanged. Control visibility through normal logging configuration, for example
Logback `<logger name="com.multiclouddb.size" level="ERROR"/>`; no SDK opt-out or
test-mode option is added. Suppressing WARN does not disable native limits or
guarantee that the native SDK avoids its own diagnostics/size work.

**Tests can assert on captured warnings after the write completes.** With
test-only Logback and JUnit, isolate the client/test from concurrent writers:

```java
var logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger("com.multiclouddb.size");
var previousLevel = logger.getLevel();
var captured = new ch.qos.logback.core.read.ListAppender<
        ch.qos.logback.classic.spi.ILoggingEvent>();
captured.start();
logger.setLevel(ch.qos.logback.classic.Level.WARN);
logger.addAppender(captured);
try {
    client.upsert(address, key, document); // May already be persisted.
    org.junit.jupiter.api.Assertions.assertTrue(captured.list.stream().noneMatch(
            event -> event.getLevel() == ch.qos.logback.classic.Level.WARN
                    && event.getFormattedMessage().startsWith("size.payload-advisory")),
            "Inspect payload-size advisory; do not retry an already successful write");
} finally {
    logger.detachAppender(captured);
    logger.setLevel(previousLevel);
    captured.stop();
}
```

The assertion fails the **test**, not the SDK write. Never throw an assertion from
a logging appender/native callback or wrap the test assertion in write retry
logic. This example does not detect missing signals, prove service acceptance
or validate the final state of a Spanner partial update.

### Exact input validation and nesting

`create()`, `update()` and `upsert()` still reject null roots, unsupported neutral
values, non-string keys, non-finite floating values, cycles and excessive depth
with non-retryable `INVALID_REQUEST` before provider delegation. Closed-client
validation remains first. Input validation is not a guarantee of native success.

The common depth limit is **32 nested Map/List levels below the document root**:
the root Map is depth **0**, each Map or List value adds one level, empty
containers count, and scalar values add none. The longest path determines depth;
shared acyclic subtrees are allowed. Depth 32 is accepted and depth 33 rejected.
For example, `{"field": []}` has depth 1, while `{"field": [{"child": {}}]}`
has depth 3.

This uses [DynamoDB's 32-level document limit](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html#HowItWorks.DataTypes.Document)
as the lowest common denominator; Cosmos permits 128 embedded object/array
levels. DynamoDB's root item attribute map is not an extra nested `M`; each
nested Map/List maps to one `M`/`L`. Their wire wrappers and added root scalar
keys/TTL do not add document levels. Spanner maps root fields to columns and
nested Map/List fields to marker-prefixed JSON STRINGs, not native JSON columns.
The limit applies to submitted update fields, not untouched legacy data.
It does not change cursor validation or provider read/query/feed parser limits.

---

## Provider Diagnostics

Every operation can return structured diagnostics via `OperationDiagnostics`. Diagnostics are available on `QueryPage` and can be accessed after read/write operations through provider-specific logging (SLF4J).

### Query Diagnostics

```java
QueryRequest q = QueryRequest.builder()
        .expression("status = @s")
        .parameter("s", "active")
        .build();

QueryPage page = client.query(address, q);

OperationDiagnostics diag = page.diagnostics();  // may be null
if (diag != null) {
    System.out.printf("Provider  : %s%n", diag.provider().id());
    System.out.printf("Operation : %s%n", diag.operation());
    System.out.printf("Latency   : %s ms%n", diag.duration().toMillis());
    System.out.printf("Request ID: %s%n", diag.requestId());
    System.out.printf("RU charge : %.2f%n", diag.requestCharge());
    System.out.printf("Item count: %d%n", diag.itemCount());
    System.out.printf("ETag      : %s%n", diag.etag());
}
```

### Provider Field Availability

| Field | Cosmos DB | DynamoDB | Spanner |
|-------|:---------:|:--------:|:-------:|
| `requestId` | activityId | x-amzn-RequestId | - |
| `statusCode` | HTTP status | HTTP status | gRPC code |
| `requestCharge` | RU cost | consumed capacity | - |
| `etag` | `_etag` | - | commit timestamp |
| `sessionToken` | session token | - | - |
| `itemCount` | ✓ | ✓ | ✓ |
| `duration` | ✓ | ✓ | ✓ |

### Structured Logging

All providers emit structured SLF4J log lines at `ERROR` (failures) and
`DEBUG` (slow operations) levels with a consistent key=value format:

```
cosmos.error op=read db=mydb col=users statusCode=408 subStatus=20001 activityId=abc message=...
dynamo.error op=upsert table=mydb__users requestId=xyz awsErrorCode=ProvisionedThroughputExceededException
```

This format is grep-friendly and compatible with log aggregation systems
(e.g., Azure Monitor, CloudWatch, Google Cloud Logging).

---

## Customizing the User-Agent

All provider clients (Cosmos, DynamoDB, Spanner) automatically stamp outgoing
requests with a canonical SDK user-agent token of the form:

```
multiclouddb-sdk-java/<sdk-version> (<jvm-version>; <os-name> <os-version>)
```

You can append your own identifier to this token via the configuration
builder. This is useful for downstream identification of applications,
frameworks, or tenants in provider-side telemetry.

### Why Set a Suffix?

- Distinguish traffic from different applications sharing the same SDK
  version (e.g., `my-app/1.2.3`)
- Identify a framework or library that wraps this SDK (e.g.,
  `acme-data-layer/4.0`)
- Tag traffic from a specific tenant or environment for support and
  diagnostics

### Configuring the Suffix

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.COSMOS)
    .connection("endpoint", "https://my-account.documents.azure.com:443/")
    .connection("key", "...")
    .userAgentSuffix("my-app/1.2.3")
    .build();

MulticloudDbClient client = MulticloudDbClientFactory.create(config);
```

The suffix is optional. Pass `null` to clear a previously-set value when
deriving a new builder from an existing config.

### Resulting Header Format

When a suffix is configured, providers append it to the canonical token,
separated by a single space:

```
multiclouddb-sdk-java/0.1.0-beta.1 (17.0.5; Mac OS X 14.4) my-app/1.2.3
```

Provider-specific notes:

- **Cosmos** - sent as the `User-Agent` HTTP header.
- **DynamoDB** - contributed via `ClientOverrideConfiguration#putAdvancedOption`
  with `SdkAdvancedClientOption.USER_AGENT_SUFFIX`. The AWS SDK appends this
  to its own `aws-sdk-java/...` token.
- **Spanner** - contributed via gax `FixedHeaderProvider` for the gRPC
  `user-agent` metadata. The gax channel preserves and merges with the
  underlying gRPC default user-agent.

### Validation Rules

The setter enforces strict rules to protect the user-agent header from
injection of CR/LF or other control characters that could break framing in
HTTP/2 or HTTP/1.1 transports:

| Rule              | Limit                                              |
| ----------------- | -------------------------------------------------- |
| Maximum length    | 256 characters                                     |
| Allowed code points | Printable US-ASCII (0x20–0x7E) + horizontal tab (0x09) |
| Disallowed        | CR (0x0D), LF (0x0A), NUL, other control chars     |

Violations throw `IllegalArgumentException` from the builder setter, before
any client is constructed:

```java
MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder();

// Throws IllegalArgumentException - contains newline
builder.userAgentSuffix("my-app\nv1.0");

// Throws IllegalArgumentException - too long
builder.userAgentSuffix("x".repeat(257));
```

If a `null` value is passed, the suffix is cleared and providers send only
the canonical SDK token.
