# Hyperscale DB SDK — Multi-Tenant Risk Analysis Platform

A professional-grade sample application built on the Hyperscale DB SDK, demonstrating
**database-per-tenant isolation** across Azure Cosmos DB and Amazon DynamoDB.
Inspired by enterprise risk platforms serving capital markets, investment banks,
and hedge funds.

The app starts an embedded HTTP server on `http://localhost:8090` with a
dark-themed executive dashboard for portfolio risk analytics.

---

## Table of Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Running the Platform](#running-the-platform)
   - [Option A: Cosmos DB Emulator](#option-a-run-against-cosmos-db-emulator)
   - [Option B: DynamoDB Local](#option-b-run-against-dynamodb-local)
   - [Option C: Cosmos DB (Azure Cloud)](#option-c-run-against-cosmos-db-azure-cloud)
   - [Option D: DynamoDB (AWS Cloud)](#option-d-run-against-dynamodb-aws-cloud)
   - [Switching Between Providers](#switching-between-providers)
   - [Running Both Providers Side by Side](#running-both-providers-side-by-side)
5. [Troubleshooting](#troubleshooting)
6. [Dashboard](#dashboard)
7. [REST API](#rest-api)
8. [Demo Tenants & Data](#demo-tenants--data)
9. [Project Structure](#project-structure)

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
| **Partition-Scoped Queries** | Uses `QueryRequest.partitionKey()` to scope queries to a single partition — e.g., positions within a portfolio are read from one partition, not a cross-partition scan |
| **Auto-Provisioning** | Databases, containers, and tables created automatically on startup |

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                 Executive Dashboard                   │
│           http://localhost:8090                        │
│     (dark-themed SPA — portfolio risk analytics)      │
└──────────────────────┬───────────────────────────────┘
                       │ REST API
┌──────────────────────┴───────────────────────────────┐
│              RiskPlatformApp (Java)                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │ Tenants  │ │Portfolios│ │ Risk     │ │ Market  │ │
│  │ API      │ │ API      │ │ Metrics  │ │ Data    │ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬────┘ │
│       └─────────────┴────────────┴─────────────┘      │
│                 TenantManager                         │
│           (database-per-tenant routing)               │
└──────────────────────┬───────────────────────────────┘
                       │ Hyperscale DB SDK
┌──────────────────────┴───────────────────────────────┐
│             HyperscaleDbClient (portable API)               │
│    ┌───────────────┐          ┌───────────────┐       │
│    │  Cosmos DB     │    OR   │  DynamoDB      │       │
│    │  Provider      │         │  Provider      │       │
│    └───────┬───────┘          └───────┬───────┘       │
│            │                          │               │
│   ┌────────┴────────┐       ┌────────┴────────┐      │
│   │  DB per tenant  │       │ Table prefix per │      │
│   │  acme-capital-  │       │ tenant:          │      │
│   │    risk-db      │       │ acme-capital-    │      │
│   │  vanguard-..    │       │   risk-db__      │      │
│   │  summit-..      │       │   portfolios     │      │
│   └─────────────────┘       └─────────────────┘      │
└──────────────────────────────────────────────────────┘
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
| JDK  | 17 LTS  | [Eclipse Adoptium](https://adoptium.net/) recommended |
| Maven | 3.9+   | Build tool |
| Node.js + npm | 18+ | Only needed for `dynamodb-admin` GUI (optional) |

Plus **at least one** of the following — either a local emulator or a cloud account:

**Local Emulators (Options A / B)**

| Emulator | Provider | Default endpoint |
|----------|----------|------------------|
| [Azure Cosmos DB Emulator](https://learn.microsoft.com/en-us/azure/cosmos-db/emulator) | Cosmos DB | `https://localhost:8081` |
| [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) | DynamoDB | `http://localhost:8000` |

**Cloud Accounts (Options C / D)**

| Service | Provider | Prerequisites |
|---------|----------|---------------|
| [Azure Cosmos DB](https://learn.microsoft.com/en-us/azure/cosmos-db/) | Cosmos DB | Azure subscription + Cosmos DB account + [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli) |
| [Amazon DynamoDB](https://aws.amazon.com/dynamodb/) | DynamoDB | AWS account + IAM credentials + [AWS CLI](https://aws.amazon.com/cli/) |

> **You do NOT need to create any databases, containers, or tables manually.**
> The Risk Platform **auto-provisions** everything on startup via the SDK's
> portable `ensureDatabase` / `ensureContainer` API.

---

## Running the Platform

### Step 0 — Set JAVA_HOME (all terminals)

Run this in every new PowerShell terminal before any `mvn` or `java` command:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
```

Verify:

```powershell
java -version
# → openjdk version "17.0.10" ...
```

### Step 1 — Build the SDK

From the **repo root** (`hyperscaledb-sdk-java/`):

```powershell
mvn clean install -DskipTests
```

Expected output ends with `BUILD SUCCESS`.

---

### Option A: Run against Cosmos DB Emulator

#### A1 — Start the emulator

**Windows (native installer):** Launch **Azure Cosmos DB Emulator** from the
Start Menu. Wait until the system tray icon shows it's ready (green).

**Docker (Linux/macOS):**

```bash
docker run -p 8081:8081 -p 10250-10255:10250-10255 \
  mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
```

#### A2 — Verify the emulator is running

```powershell
try {
    Invoke-WebRequest -Uri 'https://localhost:8081/' -SkipCertificateCheck -TimeoutSec 5
    Write-Host "Cosmos emulator: RUNNING"
} catch {
    Write-Host "Status: $($_.Exception.Response.StatusCode)"
}
```

You should see a `200` response, or `401 Unauthorized` — both confirm the
emulator is up. If you get a connection refused error, wait and retry.

#### A3 — Ensure the SSL truststore exists

The repo includes a pre-built truststore at `.tools/cacerts-local`. If it's
missing (e.g., fresh clone), create it:

```powershell
# Export the emulator's self-signed certificate
"" | openssl s_client -connect localhost:8081 2>$null | `
  openssl x509 -out .tools/cosmos-emulator.cer

# Import into a Java truststore
keytool -importcert -alias cosmosemulator -file .tools/cosmos-emulator.cer `
  -keystore .tools/cacerts-local -storepass changeit -noprompt
```

> If `.tools/cacerts-local` already exists, skip this step.

#### A4 — Launch the Risk Platform (Cosmos DB)

From the **repo root**:

```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit"
```

Wait for the startup log to show:

```
═══════════════════════════════════════════════════
  Risk Analysis Platform started on port 8090
  Provider: cosmos
  Dashboard: http://localhost:8090
═══════════════════════════════════════════════════
```

#### A5 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

#### A6 — Verify via REST API

```powershell
# List tenants (should return 3)
(Invoke-WebRequest http://localhost:8090/api/tenants -UseBasicParsing).Content | ConvertFrom-Json | Measure-Object | Select-Object -Expand Count

# Get dashboard for Acme Capital
Invoke-RestMethod http://localhost:8090/api/dashboard?tenant=acme-capital
```

#### A7 — Stop the app

Press `Ctrl+C` in the terminal running Maven.

---

### Option B: Run against DynamoDB Local

#### B1 — Start DynamoDB Local

DynamoDB Local is bundled in `.tools/dynamodb-local/`. Open a **new terminal**:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

cd .tools/dynamodb-local
java "-Djava.library.path=./DynamoDBLocal_lib" -jar DynamoDBLocal.jar -sharedDb -inMemory
```

> **Keep this terminal open.** DynamoDB Local runs in the foreground.
>
> | Flag | Purpose |
> |------|---------|
> | `-sharedDb` | All clients share a single database file |
> | `-inMemory` | Data is lost on restart (clean slate each time) |

#### B2 — Verify DynamoDB Local is running

In a **second terminal**:

```powershell
try {
    Invoke-WebRequest -Uri 'http://localhost:8000' -UseBasicParsing -TimeoutSec 5
} catch {
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)"
}
```

A `400` response is expected — DynamoDB Local only speaks its wire protocol.
If you get a connection error, check the DynamoDB Local terminal for errors.

#### B3 — Start DynamoDB Admin GUI (optional)

[dynamodb-admin](https://github.com/aaronshaf/dynamodb-admin) is a lightweight
Node.js web UI for browsing DynamoDB Local tables and items. Requires **Node.js
18+** and npm.

Install (one-time):

```bash
npm install -g dynamodb-admin
```

Start in a **new terminal**:

```powershell
$env:DYNAMO_ENDPOINT = 'http://localhost:8000'
dynamodb-admin
```

Open **http://localhost:8001** in your browser. You'll see all tables and items
created by the Risk Platform — useful for inspecting the tenant-per-database
isolation model, partition keys, and seeded data.

> **Tip**: Keep DynamoDB Local, `dynamodb-admin`, and the Risk Platform each in
> their own terminal.

#### B4 — Launch the Risk Platform (DynamoDB)

In the **second terminal**, from the **repo root**:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-dynamo.properties"
```

Wait for the startup log (same as Cosmos, but `Provider: dynamo`).

#### B5 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

#### B6 — Verify via REST API

```powershell
# List tenants (should return 3)
(Invoke-WebRequest http://localhost:8090/api/tenants -UseBasicParsing).Content | ConvertFrom-Json | Measure-Object | Select-Object -Expand Count

# Get dashboard for Acme Capital
Invoke-RestMethod http://localhost:8090/api/dashboard?tenant=acme-capital
```

#### B7 — Stop the app

Press `Ctrl+C` in the Maven terminal, `dynamodb-admin` terminal (if running),
and the DynamoDB Local terminal.

---

### Option C: Run against Cosmos DB (Azure Cloud)

This option connects to a **real Azure Cosmos DB account** using
**Microsoft Entra ID** (Azure AD) authentication via `DefaultAzureCredential`.
No master key is needed — authentication uses your Azure identity.

#### C1 — Sign in with Azure CLI

```powershell
az login
```

Verify:

```powershell
az account show --query "{name:name, id:id}" -o table
```

#### C2 — Grant Cosmos DB RBAC role

Your Azure identity needs the **Cosmos DB Built-in Data Contributor** role on
the Cosmos DB account. Run once per account:

```powershell
az cosmosdb sql role assignment create `
  --account-name tvk-my-cosmos-account `
  --resource-group <YOUR-RESOURCE-GROUP> `
  --role-definition-name "Cosmos DB Built-in Data Contributor" `
  --scope "/" `
  --principal-id (az ad signed-in-user show --query id -o tsv)
```

> Replace `<YOUR-RESOURCE-GROUP>` with the resource group containing
> your Cosmos DB account.

#### C3 — Launch the Risk Platform (Cosmos DB Cloud)

From the **repo root**:

```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos-cloud.properties"
```

> **No truststore needed** — Azure Cosmos DB uses a publicly trusted TLS
> certificate, unlike the local emulator.

#### C4 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

#### C5 — Stop the app

Press `Ctrl+C` in the terminal running Maven.

---

### Option D: Run against DynamoDB (AWS Cloud)

This option connects to the **real Amazon DynamoDB service** using the
AWS default credential provider chain. No explicit access key/secret is needed
in the properties file.

#### D1 — Install the AWS CLI

Download and install from https://aws.amazon.com/cli/. Verify:

```powershell
aws --version
```

#### D2 — Configure AWS credentials

```powershell
aws configure
```

Enter:

| Prompt | Value |
|--------|-------|
| AWS Access Key ID | Your IAM access key |
| AWS Secret Access Key | Your IAM secret key |
| Default region name | `us-east-1` (or your preferred region) |
| Default output format | `json` |

> **For production**, use IAM roles instead of long-lived access keys.
> The SDK automatically picks up EC2 instance profiles, ECS task roles,
> and Lambda execution roles.

#### D3 — Verify AWS access

```powershell
aws dynamodb list-tables --region us-east-1
```

You should see `{ "TableNames": [] }` or a list of existing tables.

#### D4 — Launch the Risk Platform (DynamoDB Cloud)

From the **repo root**:

```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-dynamo-cloud.properties"
```

#### D5 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

#### D6 — Stop and clean up

Press `Ctrl+C` in the terminal running Maven.

> **Cost note:** DynamoDB charges for read/write capacity and storage.
> The demo creates a small number of tables and items. To avoid ongoing
> charges, delete the tables after testing:
>
> ```powershell
> # List tables created by the Risk Platform
> aws dynamodb list-tables --region us-east-1
> # Delete each table (example)
> aws dynamodb delete-table --table-name acme-capital-risk-db__portfolios --region us-east-1
> ```

---

### Switching Between Providers

To switch from one provider to the other:

1. Stop the Risk Platform (`Ctrl+C`)
2. Stop the prior emulator if needed, start the other one (for local options)
3. Re-run the `mvn -pl hyperscaledb-samples exec:java ...` command with the
   desired properties file:
   - `risk-platform-cosmos.properties` — Cosmos DB Emulator
   - `risk-platform-dynamo.properties` — DynamoDB Local
   - `risk-platform-cosmos-cloud.properties` — Azure Cosmos DB (cloud)
   - `risk-platform-dynamo-cloud.properties` — Amazon DynamoDB (cloud)

The application code is **identical** — only the properties file changes.

### Running Both Providers Side by Side

You can run **two instances simultaneously** on different ports to compare
Cosmos DB and DynamoDB side by side. Make sure both emulators are running first
(see Steps A1 and B1 above), then open **four terminals**:

**Terminal 1** — DynamoDB Local (if not already running):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

cd .tools/dynamodb-local
java "-Djava.library.path=./DynamoDBLocal_lib" -jar DynamoDBLocal.jar -sharedDb -inMemory
```

**Terminal 2** — DynamoDB Admin GUI (optional, requires Node.js 18+):

```powershell
$env:DYNAMO_ENDPOINT = 'http://localhost:8000'
dynamodb-admin
```

Open **http://localhost:8001** to browse DynamoDB tables and items.

**Terminal 3** — Risk Platform on Cosmos DB (port 8090):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit" `
  "-Drisk.port=8090"
```

**Terminal 4** — Risk Platform on DynamoDB (port 8091):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-dynamo.properties" `
  "-Drisk.port=8091"
```

Then open browser windows:

| Window | URL | Purpose |
|--------|-----|----------|
| Left   | http://localhost:8090 | Risk Platform — Azure Cosmos DB |
| Right  | http://localhost:8091 | Risk Platform — Amazon DynamoDB |
| Optional | http://localhost:8001 | DynamoDB Admin GUI (table/item browser) |

Both dashboards show the same tenants, portfolios, and risk data — the only
difference is the underlying database engine. The **Provider** indicator in the
dashboard header shows which one you're looking at.

### Configuration Files

| File | Provider | Target | Auth |
|------|----------|--------|------|
| `risk-platform-cosmos.properties` | Azure Cosmos DB | `https://localhost:8081` (emulator) | Master key |
| `risk-platform-cosmos-cloud.properties` | Azure Cosmos DB | Azure cloud endpoint | DefaultAzureCredential (Entra ID) |
| `risk-platform-dynamo.properties` | Amazon DynamoDB | `http://localhost:8000` (local) | Fake static credentials |
| `risk-platform-dynamo-cloud.properties` | Amazon DynamoDB | AWS DynamoDB service | Default AWS credential chain |

Both files live in `hyperscaledb-samples/src/main/resources/`.

### Changing the Port

Override the default port (8090) with:

```powershell
"-Drisk.port=9090"
```

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| `Address already in use: bind` on port 8090 | A previous instance is still running | Kill it: `Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue \| ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }` |
| `PKIX path building failed` / SSL errors (Cosmos) | Truststore missing or wrong path | Re-create the truststore (see Step A3) and ensure `$PWD` resolves correctly — run from repo root |
| `Connection refused` on port 8081 (Cosmos) | Emulator not started | Launch the Cosmos DB Emulator from Start Menu and wait for the tray icon |
| `Connection refused` on port 8000 (DynamoDB) | DynamoDB Local not started | Start it in a separate terminal (see Step B1) |
| `UnsatisfiedLinkError` (DynamoDB Local) | Wrong `-Djava.library.path` | Use `-Djava.library.path=./DynamoDBLocal_lib` (not `.`) |
| `BUILD FAILURE` on `mvn install` | JAVA_HOME not set | Run `$env:JAVA_HOME = '...'` and `$env:PATH = ...` first |
| `ClassNotFoundException` when running the app | SDK not built | Run `mvn clean install -DskipTests` from repo root first |
| `DefaultAzureCredential authentication failed` (Cosmos Cloud) | Not signed in to Azure CLI | Run `az login` and verify with `az account show` |
| `403 Forbidden` on Cosmos DB Cloud | Missing RBAC role | Assign the **Cosmos DB Built-in Data Contributor** role (see Step C2) |
| `Unable to load credentials` (DynamoDB Cloud) | AWS credentials not configured | Run `aws configure` and enter your access key / secret (see Step D2) |

---

## Dashboard

The executive dashboard at `http://localhost:8090` provides:

- **Tenant switcher** — Toggle between firms to see isolated data
- **Portfolio overview** — AUM, positions, P&L across all portfolios
- **Risk metrics panel** — VaR, Sharpe, Beta, volatility at a glance
- **Position table** — Sortable holdings with real-time pricing
- **Risk alerts** — Severity-coded alerts with type classification
- **Market data ticker** — Live pricing for tracked securities
- **Provider indicator** — Shows which database provider is active

---

## REST API

All endpoints accept a `?tenant=<tenantId>` query parameter for tenant context.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/tenants` | List all registered tenants |
| `GET`  | `/api/tenants/{id}` | Get tenant details |
| `GET`  | `/api/portfolios?tenant={id}` | List portfolios for a tenant |
| `GET`  | `/api/positions?tenant={id}&portfolio={id}` | List positions (when `portfolio` is specified, uses partition-scoped query via `QueryRequest.partitionKey()`) |
| `GET`  | `/api/risk?tenant={id}` | Risk metrics for all tenant portfolios |
| `GET`  | `/api/alerts?tenant={id}` | Risk alerts for a tenant |
| `GET`  | `/api/market` | Market data for tracked securities |
| `GET`  | `/api/dashboard?tenant={id}` | Aggregated dashboard payload (portfolios + risk + alerts + market) |
| `GET`  | `/api/provider` | Current provider name and capabilities |

### Example Requests

```powershell
# Get dashboard data for Acme Capital
Invoke-RestMethod http://localhost:8090/api/dashboard?tenant=acme-capital

# List all tenants
Invoke-RestMethod http://localhost:8090/api/tenants

# Get positions for a specific portfolio
Invoke-RestMethod "http://localhost:8090/api/positions?tenant=acme-capital&portfolio=acme-eq-alpha"

# Get risk alerts for Vanguard Partners
Invoke-RestMethod http://localhost:8090/api/alerts?tenant=vanguard-partners

# Check provider info
Invoke-RestMethod http://localhost:8090/api/provider
```

---

## Demo Tenants & Data

The platform auto-seeds three realistic tenant firms on startup:

### Acme Capital Management (Hedge Fund)

| Portfolio | Type | Strategy |
|-----------|------|----------|
| Alpha Equity Fund | EQUITY | Aggressive US equities (AAPL, MSFT, NVDA, JPM, JNJ, AMZN) |
| Global Macro Strategy | MULTI_ASSET | Commodities, treasuries, EM, currencies |
| Tech Innovation Fund | EQUITY | High-conviction tech (META, GOOGL, CRM, AVGO) |

### Vanguard Global Partners (Investment Bank)

| Portfolio | Type | Strategy |
|-----------|------|----------|
| Balanced Growth Fund | MULTI_ASSET | 70/30 equity/bond allocation (VTI, VXUS, BND, VNQ) |
| Fixed Income Plus | FIXED_INCOME | Multi-sector bonds (BNDX, LQD, HYG, TIP) |
| Sustainable Leaders Fund | EQUITY | ESG-focused (MSFT, NEE, TSLA, ENPH) |

### Summit Wealth Advisors (Wealth Management)

| Portfolio | Type | Strategy |
|-----------|------|----------|
| Conservative Income | MULTI_ASSET | 40/60 equity/bond with dividends (AGG, VYM, SCHD, SHY) |
| Growth Allocation | EQUITY | Index + thematic (QQQ, VOO, ARKK, SMH) |

### Seeded Data Per Tenant

- 2–3 portfolios with 4–6 positions each
- Risk metrics per portfolio (VaR, Sharpe, Beta, drawdown, volatility)
- Risk alerts (2–3 per tenant, varying severity)
- Shared market data (12 securities with pricing)

---

## Project Structure

```
hyperscaledb-samples/src/main/
├── java/com/hyperscaledb/samples/riskplatform/
│   ├── RiskPlatformApp.java        # HTTP server + REST endpoints
│   ├── data/
│   │   └── DemoDataSeeder.java     # Realistic demo data for 3 tenants
│   ├── infra/
│   │   └── ResourceProvisioner.java # Auto-creates DBs/containers/tables
│   ├── model/
│   │   └── Models.java             # Domain model builders (Portfolio, Position, Risk, etc.)
│   └── tenant/
│       └── TenantManager.java      # Tenant isolation + database routing
└── resources/
    ├── risk-platform-cosmos.properties        # Cosmos DB emulator config
    ├── risk-platform-cosmos-cloud.properties   # Cosmos DB cloud config (Entra ID)
    ├── risk-platform-dynamo.properties        # DynamoDB Local config
    ├── risk-platform-dynamo-cloud.properties   # DynamoDB cloud config (AWS)
    └── static/riskplatform/
        └── index.html                   # Executive dashboard SPA
```
