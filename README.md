# Multicloud DB SDK for Java

<img src="docs/images/multiclouddb-banner.png" alt="MultiCloudDB — Unified Data Access Layer for Best-of-Breed Cloud DBs" width="800"/>

> **⚠️ Public Preview Notice**
> This repository is currently available as a **public preview** and is **not yet fully ready for production use**.
> Expect breaking changes, incomplete features, and limited support during this phase.

A **portable database SDK** that lets you write create, read, upsert, delete, and
query logic once and run it against **Azure Cosmos DB**, **Amazon DynamoDB**, or
**Google Cloud Spanner** - switch providers by changing a single properties file.
Optional operations such as partial `update()` are capability-gated.

```
┌───────────────────────────────────────────────────┐
│                 Your Application                  │
│          (code against Multicloud DB API)         │
└────────────────────────┬──────────────────────────┘
                         │
            ┌────────────▼─────────────┐
            │    MulticloudDbClient    │   Portable contract
            │     (multiclouddb-api)   │   Base Ops · Query · Capabilities
            └────────────┬─────────────┘
                         │  ServiceLoader
            ┌────────────┼───────────────┐
            ▼            ▼               ▼
       ┌─────────┐ ┌──────────┐ ┌───────────┐
       │ Cosmos  │ │ DynamoDB │ │  Spanner  │
       │ Provider│ │ Provider │ │  Provider │
       └─────────┘ └──────────┘ └───────────┘
```

---

## Table of Contents

