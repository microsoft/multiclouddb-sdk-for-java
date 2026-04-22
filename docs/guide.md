# Multicloud DB SDK — Developer Guide

Comprehensive reference for the Multicloud DB SDK's key management, CRUD operations,
query DSL, and provider-specific storage behavior. For a quick-start tutorial
and architecture overview, see the [Getting Started](getting-started.md) guide. For the
portable API surface and error mapping reference, see
[Compatibility](compatibility.md).

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
  - [Why Key Is an Explicit Parameter](#why-key-is-an-explicit-parameter)
- [Resource Addressing](#resource-addressing)
  - [Provider Physical Mapping](#provider-physical-mapping)
  - [Database-per-Tenant Pattern](#database-per-tenant-pattern)
  - [Provisioning Resources with provisionSchema()](#provisioning-resources-with-provisionschema)
- [CRUD Semantics](#crud-semantics)
  - [create — Insert Only](#create--insert-only)
  - [read — Point Read](#read--point-read)
  - [update — Replace Existing](#update--replace-existing)
  - [upsert — Create or Replace](#upsert--create-or-replace)
  - [delete — Idempotent Delete](#delete--idempotent-delete)
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

Every document stored through Multicloud DB is identified by a **`Key`** — a portable
representation of the minimum key material needed to uniquely identify a record
across all three supported providers.

```java
import com.multiclouddb.api.Key;

// 1. Simple partition-key-only key
Key simple = Key.of("order-123");

// 2. Partition key + sort key
Key partitioned = Key.of("customer-456", "order-123");

// 3. Partition + sort + composite components (future extensibility)
Key composite = Key.of("customer-456", "order-123",
    Map.of("region", "us-east", "year", "2025"));
```

The three fields:

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `partitionKey` | `String` | **Yes** (must be non-empty) | Distribution/hash key that determines which physical partition stores the item |
| `sortKey` | `String` | No (nullable) | Item identifier within a partition; maps to DynamoDB sort/range key, Cosmos DB `id`, or Spanner's second primary-key column |
| `components` | `Map<String,String>` | No (empty by default) | Additional composite key dimensions (extensibility) |

### When to Use Each Key Form

#### `Key.of(partitionKey)` — Simple Key

Use when each document is independent and you don't need to group or co-locate
related documents.

```java
// A standalone configuration document
Key configKey = Key.of("app-settings");
client.upsert(address, configKey, settingsDoc);
```

**Provider behavior:**
- **Cosmos DB**: Uses `partitionKey` as **both** the document id and the partition key
  (effectively a single-partition-per-document layout).
- **DynamoDB**: Uses `partitionKey` as the hash key only — no sort key is written.
- **Spanner**: Uses `partitionKey` as both the `partitionKey` column and the `sortKey` column.

**Best for**: Lookup tables, configuration documents, singleton records.

#### `Key.of(partitionKey, sortKey)` — Partition Key + Sort Key

Use when documents naturally belong to a group and you want to co-locate them
for efficient querying. The `partitionKey` defines the co-location group;
the `sortKey` uniquely identifies the document within the partition.

```java
// Positions within a portfolio — portfolioId is the partition key
Key positionKey = Key.of("portfolio-alpha", "position-001");

// Orders within a customer — customerId is the partition key
Key orderKey = Key.of("customer-456", "order-789");

// Events within a session — sessionId is the partition key
Key eventKey = Key.of("session-abc", "event-42");
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

#### `Key.of(pk, pk)` — Partition Key Equals Sort Key

A common pattern where the document's sort key equals its partition key. Each
document occupies its own partition. This is the simplest layout and is
appropriate when you don't need to group documents.

```java
// Each portfolio is its own partition
Key portfolioKey = Key.of("portfolio-alpha", "portfolio-alpha");
```

**Provider behavior**: Identical to `Key.of(partitionKey, sortKey)` with an explicit
sort key value, but every document is in its own partition (no co-location).

**Best for**: Top-level entities (tenants, portfolios, users) where you access
documents primarily by their ID rather than querying within a group.

### Provider Storage Mapping

The `Key` type is **portable** — you write `Key.of(partitionKey, sortKey)` and each
provider maps it to its native key model. Here's exactly what happens in each:

#### Azure Cosmos DB

```
Key.of("portfolio-alpha", "pos-1")
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

If `key.sortKey()` is null (i.e., `Key.of(partitionKey)` was used), Cosmos uses
`key.partitionKey()` as both the document id and the partition key. This means every
document gets its own logical partition.

> **Note**: The container must be created with `/partitionKey` as the partition
> key path. The SDK merges a `"partitionKey"` field into the JSON document
> automatically before upsert.

#### Amazon DynamoDB

```
Key.of("portfolio-alpha", "pos-1")
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
Key.of("portfolio-alpha", "pos-1")
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

If `key.sortKey()` is null, `sortKey` defaults to `key.partitionKey()` — giving a
composite primary key of `(pk, pk)`. Spanner always requires both columns
because the table DDL defines `PRIMARY KEY (partitionKey, sortKey)`.

### Partition Key Strategy Guide

Choosing the right partition key is the most important design decision for
performance and scalability. Here are common strategies:

| Strategy | Key Pattern | When to Use | Example |
|----------|------------|-------------|---------|
| **Entity-per-partition** | `Key.of(pk, pk)` | Simple lookups, no parent-child queries | Tenants, configuration docs |
| **Parent-child grouping** | `Key.of(parentId, childId)` | Query all items within a parent | Positions within a portfolio |
| **Tenant isolation** | `Key.of(tenantId, docId)` | Multi-tenant with per-tenant queries | SaaS applications |
| **Time-based grouping** | `Key.of(dateKey, eventId)` | Time-range queries, event streams | Logs partitioned by `2025-03` |
| **Category grouping** | `Key.of(category, itemId)` | Filtered queries within a category | Products by department |
| **Composite** | `Key.of(compositeValue, id)` | Multiple dimensions in partition | `region#category` |

#### Choosing Between Entity-per-Partition and Grouped Partitions

**Entity-per-partition** (`Key.of(pk, pk)`):
- Every document gets its own partition
- Optimal for point reads — always a single-partition operation
- Queries for "all items of type X" require a **cross-partition scan**
- Works well when you don't need to query within groups

**Grouped partitions** (`Key.of(parentId, childId)`):
- Related documents share a partition
- Point reads still work (the SDK resolves the full key)
- Queries within the group can be **partition-scoped** — much more efficient
- Requires knowing the partition key value at query time

**Rule of thumb**: If you frequently query "give me all X within Y", use Y
as the partition key. If you only read documents by their ID, use the
entity-per-partition pattern.

---

### Why Key Is an Explicit Parameter

Every CRUD operation requires an explicit `Key` parameter — the SDK never
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

| Provider | `Key.partitionKey()` stored as | `Key.sortKey()` stored as |
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

A convention-based overload — `upsert(address, document)` — would need to look
for `sortKey` on DynamoDB/Spanner but `id` on Cosmos DB. That requires
provider-aware extraction logic in what is supposed to be a provider-agnostic
interface, which defeats the purpose of a portable abstraction.

**Additional reasons for the explicit Key design:**

| Concern | Explicit Key | Extracted from Document |
|---------|-------------|------------------------|
| **`read()` / `delete()`** | Works — no document needed | Impossible — no document to extract from |
| **Consistency** | All 5 operations use the same signature pattern | Writes differ from reads/deletes |
| **Compile-time safety** | Missing key = compiler error | Missing field = runtime error in provider |
| **Key ≠ document** | Key can differ from document fields (remapping, replication) | Key must match document content |
| **Source of truth** | Key is authoritative; providers overwrite document fields | Ambiguous when key fields and document disagree |

The explicit Key keeps the API **uniform** (same pattern for all operations),
**safe** (compiler-enforced), and **portable** (no provider-specific field name
assumptions). Each provider maps the Key to its native key representation
internally — application code never needs to know which field name is used on
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
Cosmos DB and at the table level for DynamoDB — without any provider-specific
code.

### Provisioning Resources with `provisionSchema()`

When your application starts, it typically needs to ensure that all required
databases and containers/tables exist. The SDK provides `provisionSchema()` to
do this efficiently in a single call — with internal parallelism so that
application code does not need to manage threading.

```java
import java.util.*;

// Define the full schema: database name → list of collections
Map<String, List<String>> schema = new LinkedHashMap<>();
schema.put("admin-db",    List.of("tenants"));
schema.put("acme-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts"));
schema.put("shared-db",    List.of("market_data"));

// Provision everything — databases then containers, both phases in parallel
client.provisionSchema(schema);
```

**How it works internally:**

1. **Phase 1 — Databases**: All databases are created concurrently using a
   bounded thread pool (max 10 threads). The SDK waits for all database
   creations to complete before proceeding.
2. **Phase 2 — Containers**: All containers/tables are created concurrently
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
| `provisionSchema(schema)` | Provisioning multiple databases/containers at startup — the SDK handles all parallelism |
| `ensureDatabase(name)` | Creating a single database on demand (e.g., new tenant onboarding) |
| `ensureContainer(address)` | Creating a single container on demand |

Providers may override `provisionSchema()` with provider-specific optimisations,
but the default SPI implementation works correctly for all providers.

---

## CRUD Semantics

### create — Insert Only

`create()` inserts a new document. If a document with the same key already
exists, the operation **fails** with a conflict error. Use this when you need
insert-only semantics with duplicate detection.

```java
ResourceAddress addr = new ResourceAddress("mydb", "orders");
Key key = Key.of("customer-456", "order-123");
ObjectNode doc = mapper.createObjectNode();
doc.put("total", 99.95);
doc.put("status", "pending");

client.create(addr, key, doc);   // Fails if document already exists
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `createItem()` | `putItem()` with `attribute_not_exists` condition | `newInsertBuilder` mutation |
| If exists | Throws CONFLICT | Throws CONFLICT | Throws CONFLICT |
| If not exists | Create | Create | Create |
| Return value | None (void) | None (void) | None (void) |

### read — Point Read

`read()` performs a single-document read using the full key. Returns the document
as a `JsonNode` or `null` if not found.

```java
JsonNode order = client.read(addr, Key.of("customer-456", "order-123"));
if (order != null) {
    String status = order.path("status").asText();
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

### update — Replace Existing

`update()` replaces an existing document. If the document does **not** exist,
the operation **fails** with a not-found error. Use this when you need strict
update-only semantics.

```java
ObjectNode updated = mapper.createObjectNode();
updated.put("total", 109.95);
updated.put("status", "shipped");

client.update(addr, Key.of("customer-456", "order-123"), updated);   // Fails if not exists
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `replaceItem()` | `putItem()` with `attribute_exists` condition | `newUpdateBuilder` mutation |
| If exists | Replace | Replace | Replace |
| If not exists | Throws NOT_FOUND | Throws NOT_FOUND | Throws NOT_FOUND |
| Return value | None (void) | None (void) | None (void) |

### upsert — Create or Replace

`upsert()` creates a new document or **overwrites** an existing document with
the same key. It is an insert-or-update in all providers.

```java
ResourceAddress addr = new ResourceAddress("mydb", "orders");
Key key = Key.of("customer-456", "order-123");
ObjectNode doc = mapper.createObjectNode();
doc.put("total", 99.95);
doc.put("status", "pending");

client.upsert(addr, key, doc);   // Creates or replaces the document
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `upsertItem()` | `putItem()` (no condition) | `insertOrUpdate` mutation |
| If exists | Replace | Replace | Replace |
| If not exists | Create | Create | Create |
| Return value | None (void) | None (void) | None (void) |

### delete — Idempotent Delete

`delete()` removes a document by key. If the document doesn't exist, the
operation **succeeds silently** (no error). This makes delete idempotent and
safe for retry.

```java
client.delete(addr, Key.of("customer-456", "order-123"));
// Succeeds whether or not the document exists
```

**Key behavior across providers:**

| Behavior | Cosmos DB | DynamoDB | Spanner |
|----------|-----------|----------|---------|
| Operation | `deleteItem(id, partitionKey)` | `deleteItem(keyMap)` | `Mutation.delete(key)` |
| Not found | Silent success (HTTP 404 suppressed) | Silent success | Silent success |
| Return value | None (void) | None (void) | None (void) |

### Document Field Injection

When you call `create()`, `update()`, or `upsert()`, the SDK **injects key
fields into the document** automatically. You don't need to manually set `"id"`
or `"partitionKey"` in your JSON — the provider handles this:

```java
ObjectNode doc = mapper.createObjectNode();
doc.put("name", "Alpha Fund");
doc.put("type", "EQUITY");

// No need to set "id" or "partitionKey" in doc — injected by provider
client.upsert(addr, Key.of("acme", "port-1"), doc);
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

The SDK provides a **portable query expression language** — write a WHERE-clause
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
for (JsonNode item : page.items()) {
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
`Boolean`. Parameters are bound safely — no SQL injection risk.

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

Continuation tokens are **opaque strings** — their format differs by provider
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
// Cosmos DB — full SQL capability
QueryRequest cosmosQuery = QueryRequest.builder()
    .nativeExpression(
        "SELECT c.symbol, c.marketValue FROM c " +
        "WHERE c.portfolioId = @pid ORDER BY c.marketValue DESC"
    )
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(10)
    .build();

// DynamoDB — PartiQL syntax
QueryRequest dynamoQuery = QueryRequest.builder()
    .nativeExpression(
        "SELECT * FROM \"acme-risk-db__positions\" " +
        "WHERE begins_with(symbol, 'AA')"
    )
    .maxPageSize(10)
    .build();

// Spanner — GoogleSQL syntax
QueryRequest spannerQuery = QueryRequest.builder()
    .nativeExpression(
        "SELECT * FROM positions WHERE STARTS_WITH(symbol, 'AA') " +
        "ORDER BY marketValue DESC LIMIT 10"
    )
    .build();
```

> **Warning**: `expression` and `nativeExpression` are **mutually exclusive** —
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

This is fully transparent — your application code only provides the portable
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
// Key: Key.of(positionId, positionId) — each position in its own partition

// Query: fetch ALL positions, filter in Java
List<JsonNode> allPositions = client.query(addr,
    QueryRequest.builder().maxPageSize(200).build()
).items();

List<JsonNode> filtered = allPositions.stream()
    .filter(p -> "portfolio-alpha".equals(p.path("portfolioId").asText()))
    .toList();
```

**Problem**: Reads every position document, performs a cross-partition scan,
then discards most results. O(N) where N = total positions across all
portfolios.

#### Approach 2: Entity-per-partition + Expression Filter (Better)

```java
// Key: Key.of(positionId, positionId) — still entity-per-partition

// Query: let the database filter — only matching documents are returned
QueryRequest query = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(50)
    .build();

List<JsonNode> positions = client.query(addr, query).items();
```

**Better**: The database applies the filter — you only receive matching
documents. But this is still a cross-partition scan (the database still reads
all partitions, it just doesn't return non-matching documents).

#### Approach 3: Partition-per-parent + Expression Filter (Best)

```java
// Key: Key.of(portfolioId, positionId) — positions grouped by portfolio

// Query: the database knows to read only the "portfolio-alpha" partition
QueryRequest query = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameter("pid", "portfolio-alpha")
    .maxPageSize(50)
    .build();

List<JsonNode> positions = client.query(addr, query).items();
```

**Best**: By using `portfolioId` as the partition key (first argument to
`Key.of`), the database can scope the query to a single partition. Combined with
the expression filter, this is a **single-partition read** — the most efficient
pattern possible.

> **Note on DynamoDB**: When documents use `Key.of(portfolioId, positionId)`,
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
| **Cosmos DB** | Sets `CosmosQueryRequestOptions.setPartitionKey(new PartitionKey(value))` — query reads only that logical partition |
| **DynamoDB** | Adds a `partitionKey = :pk` equality condition to the scan filter — restricts results to items sharing the given hash key value |
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

This is far more efficient than a full cross-partition scan — it reads only
the documents belonging to `"portfolio-alpha"`.

#### Cross-Partition Query (Default)

When `partitionKey` is not set (or set to `null`), the query fans out across
all partitions — the same behavior as before:

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
    switch (e.error().category()) {
        case THROTTLED -> retryWithBackoff();
        case NOT_FOUND -> createResource();
        case CONFLICT -> resolveConflict();
        case AUTHENTICATION_FAILED -> refreshCredentials();
        default -> log.error("Unexpected error", e);
    }
}
```

See [compatibility.md](compatibility.md) for the full error category mapping
table across all providers.

---

## Result Set Control

The SDK supports portable `limit` (Top N) and `orderBy` on providers that declare the respective capabilities. Both are **capability-gated** — query silently against non-supporting providers without error, and the SDK enforces the limit client-side when the provider cannot.

### Checking Capabilities

```java
CapabilitySet caps = client.capabilities();
boolean canLimit  = caps.supports(Capability.RESULT_LIMIT);
boolean canOrder  = caps.supports(Capability.ORDER_BY);
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

`orderBy` and `limit` are silently discarded when `nativeExpression` is used — the native query string is passed through unmodified. Use provider-native syntax for full control:

```java
// Cosmos DB native — full SQL control
QueryRequest q = QueryRequest.builder()
        .nativeExpression(
            "SELECT TOP 10 c.symbol, c.marketValue FROM c " +
            "ORDER BY c.marketValue DESC")
        .build();
```

---

## Document TTL (Time-to-Live)

Set a per-document expiry at write time using `OperationOptions.ttlSeconds()`. Supported on `create()`, `upsert()`, and `update()`.

### Prerequisite: Enable Container-Level TTL

TTL is enforced at the provider level and requires the collection to be configured with TTL support before writing TTL-bearing documents.

| Provider | Required Setup |
|----------|----------------|
| Cosmos DB | Enable "Default Time to Live" on the container (set to `-1` for item-level control) |
| DynamoDB | Enable TTL on the table specifying `ttlExpiry` as the TTL attribute |
| Spanner | No native row-level TTL — `ROW_LEVEL_TTL=false`; `ttlSeconds` is silently ignored |

### Writing with TTL

```java
OperationOptions opts = OperationOptions.builder()
        .ttlSeconds(3_600)          // expire in 1 hour
        .build();

// TTL on create
client.create(address, key, doc, opts);

// TTL on upsert (create-or-replace)
client.upsert(address, key, doc, opts);

// TTL on update — carries TTL forward through the full-replace write
client.update(address, key, updatedDoc, opts);
```

### Checking TTL Support

```java
if (client.capabilities().supports(Capability.ROW_LEVEL_TTL)) {
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
if (client.capabilities().supports(Capability.WRITE_TIMESTAMP)) {
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

The SDK enforces a **399 KB** (408,576 bytes) maximum document size before any
data leaves the client. This limit applies to `create()`, `upsert()`, and
`update()` on all providers.

### Why 399 KB, not 400 KB?

Providers inject additional fields (`partitionKey`, `sortKey`, `id`, `ttlExpiry`) before writing. DynamoDB measures its 400 KB limit against the internal wire format, which can be larger than raw JSON bytes. The 1 KB safety margin prevents valid-looking documents from exceeding the wire limit after field injection.

### Validation Behaviour

```java
try {
    client.create(address, key, hugeDoc);
} catch (MulticloudDbException e) {
    if (e.error().category() == MulticloudDbErrorCategory.INVALID_REQUEST) {
        // Document rejected before reaching any provider
        System.out.println("Document too large: " + e.getMessage());
    }
}
```

Oversized documents are rejected **at the SDK layer** — no network call is made.
The error category is always `INVALID_REQUEST`.

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
| `requestId` | activityId | x-amzn-RequestId | — |
| `statusCode` | HTTP status | HTTP status | gRPC code |
| `requestCharge` | RU cost | consumed capacity | — |
| `etag` | `_etag` | — | commit timestamp |
| `sessionToken` | session token | — | — |
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
    .providerId(ProviderId.COSMOS)
    .property("endpoint", "https://my-account.documents.azure.com:443/")
    .authProperty("masterKey", "...")
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

- **Cosmos** — sent as the `User-Agent` HTTP header.
- **DynamoDB** — contributed via `ClientOverrideConfiguration#putAdvancedOption`
  with `SdkAdvancedClientOption.USER_AGENT_SUFFIX`. The AWS SDK appends this
  to its own `aws-sdk-java/...` token.
- **Spanner** — contributed via gax `FixedHeaderProvider` for the gRPC
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
// Throws IllegalArgumentException — contains newline
config.userAgentSuffix("my-app\nv1.0");

// Throws IllegalArgumentException — too long
config.userAgentSuffix("x".repeat(257));
```

If a `null` value is passed, the suffix is cleared and providers send only
the canonical SDK token.
