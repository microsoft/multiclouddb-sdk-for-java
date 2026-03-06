# Hyperscale DB SDK - Sample Applications

This module contains sample applications demonstrating the Hyperscale DB SDK's
provider-portable API. Each sample runs against **Azure Cosmos DB** or
**Amazon DynamoDB** - switch providers by changing a single properties file.

| Sample | Description | Port | README |
|--------|-------------|------|--------|
| **Risk Analysis Platform** | Multi-tenant portfolio risk analytics with executive dashboard | `8090` | [README-risk-platform.md](README-risk-platform.md) |
| **TODO App** | Simple CRUD web app with browser UI | `8080` | [README-todo-app.md](README-todo-app.md) |

---

## Quick Start

Build the SDK from the repo root first:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
mvn clean install -DskipTests
```

Then see the individual READMEs above for per-sample instructions.