- [Why Multicloud DB?](#why-multicloud-db)
- [Quick Start](#quick-start)
- [Portable Query DSL](#portable-query-dsl)
- [Architecture](#architecture)
  - [Modules](#modules)
  - [API Surface](#api-surface)
  - [SPI (Provider Interface)](#spi-provider-interface)
  - [Provider Discovery](#provider-discovery)
- [Design Decisions](#design-decisions)
  - [Why MulticloudDbKey Is an Explicit Parameter](#why-multiclouddbkey-is-an-explicit-parameter)
- [Supported Providers](#supported-providers)
- [Configuration](#configuration)
- [Capabilities & Portability](#capabilities--portability)
- [Partial Updates](#partial-updates)
- [Result Set Control](#result-set-control)
- [Document TTL](#document-ttl)
- [Document Metadata](#document-metadata)
- [Document Size Enforcement](#document-size-enforcement)
- [Provider Diagnostics](#provider-diagnostics)
- [Sample Applications](#sample-applications)
- [Building from Source](#building-from-source)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Documentation](#documentation)
- [License](#license)

---

## Why Multicloud DB?

| Problem | Multicloud DB Solution |
|---------|------------------|
| Vendor lock-in - each cloud DB has its own SDK, data model, and query language | Single `MulticloudDbClient` interface with portable point operations (create/read/upsert/delete) + query and explicit capability gates |
| Each provider has a different query language (Cosmos SQL, PartiQL, GoogleSQL) | **Portable query DSL** - write `status = @status AND priority > @min`, auto-translated per provider |
| Migrating between providers requires rewriting data-access code | Change **one property** (`multiclouddb.provider=dynamo` → `cosmos`) |
| Understanding which features are portable vs. provider-specific | Runtime `CapabilitySet` introspection and structured `UNSUPPORTED_CAPABILITY` errors |
| Testing across providers | Shared conformance tests verify the common contract and capability-gated behavior |

---

## Quick Start

### 1. Build

```bash
# Requires JDK 17+
mvn clean install -DskipTests
```

### 2. Add dependencies

```xml
<!-- Portable API (compile scope) -->
<dependency>
    <groupId>com.microsoft.multiclouddb</groupId>
    <artifactId>multiclouddb-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- Pick one or more providers (runtime scope - swap without recompiling) -->
<dependency>
    <groupId>com.microsoft.multiclouddb</groupId>
    <artifactId>multiclouddb-provider-cosmos</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>runtime</scope>
</dependency>

<!-- Additional providers can be included in the same project.
     Each is discovered via ServiceLoader and selected by ProviderId at runtime.
     Include as many as your application needs: -->
<!--
<dependency>
    <groupId>com.microsoft.multiclouddb</groupId>
    <artifactId>multiclouddb-provider-dynamo</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.microsoft.multiclouddb</groupId>
    <artifactId>multiclouddb-provider-spanner</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>runtime</scope>
</dependency>
-->
```

### 3. Write portable code

```java
import com.multiclouddb.api.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

// Configure - provider selected entirely by config, not code
Properties props = new Properties();
props.load(getClass().getResourceAsStream("/todo-app-cosmos.properties"));

String providerName = props.getProperty("multiclouddb.provider");   // "cosmos", "dynamo", etc.
ProviderId provider = ProviderId.fromId(providerName);

Map<String, String> connection = new HashMap<>();
Map<String, String> auth = new HashMap<>();
for (String name : props.stringPropertyNames()) {
    if (name.startsWith("multiclouddb.connection.")) {
        connection.put(name.substring("multiclouddb.connection.".length()),
                props.getProperty(name));
    } else if (name.startsWith("multiclouddb.auth.")) {
        auth.put(name.substring("multiclouddb.auth.".length()),
                props.getProperty(name));
    }
}

MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
        .provider(provider)
        .connection(connection)
        .auth(auth)
        .build();

// Create client via ServiceLoader discovery
MulticloudDbClient client = MulticloudDbClientFactory.create(config);

// Portable point operations - same code for every provider
Map<String, Object> doc = Map.of(
        "title", "Buy groceries",
        "completed", false,
        "status", "active",
        "category", "shopping");

ResourceAddress todos = new ResourceAddress("mydb", "todos");
MulticloudDbKey key = MulticloudDbKey.of("todo-1", "todo-1");

client.upsert(todos, key, doc);                  // Create or replace (upsert)
DocumentResult result = client.read(todos, key); // Point read → returns DocumentResult
ObjectNode document = result.document();         // The document payload

// Query with portable expressions - automatically translated per provider
QueryRequest query = QueryRequest.builder()
        .expression("status = @status AND category = @cat")
        .parameters(Map.of("status", "active", "cat", "shopping"))
        .maxPageSize(25)
        .build();
QueryPage page = client.query(todos, query);
for (Map<String, Object> item : page.items()) {
    System.out.println(item);
}
client.delete(todos, key);                       // Cleanup after query
// Cosmos → SELECT * FROM c WHERE (c.status = @status AND c.category = @cat)
// DynamoDB → SELECT * FROM "todos" WHERE (status = ? AND category = ?)
// Spanner → SELECT * FROM `todos` WHERE (status = @status AND category = @cat)
```

### 4. Native query expression (non-portable)

When you need provider-specific query syntax, use `nativeExpression()`:

```java
// Cosmos SQL (only works with Cosmos provider)
QueryRequest cosmosQuery = QueryRequest.builder()
        .nativeExpression("SELECT * FROM c WHERE c.title LIKE '%flight%'")
        .maxPageSize(25)
        .build();

// DynamoDB PartiQL (only works with DynamoDB provider)
QueryRequest dynamoQuery = QueryRequest.builder()
        .nativeExpression("SELECT * FROM \"todos\" WHERE begins_with(title, 'Ship')")
        .maxPageSize(25)
        .build();

// Spanner GoogleSQL (only works with Spanner provider)
QueryRequest spannerQuery = QueryRequest.builder()
        .nativeExpression("SELECT * FROM todos WHERE STARTS_WITH(title, 'Ship')")
        .maxPageSize(25)
        .build();
```

### 5. Switch providers

Change **only** the properties file - no code changes:

```properties
# Cosmos DB
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://localhost:8081
multiclouddb.connection.key=...

# --- OR ---

# DynamoDB
multiclouddb.provider=dynamo
multiclouddb.connection.endpoint=http://localhost:8000
multiclouddb.connection.region=us-east-1
multiclouddb.auth.accessKeyId=fakeMyKeyId
multiclouddb.auth.secretAccessKey=fakeSecretAccessKey

# --- OR ---

# Google Cloud Spanner
multiclouddb.provider=spanner
multiclouddb.connection.projectId=my-gcp-project
multiclouddb.connection.instanceId=my-instance
multiclouddb.connection.databaseId=my-database
# multiclouddb.connection.emulatorHost=localhost:9010   # Optional - for emulator
```

---

## Portable Query DSL

Multicloud DB includes a **portable query expression language** that lets you write WHERE-clause filters once and have them automatically translated to each provider's native query language.

### Expression Syntax

```
<field> <op> @<param>              Comparison (=, !=, <, <=, >, >=)
<expr> AND <expr>                  Logical AND
<expr> OR <expr>                   Logical OR
NOT <expr>                         Logical NOT
<field> BETWEEN @low AND @high     Range check
<field> IN (@a, @b, @c)            Set membership
starts_with(<field>, @param)       String prefix
contains(<field>, @param)          Substring search
field_exists(<field>)              Field existence check
string_length(<field>) > @n        String length
collection_size(<field>) > @n      Array/collection size
```

Parameters use `@name` syntax and are passed as a `Map<String, Object>`. Expressions support arbitrary nesting with parentheses.

### Translation Examples

| Portable Expression | Cosmos DB SQL | DynamoDB PartiQL | Spanner GoogleSQL |
|---|---|---|---|
| `status = @status` | `c.status = @status` | `status = ?` | `status = @status` |
| `starts_with(title, @prefix)` | `STARTSWITH(c.title, @prefix)` | `begins_with(title, ?)` | `STARTS_WITH(title, @prefix)` |
| `priority > @min AND category = @cat` | `(c.priority > @min AND c.category = @cat)` | `(priority > ? AND category = ?)` | `(priority > @min AND category = @cat)` |
| `field BETWEEN @lo AND @hi` | `c.field BETWEEN @lo AND @hi` | `field BETWEEN ? AND ?` | `field BETWEEN @lo AND @hi` |
| `tag IN (@a, @b, @c)` | `c.tag IN (@a, @b, @c)` | `tag IN (?, ?, ?)` | `tag IN (@a, @b, @c)` |

### Pipeline

When you call `client.query()` with a portable expression:

1. **Parse** - `ExpressionParser` converts the string to a typed AST
2. **Validate** - `ExpressionValidator` checks parameter bindings and function signatures
3. **Translate** - Provider-specific `ExpressionTranslator` generates native query syntax
4. **Execute** - Provider runs the translated query against the database

This is fully transparent - you never see the translated SQL. For direct control, use `nativeExpression()` to bypass the pipeline entirely.

---

## Architecture

### Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| **multiclouddb-api** | `com.microsoft.multiclouddb:multiclouddb-api` | Portable client interface, types, error model, factory, and SPI contracts. The only compile-time dependency your app needs. |
| **multiclouddb-provider-cosmos** | `com.microsoft.multiclouddb:multiclouddb-provider-cosmos` | Azure Cosmos DB adapter (Java SDK v4) |
| **multiclouddb-provider-dynamo** | `com.microsoft.multiclouddb:multiclouddb-provider-dynamo` | Amazon DynamoDB adapter (AWS SDK v2) |
| **multiclouddb-provider-spanner** | `com.microsoft.multiclouddb:multiclouddb-provider-spanner` | Google Cloud Spanner adapter (Google Cloud Spanner 6.62.0) |
| **multiclouddb-conformance** | `com.microsoft.multiclouddb:multiclouddb-conformance` | Cross-provider integration tests |

Samples: see the [separate samples repo](https://github.com/microsoft/multiclouddb-sdk-for-java-samples).

### API Surface

All application code depends on `multiclouddb-api`. The core types are:

| Type | Purpose |
|------|---------|
| `MulticloudDbClient` | Portable interface: `create`, `read`, `delete`, full-replacement `upsert`, capability-gated shallow partial `update`, query, provisioning, and capabilities |
| `MulticloudDbClientFactory` | Creates a `MulticloudDbClient` by discovering providers via `ServiceLoader` |
| `MulticloudDbClientConfig` | Builder-pattern config: provider selection, connection, auth, feature flags |
| `ResourceAddress` | `(database, collection)` pair targeting a container/table |
| `MulticloudDbKey` | `(partitionKey, sortKey)` pair - every document needs at least a partition key |
| `QueryRequest` | Portable expression, native expression, parameters, page size, continuation token, partition key scoping, `limit`, `orderBy` |
| `QueryPage` | Result page: items + optional continuation token + optional `OperationDiagnostics` |
| `SortOrder` | `(field, direction)` sort specification for `orderBy` — validates field names against injection |
| `SortDirection` | `ASC` or `DESC` |
| `DocumentResult` | Result of `read()`: document payload + optional `DocumentMetadata` |
| `DocumentMetadata` | Provider-available nullable metadata: `lastModified`, `ttlExpiry`, `version` |
| `CapabilitySet` | Runtime introspection of provider capabilities |
| `Capability` | Named capability with `supported` flag and notes |
| `MulticloudDbException` | Structured error with `MulticloudDbError` (category, provider, native code) |
| `OperationOptions` | Timeout, create/upsert-only TTL (`ttlSeconds`), metadata flag (`includeMetadata`) |
| `OperationDiagnostics` | Latency, request units/charge, request ID, ETag, item count |
| `Expression` | AST node interface for parsed query expressions |
| `ExpressionParser` | Parses portable expression strings into an AST |
| `ExpressionValidator` | Validates parameter bindings and function usage |
| `ExpressionTranslator` | SPI - translates AST to provider-native query syntax |
| `TranslatedQuery` | Result of translation: query string + bound parameters |

### SPI (Provider Interface)

Provider modules implement two SPI contracts without importing each other:

| SPI Interface | Responsibility |
|---------------|---------------|
| `MulticloudDbProviderAdapter` | Factory - creates a `MulticloudDbProviderClient` from config; registered via `META-INF/services` |
| `MulticloudDbProviderClient` | Key-based create/read/upsert/delete, capability-gated partial update, query, provisioning, and capabilities - called by `DefaultMulticloudDbClient` |

### Provider Discovery

Providers are discovered at runtime via Java's `ServiceLoader`:

1. Your app calls `MulticloudDbClientFactory.create(config)`
2. The factory scans `META-INF/services/com.multiclouddb.spi.MulticloudDbProviderAdapter`
3. The matching adapter's `createClient()` builds a native SDK client
4. A `DefaultMulticloudDbClient` wraps it with error mapping, diagnostics, and the portable contract

**No provider imports in application code.** Just drop the provider JAR on the classpath (or add it as a `<scope>runtime</scope>` Maven dependency).

---

## Design Decisions

### Why MulticloudDbKey Is an Explicit Parameter

You may notice that every document operation requires an explicit
`MulticloudDbKey` parameter,
even on writes where the key material could theoretically be extracted from the
document:

```java
// MulticloudDbKey is always explicit - never extracted from the document
client.upsert(addr, MulticloudDbKey.of("tenant-1", "pos-42"), doc);
```

Some database SDKs (notably the Azure Cosmos DB SDK) extract the partition key
and ID from the document body automatically. Multicloud DB deliberately does **not**
do this, for several reasons:

1. **Each provider maps key fields differently.** Cosmos DB stores
   `MulticloudDbKey.sortKey()` as the built-in `id` field, while DynamoDB and
   Spanner store it as a `sortKey` attribute/column. A convention-based
   extractor would need provider-specific logic, undermining portability.

2. **`read()` and `delete()` have no document.** These operations require a
   `MulticloudDbKey` with nothing to extract from. Making writes work
   differently would create an inconsistent API.

3. **The key is always authoritative.** Complete writes reject caller-supplied
   provider-owned top-level names (`id`, `partitionKey`, `sortKey`, `ttl`,
   `ttlExpiry`, `data`, or any name beginning with `_`) before I/O, then
   providers derive their native key fields from `MulticloudDbKey` (see
   [Document Field Injection](docs/guide.md#document-field-injection) in the
   developer guide). This prevents accidental mismatches.

4. **Compile-time safety.** A missing `MulticloudDbKey` is a compiler error. A
   missing field in a JSON document is a runtime error deep in the provider
   layer.

See the [developer guide](docs/guide.md#why-multiclouddbkey-is-an-explicit-parameter) for the full rationale and per-provider field mapping details.

---

## Supported Providers

| Provider | Module | Status | Native SDK |
|----------|--------|--------|------------|
| **Azure Cosmos DB** | `multiclouddb-provider-cosmos` | Full | Azure Cosmos Java SDK 4.60.0 |
| **Amazon DynamoDB** | `multiclouddb-provider-dynamo` | Full | AWS SDK for Java 2.25.16 |
| **Google Cloud Spanner** | `multiclouddb-provider-spanner` | Full | Google Cloud Spanner 6.62.0 |

---

## Configuration

All configuration flows through `MulticloudDbClientConfig` or a `.properties` file:

| Property | Description | Example |
|----------|-------------|---------|
| `multiclouddb.provider` | Provider ID | `cosmos`, `dynamo`, `spanner` |
| `multiclouddb.connection.*` | Connection properties | `endpoint`, `key`, `region`, `connectionMode` |
| `multiclouddb.auth.*` | Authentication properties | `accessKeyId`, `secretAccessKey` |
| `multiclouddb.feature.*` | Feature flags | Provider-specific opt-ins |

### Cosmos DB connection properties

| Key | Value |
|-----|-------|
| `multiclouddb.connection.endpoint` | `https://localhost:8081` (emulator) or your Cosmos account URI |
| `multiclouddb.connection.key` | Master key or Cosmos emulator well-known key |
| `multiclouddb.connection.connectionMode` | `gateway` or `direct` |

### DynamoDB connection properties

| Key | Value |
|-----|-------|
| `multiclouddb.connection.endpoint` | `http://localhost:8000` (DynamoDB Local) or omit for AWS |
| `multiclouddb.connection.region` | AWS region, e.g. `us-east-1` |
| `multiclouddb.auth.accessKeyId` | AWS access key (or any string for DynamoDB Local) |
| `multiclouddb.auth.secretAccessKey` | AWS secret key (or any string for DynamoDB Local) |

### Spanner connection properties

| Key | Value |
|-----|-------|
| `multiclouddb.connection.projectId` | GCP project ID |
| `multiclouddb.connection.instanceId` | Spanner instance ID |
| `multiclouddb.connection.databaseId` | Spanner database ID |
| `multiclouddb.connection.emulatorHost` | `localhost:9010` (Spanner Emulator) or omit for GCP |

---

## Resource Provisioning

The SDK provides a single method to provision a provider-compatible schema of
databases and containers/tables. Parallelism is handled internally - the SDK
creates all addressed databases concurrently, waits for completion, then creates
all containers concurrently. Application code does not need to manage threading.

```java
// Define your schema: database name → list of collection/table names
Map<String, List<String>> schema = Map.of(
    "app-db", List.of("tenants", "portfolios", "positions", "risk_metrics")
);

// Single call - SDK handles parallel creation internally
client.provisionSchema(schema);
```

| Provider | Database Phase | Container/Table Phase |
|----------|---------------|----------------------|
| **Cosmos DB** | Uses data-plane `createDatabaseIfNotExists` in all environments; the caller needs database-creation permission | Creates containers in parallel via the data-plane SDK |
| **DynamoDB** | No-op (DynamoDB has no native database concept) | Creates tables in parallel, waits for ACTIVE status |
| **Spanner** | Creates the configured database; emulator mode also creates the configured instance if absent, while production requires the instance to pre-exist | Creates tables in parallel |

For a schema definition portable to Spanner, use the single database name
configured as `databaseId`. A Spanner client rejects other database names;
Cosmos DB and DynamoDB mappings can represent multiple logical database entries.

You can also call `ensureDatabase()` and `ensureContainer()` individually if
you need fine-grained control, but `provisionSchema()` is the recommended
approach for provisioning multiple standard-schema resources. These methods are
startup/development conveniences, not a replacement for infrastructure as code
when production provisioning needs custom throughput, indexing, regions, or
other advanced controls.

---

## Capabilities & Portability

Each provider declares which cross-cutting features it supports. Query at runtime:

```java
CapabilitySet caps = client.capabilities();

if (caps.isSupported(Capability.TRANSACTIONS)) {
    // safe to use transactions
}

for (Capability cap : caps.all()) {
    System.out.printf("%-30s %s %s%n",
        cap.name(),
        cap.supported() ? "✓" : "✗",
        cap.notes() != null ? cap.notes() : "");
}
```

| Capability | Cosmos DB | DynamoDB | Spanner |
|------------|:---------:|:--------:|:-------:|
| **Portable query DSL** | ✓ | ✓ | ✓ |
| Native expression passthrough | ✓ (SQL) | ✓ (PartiQL) | ✓ (GoogleSQL) |
| Continuation token paging | ✓ | ✓ | ✓ |
| Cross-partition query | ✓ | ✗ | ✓ |
| Transactions | ✓ | ✓ | ✓ |
| Batch operations | ✓ | ✓ | ✓ |
| Strong consistency | ✓ | ✓ | ✓ |
| Change feed | ✓ | ✓ | ✓ |
| **Result limit** (`Top N`) | ✓ | ✓ (per-page) | ✓ |
| **ORDER BY** | ✓ | ✗ | ✓ |
| **Row-level TTL** | ✓ | ✓ | ✗ |
| **Write timestamp (`lastModified`)** | ✓ | ✗ | ✗ |
| **Partial update** | ✓ | ✓ | ✗ (not in this release) |
| **Case-sensitive partial-update fields** | ✓ | ✓ | — |

---

## Partial Updates

> **Breaking change (pre-1.0 beta):** `update()` previously meant complete
> replacement. It now means shallow partial update. Callers, including code
> already compiled against an earlier beta, must migrate the payload and its
> expectations before running with this release. Use `upsert()` for an
> unguarded complete replacement; there is no exact portable atomic
> full-document replace-if-present equivalent.

`update()` sets or replaces only supplied top-level fields and preserves omitted
fields. It returns `NOT_FOUND` without creating a missing item. Map/list values
replace their complete top-level value, and Java `null` stores null. Values must be serializable with the SDK-owned Jackson configuration used by
shared preflight. Caller-registered modules are not consulted, so values requiring
custom modules, such as `Instant`, must first be converted to serializable values.

```java
if (client.capabilities().isSupported(Capability.PARTIAL_UPDATE)) {
    client.update(address, key, Map.of("status", "shipped"));
} else {
    // Skip this optional operation or select a non-update workflow.
}
```

On providers that advertise `PARTIAL_UPDATE`, the normalized contract is the
same: at most 10 fields, shallow top-level set/replace semantics, preservation
of omitted fields, one atomic native write, and `NOT_FOUND` for a missing item.
The conservative 10-field initial-release limit guarantees one atomic native
write on both supported providers. This release does not define a wider-update
or multi-patch transaction path.

Non-reserved field names are literal and case-sensitive: `foo` and `Foo` are
separate fields, including when both appear in one atomic update. Names matching
`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`
case-insensitively, and names beginning with `_`, are reserved and fail shared
preflight before provider I/O.

"Top-level" describes the updated field path, not the shape of its replacement
value. Each replacement value may contain at most 31 nested map/list containers,
counting its top-level container as level 1. Every field name, including nested map
keys, must be at most 50,000 UTF-8 bytes, and binary values are rejected. Cyclic graphs and non-collection iterables are also invalid. The
incoming field map must also fit a 390 KiB structural footprint that includes UTF-8
field names and native map/list overhead, independently of its serialized JSON
size. Shared violations perform
zero provider I/O and return non-retryable `INVALID_REQUEST` with
`reason=partial_update_field_name_size_limit`, `reason=non_portable_binary_value`,
`reason=non_portable_iterable`,
`reason=partial_update_value_cycle`, `reason=partial_update_nesting_depth_limit`, or
`reason=partial_update_structural_footprint_limit` and the applicable actual and
maximum limit details.

The `PARTIAL_UPDATE` portable contract applies only when both the resulting
logical document's serialized JSON and its portable structural footprint are at
or below 390 KiB. A state-dependent result above either bound is outside this
release's portable contract and may succeed or fail under the selected
provider's native limits. The SDK performs no read/merge preflight. A native
result-size rejection remains non-retryable `UNSUPPORTED_CAPABILITY`, carries a
stable provider-specific reason and limit details, and follows at most one
attempted atomic write.

TTL timing is also outside this release's portable partial-update contract.
DynamoDB `UpdateItem` happens to leave `ttlExpiry` unchanged, while Cosmos DB
`patchItem` advances `_ts` and restarts relative TTL. Until TTL behavior is
normalized, callers that require a fixed absolute expiry must not call
`update()` on TTL-bearing items.

Follow-up normalization is tracked in [#113](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/113) for absolute TTL expiry and [#114](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/114) for state-dependent resulting size.

Spanner does not advertise `PARTIAL_UPDATE`. `CapabilitySet` treats an omitted
declaration as unsupported by default, so this API release remains compatible
with older Spanner provider versions. A valid call fails safely at the shared
capability gate with non-retryable `UNSUPPORTED_CAPABILITY` before provider
delegation. This is a deliberate release scope: Spanner emulator validation
covers shared preflight, capability rejection, and the provider-direct legacy
regression, but this release does not claim live production Spanner validation.
Each built-in provider exposes 18 effective capability rows: Cosmos DB and
DynamoDB explicitly declare all 18, while Spanner declares 17 and
`CapabilitySet` supplies only the omitted core `PARTIAL_UPDATE` unsupported
default. Unrelated omitted capability names remain absent.

Provider-native resulting-item ceilings remain constraints. Any resulting-item
failure is atomic and surfaces as non-retryable
`UNSUPPORTED_CAPABILITY` with a stable provider-specific `reason`; the SDK does
not add a read-before-write size check that would introduce cost and a race.

For complete replacement, use `upsert()` with the complete document; it creates
a missing item. Read-then-upsert is not atomic, and this release has no exact
portable atomic full-document replace-if-present equivalent. `ttlSeconds` is
invalid on `update()` and fails before provider I/O with `INVALID_REQUEST`.

---

## Result Set Control

Limit and sort results portably across providers:

```java
QueryRequest q = QueryRequest.builder()
        .expression("status = @s")
        .parameter("s", "active")
        .limit(25)                                    // top 25 results
        .orderBy("createdAt", SortDirection.DESC)     // newest first
        .build();

QueryPage page = client.query(address, q);
```

Check capabilities before using `ORDER BY` — DynamoDB does not support server-side ordering:

```java
if (client.capabilities().isSupported(Capability.ORDER_BY)) {
    // use orderBy()
}
```

---

## Document TTL

Set a per-document TTL at write time using `OperationOptions`:

```java
OperationOptions opts = OperationOptions.builder()
        .ttlSeconds(3_600)      // expire in 1 hour
        .build();

client.create(address, key, doc, opts);
client.upsert(address, key, doc, opts);
```

TTL requires collection-level configuration first (enable "Default TTL" on the
Cosmos DB container; enable TTL on the DynamoDB table using `ttlExpiry` as the
attribute name). Spanner ignores TTL on create/upsert
(`ROW_LEVEL_TTL=false`). `update()` rejects non-null `ttlSeconds` on every
provider. Callers that require expiry must inspect `ROW_LEVEL_TTL` before
writing.

---

## Document Metadata

Read write-metadata (last-modified timestamp, TTL expiry, version/ETag) on demand.
The snippets use a class-level SLF4J logger such as
`private static final Logger LOG = LoggerFactory.getLogger(YourApplication.class)`:

```java
OperationOptions opts = OperationOptions.builder()
        .includeMetadata(true)
        .build();

DocumentResult result = client.read(address, key, opts);
DocumentMetadata meta = result.metadata();   // non-null because metadata was requested

if (meta != null) {
    // Each field is independently nullable.
    if (meta.lastModified() != null) {
        LOG.info("Last modified: {}", meta.lastModified());
    }
    if (meta.ttlExpiry() != null) {
        LOG.info("Expires at: {}", meta.ttlExpiry());
    }
    if (meta.version() != null) {
        LOG.info("ETag/version: {}", meta.version());
    }
}
```

| Metadata field | Cosmos DB | DynamoDB | Spanner |
|----------------|:---------:|:--------:|:-------:|
| `lastModified` | ✓ (`_ts`) | ✗ | ✗ |
| `ttlExpiry` | ✗ | ✓ | ✗ |
| `version` | ✓ (ETag) | ✗ | ✗ |

`metadata()` is `null` only when metadata was not requested. With
`includeMetadata(true)`, the current providers return a metadata envelope and
callers inspect each nullable field independently; Spanner's envelope is
currently empty. `Capability.WRITE_TIMESTAMP` indicates whether
`lastModified` may be populated (Cosmos DB only). It does not gate the metadata
envelope or DynamoDB's independent `ttlExpiry` field.

---

## Document Size Enforcement

The SDK validates **independent 390 KiB serialized and structural input bounds**
before any network call. Shared preflight snapshots the top-level map and uses
bounded SDK-owned Jackson serialization while inspecting nested values. Binary
values are rejected even when hidden in a POJO; cyclic graphs and non-collection
iterables are invalid; and serialized JSON output is capped while it is produced.
Every field name is limited to 50,000 UTF-8 bytes.

For `create()` and `upsert()`, the input is the complete document. A null
document, a top-level name matching `id`, `partitionKey`, `sortKey`, `ttl`,
`ttlExpiry`, or `data` case-insensitively, or any top-level name beginning with
`_` returns non-retryable `INVALID_REQUEST` before provider I/O. Other
case-distinct names remain separate literal fields. For `update()`, the input is
only the supplied field map. Inputs that exceed either limit are rejected with
`MulticloudDbErrorCategory.INVALID_REQUEST`:

```java
try {
    client.create(address, key, largeDoc);
} catch (MulticloudDbException e) {
    if (e.error().category() == MulticloudDbErrorCategory.INVALID_REQUEST) {
        LOG.warn("Write input exceeds a portable validation limit: {}",
                e.error().message());
    }
}
```

For `create()`/`upsert()`, the structural bound covers the complete document,
which may contain at most 31 map/list containers below its root. For `update()`,
it covers the incoming field map and also enforces the 31-level
replacement-value nesting limit described above.
These checks inspect only incoming fields; they do not preflight the resulting
stored item, which remains subject to provider-native ceilings after one attempted
atomic write.

Results above either 390 KiB portable result bound are outside this release's
portable contract and remain subject to the selected provider's native limit.
These limits are enforced internally rather than exposed as compile-time Java
constants. Applications should handle `INVALID_REQUEST` and its structured limit
details instead of compiling against copied values. Runtime limit discovery and
configuration are tracked in [#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116).

The portable size limits are rounded down to 390 KiB to leave headroom for
provider-injected fields and native wire-format overhead — see
[Developer Guide](docs/guide.md#document-size-enforcement) for details.

---

## Provider Diagnostics

`QueryPage` carries `OperationDiagnostics` with latency, request charge, and
provider correlation IDs:

```java
QueryPage page = client.query(address, q);
OperationDiagnostics diag = page.diagnostics();
if (diag != null) {
    System.out.printf("%s %s latency=%dms ruCharge=%.2f%n",
        diag.provider().id(), diag.operation(),
        diag.duration().toMillis(), diag.requestCharge());
}
```

---

## Sample Applications

Sample applications are maintained in a **separate repository**:

**[microsoft/multiclouddb-sdk-for-java-samples](https://github.com/microsoft/multiclouddb-sdk-for-java-samples)**

| Sample | Description | Port | Guide |
|--------|-------------|------|-------|
| **Portable Point Operations + Query** | Minimal create/read/upsert/delete/query sample; partial update is capability-gated | — | [README](https://github.com/microsoft/multiclouddb-sdk-for-java-samples#portable-crud--query-sample) |
| **TODO App** | Key-based document web app; completion updates require `PARTIAL_UPDATE` | `8080` | [README-todo-app.md](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-todo-app.md) |
| **Risk Analysis Platform** | Multi-tenant portfolio risk analytics with executive dashboard | `8090` | [README-risk-platform.md](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-risk-platform.md) |

### Quick Start

```bash
git clone https://github.com/microsoft/multiclouddb-sdk-for-java-samples.git
cd multiclouddb-sdk-for-java-samples
mvn clean install -DskipTests
```

### TODO App

```
┌─────────────────────────────────┐
│  Browser UI (localhost:8080)    │
│  Create · Read · Delete         │
│  Update when capability permits │
└──────────────┬──────────────────┘
               │ REST API
┌──────────────▼──────────────────┐
│  Embedded Java HttpServer       │
│  TodoApp.java                   │
└──────────────┬──────────────────┘
               │ MulticloudDbClient
     ┌─────────┼──────────┐
     ▼         ▼          ▼
 Cosmos DB  DynamoDB   Spanner
 Emulator    Local     Emulator
```

```powershell
# Cosmos DB
mvn exec:java `
  "-Dexec.mainClass=com.multiclouddb.samples.todo.TodoApp" `
  "-Dtodo.config=todo-app-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit"

# DynamoDB
mvn exec:java `
  "-Dexec.mainClass=com.multiclouddb.samples.todo.TodoApp" `
  "-Dtodo.config=todo-app-dynamo.properties"
```

Then open **http://localhost:8080** in your browser.

The TODO app's base create/read/upsert/delete/query paths can target all three
providers. Before toggling completion, callers must check
`Capability.PARTIAL_UPDATE`: Cosmos DB and DynamoDB support it; the current
Spanner provider does not and a valid `update()` is rejected before provider
I/O.

### Risk Analysis Platform

A multi-tenant SaaS application with database-per-tenant isolation,
portfolio risk analytics, and an executive dashboard. Demonstrates:

- **Database-per-tenant isolation** via `ResourceAddress` routing
- **Partition-scoped queries** via `QueryRequest.partitionKey()` for efficient
  within-partition reads (e.g., positions within a portfolio)
- **Auto-provisioning** of databases/containers/tables on startup via `provisionSchema()`
- **Provider portability** - switch between Cosmos DB and DynamoDB with zero
  code changes

```powershell
# Cosmos DB (port 8090)
mvn exec:java `
  "-Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit"

# DynamoDB (port 8090)
mvn exec:java `
  "-Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-dynamo.properties"
```

Then open **http://localhost:8090** in your browser.

For full setup instructions, see the [Risk Platform guide](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-risk-platform.md).

For full emulator setup instructions, see the [samples README](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README.md).

---

## Building from Source

```bash
# Full build (compile + test + package)
mvn clean verify

# Skip tests for faster iteration
mvn clean install -DskipTests

# Build a single module
mvn -pl multiclouddb-provider-dynamo clean install
```

> **Note**: JDK 17+ is required. Set `JAVA_HOME` accordingly:
> ```powershell
> $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
> $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
> ```

---

## Testing

### Unit tests

```bash
mvn test
```

Runs the current unit suites across the API and provider modules, including the
portable query expression parser, validator, translator, write preflight, and
provider adapters. Test totals are intentionally not hard-coded because they
change as conformance coverage grows.

### Integration / conformance tests

```bash
# Requires Cosmos DB emulator on localhost:8081, DynamoDB Local on localhost:8000,
# and Spanner Emulator on localhost:9010
mvn -pl multiclouddb-conformance verify
```

The conformance suite runs shared create/read/upsert/delete and portable-query
tests against each provider emulator. Partial-update behavior runs only for
providers advertising `PARTIAL_UPDATE`; all providers still exercise shared
invalid-request validation and unsupported-capability behavior as applicable.

---

## Project Structure

```
multiclouddb-sdk-java/
├── pom.xml                          # Parent POM (aggregator)
├── multiclouddb-api/                    # Portable API + SPI contracts
│   └── src/main/java/com/multiclouddb/
│       ├── api/                     # Public types (MulticloudDbClient, MulticloudDbKey, etc.)
│       │   ├── internal/            # DefaultMulticloudDbClient
│       │   └── query/               # Portable expression AST, parser, validator, translator SPI
│       └── spi/                     # Provider SPI interfaces
├── multiclouddb-provider-cosmos/        # Azure Cosmos DB adapter
│   └── src/main/
│       ├── java/.../cosmos/         # CosmosProviderClient, error mapper, capabilities
│       └── resources/META-INF/services/  # ServiceLoader registration
├── multiclouddb-provider-dynamo/        # Amazon DynamoDB adapter
│   └── src/main/
│       ├── java/.../dynamo/         # DynamoProviderClient, item mapper, error mapper
│       └── resources/META-INF/services/
├── multiclouddb-provider-spanner/       # Google Cloud Spanner adapter
├── multiclouddb-conformance/            # Cross-provider integration test suite
└── specs/                               # Design documents

# Sample applications are in a separate repo:
# https://github.com/microsoft/multiclouddb-sdk-for-java-samples
```

---

## Prerequisites

| Tool | Version | Required For |
|------|---------|-------------|
| JDK | 17+ | Build and run |
| Maven | 3.9+ | Build |
| Azure Cosmos DB Emulator | Latest | Cosmos integration tests |
| DynamoDB Local | Latest | DynamoDB integration tests |
| Docker | 20+ | Spanner Emulator (`gcr.io/cloud-spanner-emulator/emulator`) |
| Node.js + npm | 18+ | `dynamodb-admin` GUI (optional) |

---

## Documentation

| Document | Description |
|----------|-------------|
| [Developer Guide](docs/guide.md) | Comprehensive reference - partition keys, CRUD semantics, query DSL, multi-tenant patterns |
| [Provider Compatibility](docs/compatibility.md) | Capability matrix, error mapping, native-query guidance, no-native-client policy, async guidance |

---

## Contributing

We welcome contributions! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for
guidelines on reporting issues, requesting features, setting up a development
environment, and submitting pull requests.

---

## Code of Conduct

This project has adopted the
[Microsoft Open Source Code of Conduct](https://opensource.microsoft.com/codeofconduct/).
See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for details.

---

## CLA

Contributions require a signed
[Microsoft Contributor License Agreement](https://cla.opensource.microsoft.com).
The CLA bot will guide you through the process when you open a pull request.
See [CLA.md](CLA.md) for more information.

---

## Security

Please see [SECURITY.md](SECURITY.md) for reporting security vulnerabilities.

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE)
file for details.

Copyright © Microsoft Corporation. All rights reserved.
