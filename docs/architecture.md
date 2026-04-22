# Architecture

The Multicloud DB SDK is organized as a multi-module Maven project with a clean
separation between the portable API, the service provider interface (SPI), and
the provider implementations.

---

## Module Overview

| Module | Artifact | Description |
|--------|----------|-------------|
| **multiclouddb-api** | `com.microsoft.multiclouddb:multiclouddb-api` | Portable client interface, types, error model, factory, and SPI contracts. The only compile-time dependency your app needs. |
| **multiclouddb-provider-cosmos** | `com.microsoft.multiclouddb:multiclouddb-provider-cosmos` | Azure Cosmos DB adapter (Java SDK v4) |
| **multiclouddb-provider-dynamo** | `com.microsoft.multiclouddb:multiclouddb-provider-dynamo` | Amazon DynamoDB adapter (AWS SDK v2) |
| **multiclouddb-provider-spanner** | `com.microsoft.multiclouddb:multiclouddb-provider-spanner` | Google Cloud Spanner adapter (Google Cloud Spanner 6.62.0) |
| **multiclouddb-conformance** | `com.microsoft.multiclouddb:multiclouddb-conformance` | Cross-provider integration tests |
| **multiclouddb-samples** | `com.microsoft.multiclouddb:multiclouddb-samples` | Sample apps: TODO web app + multi-tenant Risk Analysis Platform |

---

## Dependency Graph

```
multiclouddb-api  ← must be released first if API changed
    ↑
    ├── multiclouddb-provider-cosmos   ← independent of other providers
    ├── multiclouddb-provider-dynamo   ← independent of other providers
    └── multiclouddb-provider-spanner  ← independent of other providers
```

Providers depend on a released version of `multiclouddb-api`. They are
independent of each other and can be released separately.

---

## API Surface

All application code depends on `multiclouddb-api`. The core types are:

| Type | Purpose |
|------|---------|
| `MulticloudDbClient` | Portable interface: `create`, `read`, `update`, `delete`, `upsert`, `query`, `provisionSchema`, `capabilities` |
| `MulticloudDbClientFactory` | Creates a `MulticloudDbClient` by discovering providers via `ServiceLoader` |
| `MulticloudDbClientConfig` | Builder-pattern config: provider selection, connection, auth, feature flags |
| `ResourceAddress` | `(database, collection)` pair targeting a container/table |
| `Key` | `(partitionKey, sortKey)` pair — every document needs at least a partition key |
| `QueryRequest` | Portable expression, native expression, parameters, page size, continuation token, partition key scoping, `limit`, `orderBy` |
| `QueryPage` | Result page: items + optional continuation token + optional diagnostics |
| `SortOrder` / `SortDirection` | Sort specification for `orderBy` — validates field names against injection |
| `DocumentResult` | Result of `read()`: document payload + optional `DocumentMetadata` |
| `DocumentMetadata` | Write-metadata: `lastModified`, `ttlExpiry`, `version` |
| `CapabilitySet` / `Capability` | Runtime introspection of provider capabilities |
| `MulticloudDbException` | Structured error with category, provider, and native code |
| `PortabilityWarning` | Signals non-portable behavior |
| `OperationOptions` | Per-call timeout, TTL, metadata flag |
| `OperationDiagnostics` | Latency, request units/charge, request ID, ETag, item count |

### Expression Types

| Type | Purpose |
|------|---------|
| `Expression` | AST node interface for parsed query expressions |
| `ExpressionParser` | Parses portable expression strings into an AST |
| `ExpressionValidator` | Validates parameter bindings and function usage |
| `ExpressionTranslator` | SPI — translates AST to provider-native query syntax |
| `TranslatedQuery` | Result of translation: query string + bound parameters |

---

## SPI (Provider Interface)

Provider modules implement two SPI contracts without importing each other:

| SPI Interface | Responsibility |
|---------------|---------------|
| `MulticloudDbProviderAdapter` | Factory — creates a `MulticloudDbProviderClient` from config; registered via `META-INF/services` |
| `MulticloudDbProviderClient` | CRUD + query + provisioning + capabilities — called by `DefaultMulticloudDbClient` |

---

## Provider Discovery

Providers are discovered at runtime via Java's `ServiceLoader`:

```
┌────────────────────────────────────────────────────────────────────────────────────┐
│  1. Your app calls MulticloudDbClientFactory.create(config)                        │
│                                                                                    │
│  2. The factory scans:                                                             │
│     META-INF/services/com.multiclouddb.spi.MulticloudDbProviderAdapter            │
│                                                                                    │
│  3. The matching adapter's createClient() builds a native                         │
│     SDK client (Cosmos SDK, DynamoDB SDK, or Spanner SDK)                         │
│                                                                                    │
│  4. A DefaultMulticloudDbClient wraps it with error mapping,                      │
│     diagnostics, and the portable contract                                        │
└────────────────────────────────────────────────────────────────────────────────────┘
```

**No provider imports in application code.** Just drop the provider JAR on the
classpath (or add it as a `<scope>runtime</scope>` Maven dependency).

---

## Project Structure

```
multiclouddb-sdk-java/
├── pom.xml                                # Parent POM (aggregator)
├── multiclouddb-api/                      # Portable API + SPI contracts
│   └── src/main/java/com/multiclouddb/
│       ├── api/                           # Public types
│       │   ├── internal/                  # DefaultMulticloudDbClient
│       │   └── query/                     # Expression AST, parser, validator
│       └── spi/                           # Provider SPI interfaces
├── multiclouddb-provider-cosmos/          # Azure Cosmos DB adapter
│   └── src/main/
│       ├── java/.../cosmos/               # CosmosProviderClient, capabilities
│       └── resources/META-INF/services/   # ServiceLoader registration
├── multiclouddb-provider-dynamo/          # Amazon DynamoDB adapter
├── multiclouddb-provider-spanner/         # Google Cloud Spanner adapter
├── multiclouddb-conformance/              # Cross-provider integration tests
├── multiclouddb-samples/                  # Sample applications
│   ├── src/main/java/.../todo/            # TODO web app
│   ├── src/main/java/.../riskplatform/    # Risk Analysis Platform
│   └── src/main/resources/
│       ├── static/                        # Browser UIs
│       └── *.properties                   # Provider config files
└── specs/                                 # Design documents
```

---

## Design Decisions

### Why Key Is an Explicit Parameter

Every CRUD operation requires an explicit `Key` parameter — the SDK never
extracts key material from the document body:

```java
client.upsert(addr, Key.of("tenant-1", "pos-42"), doc);
```

**Why not extract from the document?**

Each provider stores key fields using **different names**:

| Provider | `Key.partitionKey()` stored as | `Key.sortKey()` stored as |
|----------|-------------------------------|---------------------------|
| **Cosmos DB** | `partitionKey` (custom field) | `id` (built-in Cosmos field) |
| **DynamoDB** | `partitionKey` (hash key) | `sortKey` (range key) |
| **Spanner** | `partitionKey` (column) | `sortKey` (column) |

A convention-based extractor would need provider-specific logic in what is
supposed to be a provider-agnostic interface, which defeats portability.

| Concern | Explicit Key | Extracted from Document |
|---------|-------------|------------------------|
| `read()` / `delete()` | Works — no document needed | Impossible — no document |
| Consistency | All 5 operations use the same pattern | Writes differ from reads |
| Compile-time safety | Missing key = compiler error | Missing field = runtime error |
| Source of truth | Key is authoritative | Ambiguous when fields disagree |

See the [Developer Guide](guide.md#why-key-is-an-explicit-parameter) for the
full rationale.
