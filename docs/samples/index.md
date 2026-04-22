# Sample Applications

The SDK includes two sample applications demonstrating the portable API.
Each sample runs against **Azure Cosmos DB** or **Amazon DynamoDB** —
switch providers by changing a single properties file. Some samples also
support **Google Cloud Spanner**; see the sample guides and support table below.

---

## Available Samples

<div class="feature-grid" markdown>

<div class="card" markdown>

### :material-checkbox-marked-outline:{ .card-icon } TODO App

A simple CRUD web application with a browser-based UI for creating, reading,
updating, and deleting TODO items.

**Port:** `8080`

[View guide →](todo-app.md){ .md-button }

</div>

<div class="card" markdown>

### :material-chart-line:{ .card-icon } Risk Analysis Platform

A multi-tenant portfolio risk analytics platform with an executive dashboard.
Demonstrates database-per-tenant isolation, partition-scoped queries, and
auto-provisioning.

**Port:** `8090`

[View guide →](risk-platform.md){ .md-button }

</div>

</div>

---

## Quick Start

Build the SDK from the repo root first:

```bash
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
