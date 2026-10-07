# Sample Applications

Sample applications demonstrating the Multicloud DB SDK's portable API are
maintained in a **separate repository**:

:material-github: **[microsoft/multiclouddb-sdk-for-java-samples](https://github.com/microsoft/multiclouddb-sdk-for-java-samples)**

Each sample can target **Azure Cosmos DB**, **Amazon DynamoDB**, or
**Google Cloud Spanner** by changing a properties file. The portable base
operations remain configuration-switchable. Sample code that calls partial
`update()` must first check `Capability.PARTIAL_UPDATE`: Cosmos DB and DynamoDB
support it, while the current Spanner provider rejects it before provider I/O.

---

## Available Samples

<div class="feature-grid" markdown>

<div class="card" markdown>

### :material-code-tags:{ .card-icon } Portable Point Operations + Query

A minimal end-to-end sample showing create/read/upsert/delete and the portable
query DSL against any provider, with partial update exercised only when the
selected provider advertises it.

[View guide →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples#portable-crud--query-sample){ .md-button }

</div>

<div class="card" markdown>

### :material-checkbox-marked-outline:{ .card-icon } TODO App

A web application with a browser-based UI for creating, reading, and deleting
TODO items. Completion updates are enabled only when the selected provider
advertises `PARTIAL_UPDATE`.

**Port:** `8080`

[View guide →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-todo-app.md){ .md-button }

</div>

<div class="card" markdown>

### :material-chart-line:{ .card-icon } Risk Analysis Platform

A multi-tenant portfolio risk analytics platform with an executive dashboard.
Demonstrates database-per-tenant isolation, partition-scoped queries, and
auto-provisioning.

**Port:** `8090`

[View guide →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples/blob/main/README-risk-platform.md){ .md-button }

</div>

</div>

---

## Quick Start

Clone the samples repository and build:

```bash
git clone https://github.com/microsoft/multiclouddb-sdk-for-java-samples.git
cd multiclouddb-sdk-for-java-samples
mvn clean install -DskipTests
```

Then see the individual guides above for per-sample instructions.

---

## What the Samples Demonstrate

| Feature | Portable Point Operations + Query | TODO App | Risk Platform |
|---------|:---------------------:|:--------:|:-------------:|
| Base create/read/upsert/delete operations | ✅ | ✅ | ✅ |
| Capability check before partial update | ✅ | ✅ | — |
| Portable query DSL | ✅ | ✅ | ✅ |
| Partition-scoped queries | — | — | ✅ |
| Database-per-tenant isolation | — | — | ✅ |
| Auto-provisioning (`provisionSchema`) | — | — | ✅ |
| Base-provider switching (configuration only) | ✅ | ✅ | ✅ |
| Embedded HTTP server + browser UI | — | ✅ | ✅ |
| Cosmos DB support | ✅ | ✅ | ✅ |
| DynamoDB support | ✅ | ✅ | ✅ |
| Spanner support | ✅ | ✅ | — |

Spanner support in these samples covers the portable base. The current Spanner
provider does not advertise `PARTIAL_UPDATE`; callers skip or disable that path.
