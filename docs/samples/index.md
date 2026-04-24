# Sample Applications

Sample applications demonstrating the Multicloud DB SDK's portable API are
maintained in a **separate repository**:

:material-github: **[microsoft/multiclouddb-sdk-for-java-samples](https://github.com/microsoft/multiclouddb-sdk-for-java-samples)**

Each sample runs against **Azure Cosmos DB**, **Amazon DynamoDB**, or
**Google Cloud Spanner** — switch providers by changing a single properties file.

---

## Available Samples

<div class="feature-grid" markdown>

<div class="card" markdown>

### :material-code-tags:{ .card-icon } Portable CRUD + Query

A minimal end-to-end sample showing CRUD operations and the portable query DSL
against any provider.

[View in samples repo →](https://github.com/microsoft/multiclouddb-sdk-for-java-samples#portable-crud--query-sample){ .md-button }

</div>

<div class="card" markdown>

### :material-checkbox-marked-outline:{ .card-icon } TODO App

A simple CRUD web application with a browser-based UI for creating, reading,
updating, and deleting TODO items.

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

| Feature | TODO App | Risk Platform |
|---------|:--------:|:-------------:|
| Basic CRUD operations | ✅ | ✅ |
| Portable query DSL | ✅ | ✅ |
| Partition-scoped queries | — | ✅ |
| Database-per-tenant isolation | — | ✅ |
| Auto-provisioning (`provisionSchema`) | — | ✅ |
| Provider portability (zero code changes) | ✅ | ✅ |
| Embedded HTTP server + browser UI | ✅ | ✅ |
| Cosmos DB support | ✅ | ✅ |
| DynamoDB support | ✅ | ✅ |
| Spanner support | ✅ | — |
