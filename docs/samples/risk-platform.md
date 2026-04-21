# Risk Analysis Platform

A professional-grade sample application built on the Multicloud DB SDK, demonstrating
**database-per-tenant isolation** across Azure Cosmos DB and Amazon DynamoDB.
Inspired by enterprise risk platforms serving capital markets, investment banks,
and hedge funds.

The app starts an embedded HTTP server on `http://localhost:8090` with a
dark-themed executive dashboard for portfolio risk analytics.

---

## Features

| Feature | Description |
|---------|-------------|
| **Multi-Tenant Isolation** | Database-per-tenant model — each tenant's data in a separate Cosmos DB database or DynamoDB table namespace |
| **Portfolio Management** | Multiple portfolios per tenant with real-time position tracking |
| **Risk Analytics** | VaR (95%/99%), Sharpe ratio, Beta, max drawdown, volatility, Treynor ratio |
| **Market Data Feed** | Real-time pricing for equities, bonds, commodities, and ETFs |
| **Risk Alerting** | Alert engine with severity levels (CRITICAL, HIGH, MEDIUM, LOW) |
| **Executive Dashboard** | Professional dark-themed financial UI with charts and data tables |
| **Provider Portability** | Zero code changes to switch between Cosmos DB and DynamoDB |
| **Partition-Scoped Queries** | Uses `QueryRequest.partitionKey()` to scope queries efficiently |
| **Auto-Provisioning** | Databases, containers, and tables created automatically on startup |

---

## Architecture

```mermaid
graph TD
    Dashboard["Executive Dashboard<br/><i>localhost:8090</i>"]
    Dashboard -->|REST API| App
    App --> TM["TenantManager<br/><i>database-per-tenant routing</i>"]
    TM -->|Multicloud DB SDK| Client

    subgraph App["RiskPlatformApp — Java"]
        A1["Tenants API"] ~~~ A2["Portfolios API"] ~~~ A3["Risk Metrics"] ~~~ A4["Market Data"]
    end

    subgraph Client["MulticloudDbClient — portable API"]
        Cosmos["Cosmos DB Provider<br/><i>DB per tenant</i>"] ~~~ Dynamo["DynamoDB Provider<br/><i>Table-prefix per tenant</i>"]
    end

    style Dashboard fill:#e8eaf6,stroke:#3949ab,color:#1a237e
    style App fill:#e3f2fd,stroke:#1565c0,color:#0d47a1
    style TM fill:#fff3e0,stroke:#ef6c00,color:#e65100
    style Client fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
```

### Tenant Isolation Model

| Provider | Strategy | Example |
|----------|----------|---------|
| **Cosmos DB** | Separate database per tenant | Database: `acme-capital-risk-db`, Container: `portfolios` |
| **DynamoDB** | Composite table name | Table: `acme-capital-risk-db__portfolios` |

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 17 LTS  | Required |
| Maven | 3.9+   | Build tool |

Plus one of:

| Option | Extra Tools |
|--------|-------------|
| **A** — Cosmos DB Emulator | `openssl`, `keytool` |
| **B** — DynamoDB Local | _(none)_ |
| **C** — Cosmos DB Cloud | Azure CLI (`az login`) |
| **D** — DynamoDB Cloud | AWS CLI (`aws configure`) |

!!! note "Auto-provisioning"

    You do **not** need to create any databases, containers, or tables manually.
    The Risk Platform auto-provisions everything on startup via the SDK's
    portable `provisionSchema()` API.

---

## Running the Platform

=== "Cosmos DB Emulator"

    Start the Cosmos DB Emulator, then:

    ```bash
    mvn -pl multiclouddb-samples exec:java \
      -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
      -Drisk.config=risk-platform-cosmos.properties \
      -Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local \
      -Djavax.net.ssl.trustStorePassword=changeit
    ```

=== "DynamoDB Local"

    Start DynamoDB Local on port 8000, then:

    ```bash
    mvn -pl multiclouddb-samples exec:java \
      -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
      -Drisk.config=risk-platform-dynamo.properties
    ```

=== "Cosmos DB (Azure Cloud)"

    Create a `risk-platform-cosmos-cloud.properties` file with your Azure credentials, then:

    ```bash
    mvn -pl multiclouddb-samples exec:java \
      -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
      -Drisk.config=risk-platform-cosmos-cloud.properties
    ```

=== "DynamoDB (AWS Cloud)"

    Create a `risk-platform-dynamo-cloud.properties` file with your AWS credentials, then:

    ```bash
    mvn -pl multiclouddb-samples exec:java \
      -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
      -Drisk.config=risk-platform-dynamo-cloud.properties
    ```

Then open **http://localhost:8090** in your browser.

### Running Both Providers Side by Side

You can run two instances simultaneously on different ports to compare
providers:

```bash
# Terminal 1 — Cosmos DB on port 8090
mvn -pl multiclouddb-samples exec:java \
  -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-cosmos.properties \
  -Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local \
  -Djavax.net.ssl.trustStorePassword=changeit

# Terminal 2 — DynamoDB on port 8091
mvn -pl multiclouddb-samples exec:java \
  -Dexec.mainClass=com.multiclouddb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-dynamo.properties \
  -Drisk.port=8091
```

---

## Dashboard

The executive dashboard provides:

- **Tenant selector** — switch between demo tenants
- **Portfolio overview** — aggregated metrics per portfolio
- **Position details** — individual holdings with real-time pricing
- **Risk metrics** — VaR, Sharpe ratio, Beta, max drawdown, volatility
- **Alert feed** — risk alerts with severity levels and timestamps

---

## REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/tenants` | GET | List all tenants |
| `/api/tenants/{id}/portfolios` | GET | List portfolios for a tenant |
| `/api/tenants/{id}/portfolios/{pid}` | GET | Portfolio detail with positions |
| `/api/tenants/{id}/portfolios/{pid}/risk` | GET | Risk metrics for a portfolio |
| `/api/tenants/{id}/alerts` | GET | Active alerts for a tenant |
| `/api/market-data` | GET | Current market prices |

---

## Demo Tenants

The platform seeds three demo tenants on startup:

| Tenant | Description | Portfolios |
|--------|-------------|------------|
| **Acme Capital** | Large diversified investment firm | Global Equity, Fixed Income, Commodities |
| **Vanguard Partners** | Technology-focused fund | Tech Growth, Balanced |
| **Summit Investments** | Conservative wealth manager | Conservative Income, Real Assets |

Each tenant demonstrates the database-per-tenant isolation pattern with
realistic portfolio data and risk metrics.

---

## Key SDK Features Demonstrated

### Database-per-Tenant via `ResourceAddress`

```java
public ResourceAddress addressFor(String tenantId, String collection) {
    String database = tenantId + "-risk-db";
    return new ResourceAddress(database, collection);
}
```

### Partition-Scoped Queries

```java
// Efficiently query positions within a single portfolio
QueryRequest query = QueryRequest.builder()
    .expression("sector = @sector")
    .parameter("sector", "Technology")
    .partitionKey("portfolio-alpha")
    .pageSize(50)
    .build();
```

### Auto-Provisioning

```java
Map<String, List<String>> schema = Map.of(
    "acme-risk-db", List.of("portfolios", "positions", "risk_metrics", "alerts")
);
client.provisionSchema(schema);
```

For complete setup instructions including emulator installation and cloud
provider configuration, see the
[full README](https://github.com/microsoft/multiclouddb-sdk-for-java/blob/main/multiclouddb-samples/README-risk-platform.md)
in the repository.
