---
hide:
  - navigation
  - toc
---

<div class="hero-banner" markdown>

![Multicloud DB SDK - Unified Data Access Layer for Best-of-Breed Cloud DBs](images/multiclouddb-banner.png)

# Multicloud DB SDK for Java

A **portable database SDK** that lets you write create, read, upsert, delete, and
query logic once and run it against **Azure Cosmos DB**, **Amazon DynamoDB**, or
**Google Cloud Spanner**. Optional operations such as partial `update()` are
capability-gated.

<div class="hero-buttons" markdown>

[Get Started](getting-started.md){ .md-button .md-button--primary }
[Developer Guide](guide.md){ .md-button }
[View on GitHub](https://github.com/microsoft/multiclouddb-sdk-for-java){ .md-button }

</div>

</div>

!!! warning "Public Preview"

    This SDK is currently available as a **public preview** and is not yet fully
    ready for production use. Expect breaking changes, incomplete features, and
    limited support during this phase.

---

## Why Multicloud DB?

| Challenge | How the SDK helps |
|-----------|-------------------|
| **Vendor lock-in** | Single `MulticloudDbClient` interface - portable point operations (create/read/upsert/delete) + query with explicit capability gates |
| **Divergent query languages** | Portable DSL auto-translated to Cosmos SQL, PartiQL, or GoogleSQL |
| **Migration pain** | Switch providers by changing one property for the common operation set; check capabilities before optional operations |
| **Feature uncertainty** | Runtime `CapabilitySet` introspection and structured `UNSUPPORTED_CAPABILITY` errors |
| **Cross-provider testing** | Shared conformance verifies the common contract and capability-gated behavior |

---

## Key Features

<div class="feature-grid" markdown>

<div class="card" markdown>

### :material-swap-horizontal: Write Once, Run Anywhere

Single `MulticloudDbClient` interface for portable point operations and query.
Capability discovery makes optional operations explicit when providers differ.

[Learn more →](architecture.md)

</div>

<div class="card" markdown>

### :material-translate: Portable Query DSL

Write WHERE-clause filters once using a SQL-subset syntax with named parameters.
Automatically translated to Cosmos SQL, DynamoDB PartiQL, or Spanner GoogleSQL.

[Learn more →](compatibility.md#query--portable-expression-dsl)

</div>

<div class="card" markdown>

### :material-shield-check: Capability Introspection

Query provider capabilities at runtime. Get clear signals when a feature
is unavailable or behaviour may differ across providers.

[Learn more →](compatibility.md)

</div>

<div class="card" markdown>

### :material-office-building: Multi-Tenant Patterns

Database-per-tenant isolation via `ResourceAddress` routing.
Partition-scoped queries for efficient within-partition reads.

[Learn more →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-risk-platform.md)

</div>

<div class="card" markdown>

### :material-test-tube: Conformance Testing

Shared point-operation and query conformance runs against each provider
emulator; optional behavior is exercised only when its capability is advertised.

[Learn more →](contributing.md)

</div>

<div class="card" markdown>

### :material-speedometer: Provider Diagnostics

Structured diagnostics with latency, request charge (RU), and provider
correlation IDs. SLF4J structured logging for production monitoring.

[Learn more →](api-reference.md)

</div>

</div>

---

## Architecture

```mermaid
graph TD
    APP["<b>Your Application</b><br/>code against Multicloud DB API"]
    API["<b>MulticloudDbClient</b><br/>multiclouddb-api<br/><i>Base Ops · Query · Capabilities</i>"]
    SL(("ServiceLoader"))
    COSMOS["<b>Cosmos DB</b><br/>Provider"]
    DYNAMO["<b>DynamoDB</b><br/>Provider"]
    SPANNER["<b>Spanner</b><br/>Provider"]

    APP --> API
    API --> SL
    SL --> COSMOS
    SL --> DYNAMO
    SL --> SPANNER
```

Providers are discovered at runtime via Java's `ServiceLoader` - no provider
imports in application code. Drop the provider JAR on the classpath and
configure via properties.

[Learn more about the architecture :material-arrow-right:](architecture.md){ .md-button }

---

## Supported Providers

| Provider | Module | Status | Native SDK |
|----------|--------|--------|------------|
| **Azure Cosmos DB** | `multiclouddb-provider-cosmos` | Full | Azure Cosmos Java SDK |
| **Amazon DynamoDB** | `multiclouddb-provider-dynamo` | Full | AWS SDK for Java 2.x |
| **Google Cloud Spanner** | `multiclouddb-provider-spanner` | Source only* | Google Cloud Spanner |

> \* The Spanner provider source code and conformance tests are included in the repository,
> but **Maven artifacts are not yet published**. Spanner artifacts will be available in a future release.

---

## Sample Applications

Sample applications are maintained in a separate repository:
:material-github: **[microsoft/multiclouddb-sdk-for-java-samples](https://github.com/microsoft/multiclouddb-sdk-for-java-samples)**

| Sample | Description | Details |
|--------|-------------|---------|
| **Portable Point Operations + Query** | Minimal create/read/upsert/delete/query sample; update is capability-gated | [View guide →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples#portable-crud--query-sample) |
| **TODO App** | Key-based document web app; completion update requires `PARTIAL_UPDATE` | [View guide →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-todo-app.md) |
| **Risk Analysis Platform** | Multi-tenant portfolio risk analytics with executive dashboard | [View guide →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-risk-platform.md) |

Sample callers check `Capability.PARTIAL_UPDATE` before `update()`. Cosmos DB
and DynamoDB support it; the current Spanner provider rejects a valid update
before provider I/O.

---

## Quick Example

```java
// Configure - provider selected entirely by config
Properties props = new Properties();
props.load(getClass().getResourceAsStream("/app.properties"));

MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.fromId(props.getProperty("multiclouddb.provider")))
    .connection("endpoint", props.getProperty("multiclouddb.connection.endpoint"))
    // Auth properties (key, credentials, etc.) are loaded from the
    // properties file. See Configuration Reference for recommended
    // identity-based auth patterns for each provider.
    .build();

try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {

// Portable point operations - same code for every provider
ResourceAddress todos = new ResourceAddress("mydb", "todos");
MulticloudDbKey key = MulticloudDbKey.of("todo-1", "todo-1");
Map<String, Object> doc = Map.of(
    "status", "active",
    "category", "shopping"
);
client.upsert(todos, key, doc);

// Query with portable expressions - auto-translated per provider
QueryRequest query = QueryRequest.builder()
    .expression("status = @status AND category = @cat")
    .parameters(Map.of("status", "active", "cat", "shopping"))
    .maxPageSize(25)
    .build();
QueryPage page = client.query(todos, query);
}
```

[Get started →](getting-started.md){ .md-button .md-button--primary }
