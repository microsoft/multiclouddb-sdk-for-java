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

> **Samples** are maintained in a separate repository:
> [microsoft/multiclouddb-sdk-for-java-samples](https://github.com/microsoft/multiclouddb-sdk-for-java-samples)

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
| `MulticloudDbKey` | `(partitionKey, sortKey)` pair - every document needs at least a partition key |
| `QueryRequest` | Portable expression, native expression, parameters, page size, continuation token, partition key scoping, `limit`, `orderBy` |
| `QueryPage` | Result page: items + optional continuation token + optional diagnostics |
| `SortOrder` / `SortDirection` | Sort specification for `orderBy` - validates field names against injection |
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
| `ExpressionTranslator` | SPI - translates AST to provider-native query syntax |
| `TranslatedQuery` | Result of translation: query string + bound parameters |

---

## SPI (Provider Interface)

Provider modules implement two SPI contracts without importing each other:

| SPI Interface | Responsibility |
|---------------|---------------|
| `MulticloudDbProviderAdapter` | Factory - creates a `MulticloudDbProviderClient` from config; registered via `META-INF/services` |
| `MulticloudDbProviderClient` | CRUD + query + provisioning + capabilities - called by `DefaultMulticloudDbClient` |

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
└── specs/                                 # Design documents

# Sample applications (separate repo):
# https://github.com/microsoft/multiclouddb-sdk-for-java-samples
```

---

## Design Decisions

### Why MulticloudDbKey Is an Explicit Parameter

Every CRUD operation requires an explicit `MulticloudDbKey` parameter - the SDK never
extracts key material from the document body:

```java
client.upsert(addr, MulticloudDbKey.of("tenant-1", "pos-42"), doc);
```

**Why not extract from the document?**

Each provider stores key fields using **different names**:

| Provider | `MulticloudDbKey.partitionKey()` stored as | `MulticloudDbKey.sortKey()` stored as |
|----------|-------------------------------|---------------------------|
| **Cosmos DB** | `partitionKey` (custom field) | `id` (built-in Cosmos field) |
| **DynamoDB** | `partitionKey` (hash key) | `sortKey` (range key) |
| **Spanner** | `partitionKey` (column) | `sortKey` (column) |

A convention-based extractor would need provider-specific logic in what is
supposed to be a provider-agnostic interface, which defeats portability.

| Concern | Explicit MulticloudDbKey | Extracted from Document |
|---------|-------------|------------------------|
| `read()` / `delete()` | Works - no document needed | Impossible - no document |
| Consistency | All 5 operations use the same pattern | Writes differ from reads |
| Compile-time safety | Missing key = compiler error | Missing field = runtime error |
| Source of truth | Key is authoritative | Ambiguous when fields disagree |

See the [Developer Guide](guide.md#why-key-is-an-explicit-parameter) for the
full rationale.

### Cosmos Gateway Transport

The Cosmos provider fixes Gateway mode and HTTP/2 rather than merely making
them defaults. One supported transport reduces configuration drift and keeps
network protocol, proxy, and firewall behavior from depending on an unnecessary
application-level choice. Retaining a Direct-mode switch would still allow a
path outside that policy.

These are separate layers:

| Layer | Responsibility |
|-------|----------------|
| Gateway mode | Uses Cosmos HTTP connectivity instead of Direct/RNTBD |
| Gateway HTTP/2 | Enables the wire protocol required by Gateway V2 and newer supported Cosmos features |
| Gateway V1/V2 | Selects the service routing path; V2 is a lower-overhead proxy for eligible data-plane requests |

Gateway V2 is also called the "thin client" inside the Azure SDK. It is not
another client library, application-side process, or sidecar to install.
Gateway mode and HTTP/2 remain fixed regardless of the selected Gateway version.

The provider explicitly enables HTTP/2 through the supported per-client
[`GatewayConnectionConfig`][cosmos-gateway-config] and
[`Http2ConnectionConfig`][cosmos-http2-config] APIs. SDK 4.82.0 still defaults
Gateway HTTP/2 to disabled, so relying on the native default would not meet
this contract. A builder setting also avoids depending on ambient process
configuration. The automatic routing contract requires
[Azure Cosmos Java SDK 4.82.0 or later][cosmos-sdk-release], which provides
probe-gated Gateway V2 selection rather than requiring a forced opt-in.

#### Service and SDK Ownership

The [Cosmos account response][cosmos-account-response] advertises Gateway V2
endpoint availability. The SDK starts on Gateway V1 and uses its
[connectivity configuration][cosmos-connectivity-config] and
[native probe][cosmos-endpoint-probe] to check an advertised endpoint.
Only a successful probe makes eligible data-plane requests candidates for V2.
Missing endpoints or an unsuccessful probe leave routing on V1; metadata and
other ineligible requests can still use V1 after a successful probe. This
fallback does not mean the wrapper suppresses native connectivity failures.

The wrapper adds no probe or request-routing layer and exposes no Gateway
version selector or separate query-plan routing switch. SDK 4.82.0 has no
supported public per-client Gateway V2 builder selector. Its
[internal JVM-wide thin-client flags][cosmos-internal-config] are not a
Multicloud DB contract: the wrapper neither reads nor writes them, including
through reflection. Mapping a connection property to those flags would create
an unsupported process-wide override and could bypass the safe probe.

#### Configuration and Portability Boundary

The presence of `connectionMode`, `gatewayHttp2Enabled`, `gatewayV2Enable`, or
`thinClientEnabled` causes rejection before native builder construction,
credential work, or network I/O, even for fixed-equivalent values such as
`gateway` or `true`. Silent acceptance would leave deployments with settings
that appear to control behavior but no longer do.

After successful native client construction, one INFO record describes the
fixed Gateway/HTTP2 policy and automatic account/SDK selection, without
including credentials. It is a **configuration snapshot**, not a negotiated
per-request route: the SDK exposes no public construction-time Gateway-version
getter, and routing can vary by topology, probe result, and request eligibility.

This provider-local decision changes no portable CRUD, query, paging,
diagnostics, capability, or error contracts and adds no persisted data model.
DynamoDB and Spanner need no corresponding setting. Connection-pool tuning is
separate from transport selection. See the authoritative
[configuration and migration guidance](configuration.md#transport-defaults)
for supported inputs and removal of pre-release settings.

[cosmos-sdk-release]: https://github.com/Azure/azure-sdk-for-java/releases/tag/com.azure%2Bazure-cosmos_4.82.0
[cosmos-gateway-config]: https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/GatewayConnectionConfig.java
[cosmos-http2-config]: https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/Http2ConnectionConfig.java
[cosmos-account-response]: https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/DatabaseAccount.java
[cosmos-connectivity-config]: https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/ThinClientConnectivityConfig.java
[cosmos-endpoint-probe]: https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/EndpointProbeClient.java
[cosmos-internal-config]: https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/Configs.java
