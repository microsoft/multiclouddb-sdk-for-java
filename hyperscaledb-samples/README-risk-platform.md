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
     - [Provider Parameters](#provider-parameters)
     - [Full Examples Per Provider](#full-examples-per-provider)
   - [Running Both Providers Side by Side](#running-both-providers-side-by-side)
     - [What You Need](#what-you-need)
     - [Terminal 1 — Cosmos DB Emulator](#terminal-1--cosmos-db-emulator)
     - [Terminal 2 — DynamoDB Local](#terminal-2--dynamodb-local)
     - [Terminal 3 — DynamoDB Admin GUI (optional)](#terminal-3--dynamodb-admin-gui-optional)
     - [Terminal 4 — Risk Platform on Cosmos DB](#terminal-4--risk-platform-on-cosmos-db-emulator-port-8090)
     - [Terminal 5 — Risk Platform on DynamoDB](#terminal-5--risk-platform-on-dynamodb-local-port-8091)
5. [Troubleshooting](#troubleshooting)
6. [Dashboard](#dashboard)
7. [REST API](#rest-api)
8. [Demo Tenants & Data](#demo-tenants--data)
9. [Project Structure](#project-structure)
10. [Appendix: Installing Prerequisites](#appendix-installing-prerequisites)
    - [Install JDK](#install-jdk)
    - [Set JAVA_HOME](#set-java_home)
    - [Install Maven](#install-maven)
    - [Install Azure CLI](#install-azure-cli)
      - [Azure Setup for Option C](#azure-setup-for-option-c)
    - [Install AWS CLI](#install-aws-cli)
      - [AWS Setup for Option D](#aws-setup-for-option-d)
    - [Install OpenSSL (Windows)](#install-openssl-windows-only--needed-for-option-a)
    - [Install Node.js (optional)](#install-nodejs-optional--for-dynamodb-admin-gui)

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

You need **JDK 17** and **Maven 3.9+** installed. Run the checks below to
verify. If anything is missing, see
[Appendix: Installing Prerequisites](#appendix-installing-prerequisites).

> **Important:** Use **JDK 17 LTS only**. JDK 18 and above are **not
> supported** and will cause compilation or runtime errors.

#### Verify JDK

**macOS / Linux / Windows:**

```bash
java -version
```

Expected output (must be version **17**):

```
openjdk version "17.x.x" ...
```

> **Wrong version (18+)?** — JDK 18 and above are not supported. Install JDK 17.
>
> Not installed? → [Install JDK](#install-jdk)

#### Verify Maven

**macOS / Linux / Windows:**

```bash
mvn -version
```

Expected output (version 3.9 or higher):

```
Apache Maven 3.9.x ...
```

> Not installed or wrong version? → [Install Maven](#install-maven)

#### Verify JAVA_HOME

`JAVA_HOME` must point to your JDK installation.

**Windows (PowerShell):**

```powershell
echo $env:JAVA_HOME
```

**macOS / Linux (Bash):**

```bash
echo $JAVA_HOME
```

> If blank or pointing to the wrong path, see [Set JAVA_HOME](#set-java_home)
> in the Appendix.

#### Additional Requirements (per option)

| Option | Extra Tools | Notes |
|--------|-------------|-------|
| **A** — Cosmos DB Emulator | `openssl`, `keytool` | `keytool` ships with JDK. `openssl` ships with macOS/Linux; on Windows install via `winget install ShiningLight.OpenSSL.Light` |
| **B** — DynamoDB Local | _(none)_ | Downloaded in step B1 |
| **C** — Cosmos DB Cloud | Azure CLI | `az login` required. Not installed? → [Install Azure CLI](#install-azure-cli) |
| **D** — DynamoDB Cloud | AWS CLI | `aws configure` required. Not installed? → [Install AWS CLI](#install-aws-cli) |
| _(optional)_ | Node.js 18+ | Only for `dynamodb-admin` GUI |

> **You do NOT need to create any databases, containers, or tables manually.**
> The Risk Platform **auto-provisions** everything on startup via the SDK's
> portable `ensureDatabase` / `ensureContainer` API.

---

## Running the Platform

### Step 0 — Set JAVA_HOME (all terminals)

Run this **once per terminal** before any `mvn` or `java` command.

**Windows (PowerShell):**

```powershell
# Replace the path below with your actual JDK install location
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
```

> **How to find your JDK path on Windows:**
>
> ```powershell
> Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -ErrorAction SilentlyContinue
> Get-ChildItem 'C:\Program Files\Java' -ErrorAction SilentlyContinue
> # Use whichever path contains your jdk-17* folder
> ```

**macOS (Bash / Zsh):**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

**Linux (Bash):**

```bash
# Replace the path below with your actual JDK install location
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk
export PATH="$JAVA_HOME/bin:$PATH"
```

> **How to find your JDK path on Linux:**
>
> ```bash
> update-java-alternatives -l
> # or
> ls /usr/lib/jvm/
> ```

Verify (all platforms):

```bash
java -version
# → openjdk version "17.x.x" ...
```

### Step 1 — Build the SDK

Run from the **repo root** (`hyperscale-db-sdk-for-java/`).

**macOS / Linux / Windows:**

```bash
mvn clean install -DskipTests
```

Expected output ends with:

```
[INFO] BUILD SUCCESS
```

> **If `mvn` is not recognized** — Maven is not on your PATH. See
> [Install Maven](#install-maven) in the Appendix.
>
> **If the build fails with compilation errors** — check that `java -version`
> shows JDK 17. Older versions (16 and below) and newer versions (18 and above)
> are not supported.

---

### Option A: Run against Cosmos DB Emulator

> **No Azure subscription required.** The Cosmos DB Emulator runs entirely on
> your machine. The SDK properties file (`risk-platform-cosmos.properties`)
> uses the emulator's well-known master key — no real Azure access is needed.

#### A0 — Prerequisites

| Requirement | Needed? | Notes |
|-------------|---------|-------|
| JDK 17      | Yes     | Already installed if you completed Step 0 (do **not** use JDK 18+) |
| Maven 3.9+  | Yes     | Already installed if you completed Step 1 |
| `openssl`   | Yes     | For exporting the emulator's TLS certificate (pre-installed on macOS/Linux; on Windows, bundled with Git for Windows or install via `winget install ShiningLight.OpenSSL`) |
| `keytool`   | Yes     | Bundled with JDK — available on PATH if JAVA_HOME is set |
| Azure CLI   | **No**  | Not needed for local emulator |
| Azure subscription | **No** | Not needed for local emulator |

Verify `openssl` and `keytool` are available before proceeding:

**Windows (PowerShell):**

```powershell
openssl version
keytool -help 2>&1 | Select-Object -First 1
```

**macOS / Linux (Bash):**

```bash
openssl version
keytool -help 2>&1 | head -1
```

Both commands should print version/help text. If `openssl` is not found on
Windows, install it:

```powershell
winget install ShiningLight.OpenSSL.Light
# Then restart your terminal so it's on PATH
```

#### A1 — Install and start the emulator

> Run in: **Terminal 1** (this terminal will remain free after the emulator starts)

**Windows (native installer):**

Download and install via PowerShell (one-time):

```powershell
# Download the installer
curl.exe -fSL -o "$env:TEMP\cosmos-emulator.msi" `
  "https://aka.ms/cosmosdb-emulator"

# Run the installer (follow the GUI wizard)
Start-Process msiexec.exe -ArgumentList "/i `"$env:TEMP\cosmos-emulator.msi`"" -Wait
```

Or download manually from
[Azure Cosmos DB Emulator](https://learn.microsoft.com/en-us/azure/cosmos-db/emulator).

Start the emulator:

```powershell
# Start the emulator (runs as a background process / system tray app)
& "$env:ProgramFiles\Azure Cosmos DB Emulator\CosmosDB.Emulator.exe" /NoUI
```

> The emulator runs in the **background** on Windows. You do not need to keep
> this terminal open. Wait 30–60 seconds for it to initialize before proceeding.

**macOS / Linux (Docker):**

```bash
docker run -d --name cosmos-emulator \
  -p 8081:8081 -p 10250-10255:10250-10255 \
  mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
```

> The Docker container runs in the background. Wait 30–60 seconds for it to
> initialize before proceeding.

#### A2 — Verify the emulator is running

> Run in: **Terminal 1** (same terminal as A1)

**Windows (PowerShell):**

```powershell
try {
    $response = Invoke-WebRequest -Uri 'https://localhost:8081/' `
      -SkipCertificateCheck -TimeoutSec 10
    Write-Host "Cosmos emulator: RUNNING (Status: $($response.StatusCode))"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 401 -or $status -eq 404) {
        Write-Host "Cosmos emulator: RUNNING (Status: $status — expected)"
    } else {
        Write-Host "Cosmos emulator: NOT RUNNING — $($_.Exception.Message)"
    }
}
```

**macOS / Linux (Bash):**

```bash
status=$(curl -sk -o /dev/null -w "%{http_code}" https://localhost:8081/)
if [ "$status" = "200" ] || [ "$status" = "401" ] || [ "$status" = "404" ]; then
    echo "Cosmos emulator: RUNNING (Status: $status)"
else
    echo "Cosmos emulator: NOT RUNNING (Status: $status)"
fi
```

| Response | Meaning |
|----------|---------|
| `200`    | Emulator is running |
| `401` or `404` | Emulator is running (expected — requires auth / no content at `/`) |
| `000` or connection refused | Emulator not started — wait 30–60 seconds and retry |

> **If the emulator won't start on Windows**, check if it's already running:
>
> ```powershell
> Get-Process -Name "CosmosDB.Emulator" -ErrorAction SilentlyContinue |
>   Select-Object Id, ProcessName
> ```
>
> To restart it:
>
> ```powershell
> Get-Process -Name "CosmosDB.Emulator" -ErrorAction SilentlyContinue |
>   Stop-Process -Force
> Start-Sleep -Seconds 3
> & "$env:ProgramFiles\Azure Cosmos DB Emulator\CosmosDB.Emulator.exe" /NoUI
> ```

#### A3 — Create the SSL truststore

> Run in: **Terminal 1** (same terminal). Run all commands from the **repo root**.

The Cosmos DB Emulator uses a self-signed TLS certificate that Java does not
trust by default. You need to export the certificate and import it into a
**repo-local truststore** (not the global Java `cacerts`, which requires admin
privileges).

> If `.tools/cacerts-local` already exists from a previous setup, skip to A4.

**Step 1 — Create the tools directory:**

**Windows (PowerShell):**

```powershell
New-Item -ItemType Directory -Force -Path .tools | Out-Null
```

**macOS / Linux (Bash):**

```bash
mkdir -p .tools
```

**Step 2 — Export the emulator's TLS certificate:**

**Windows (PowerShell):**

```powershell
openssl s_client -connect localhost:8081 2>$null |
  openssl x509 -out .tools/cosmos-emulator.cer
```

**macOS / Linux (Bash):**

```bash
openssl s_client -connect localhost:8081 </dev/null 2>/dev/null |
  openssl x509 -out .tools/cosmos-emulator.cer
```

> **If you see `BIO_new_file: fopen(.tools/cosmos-emulator.cer) failed`** —
> the `.tools/` directory doesn't exist. Go back to Step 1.
>
> **If you see PowerShell errors like "not recognized as cmdlet"** — you may
> have copied Bash syntax into PowerShell. Use the **Windows (PowerShell)**
> commands exactly as shown. Do not use `< /dev/null` on Windows.

Verify the certificate was exported:

**Windows (PowerShell):**

```powershell
if (Test-Path .tools/cosmos-emulator.cer) {
    Write-Host "Certificate exported successfully"
    Get-Item .tools/cosmos-emulator.cer | Select-Object Name, Length
} else {
    Write-Host "ERROR: Certificate file not found"
}
```

**macOS / Linux (Bash):**

```bash
ls -la .tools/cosmos-emulator.cer
```

**Step 3 — Import the certificate into a local Java truststore:**

**Windows (PowerShell):**

```powershell
keytool -importcert -alias cosmosemulator `
  -file .tools/cosmos-emulator.cer `
  -keystore .tools/cacerts-local `
  -storepass changeit -noprompt
```

**macOS / Linux (Bash):**

```bash
keytool -importcert -alias cosmosemulator \
  -file .tools/cosmos-emulator.cer \
  -keystore .tools/cacerts-local \
  -storepass changeit -noprompt
```

> **If you see `keytool error: Certificate not imported, alias already exists`**
> — the alias `cosmosemulator` already exists in the truststore. Delete it first
> and re-run the import:
>
> **Windows (PowerShell):**
>
> ```powershell
> keytool -delete -alias cosmosemulator `
>   -keystore .tools/cacerts-local -storepass changeit
> ```
>
> **macOS / Linux (Bash):**
>
> ```bash
> keytool -delete -alias cosmosemulator \
>   -keystore .tools/cacerts-local -storepass changeit
> ```
>
> **If you see `keytool error: java.io.FileNotFoundException`** — you are
> running the command from the wrong directory. Make sure you are at the
> **repo root** (`hyperscale-db-sdk-for-java/`), or use absolute paths.
>
> **If you see `Access denied`** — you may be trying to write to the global
> Java `cacerts` file instead of the local truststore. Make sure you are using
> `-keystore .tools/cacerts-local`, not `-cacerts`.
>
> **If you see a warning about `SHA1withRSA`** — this is expected for the
> emulator's self-signed certificate. It does not block the import. Safe to
> ignore for local development.

**Step 4 — Verify the truststore:**

```bash
keytool -list -keystore .tools/cacerts-local -storepass changeit -alias cosmosemulator
```

Expected output includes `trustedCertEntry`. This command works the same on
all platforms.

#### A4 — Launch the Risk Platform (Cosmos DB)

> Run in: **Terminal 1** (same terminal), from the **repo root**.

**Windows (PowerShell):**

```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit"
```

**macOS / Linux (Bash):**

```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-cosmos.properties \
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" \
  -Djavax.net.ssl.trustStorePassword=changeit
```

Wait for the startup log to show:

```
═══════════════════════════════════════════════════
  Risk Analysis Platform started on port 8090
  Provider: cosmos
  Dashboard: http://localhost:8090
═══════════════════════════════════════════════════
```

> **If you see `Address already in use: bind` on port 8090** — a previous
> instance is still running. Kill it and retry:
>
> **Windows (PowerShell):**
>
> ```powershell
> Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue |
>   ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
> ```
>
> **macOS / Linux (Bash):**
>
> ```bash
> lsof -ti:8090 | xargs kill -9
> ```
>
> **If you see `PKIX path building failed`** — the truststore is missing or
> the path is wrong. Make sure you run the command from the **repo root** so
> `$PWD/.tools/cacerts-local` resolves correctly. Re-create the truststore
> if needed (go back to step A3).
>
> **If you see `Connection refused` on port 8081** — the Cosmos DB Emulator
> is not running. Go back to step A1 and start it.

#### A5 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

#### A6 — Verify via REST API

> Run in: **Terminal 2** (open a **new terminal**).

**Windows (PowerShell):**

```powershell
# List tenants (should return 3)
(Invoke-WebRequest http://localhost:8090/api/tenants -UseBasicParsing).Content |
  ConvertFrom-Json | Measure-Object | Select-Object -Expand Count

# Get dashboard for Acme Capital
Invoke-RestMethod http://localhost:8090/api/dashboard?tenant=acme-capital

# Check provider (should show "cosmos")
Invoke-RestMethod http://localhost:8090/api/provider
```

**macOS / Linux (Bash):**

```bash
# List tenants (should return 3)
curl -s http://localhost:8090/api/tenants | python3 -m json.tool

# Get dashboard for Acme Capital
curl -s http://localhost:8090/api/dashboard?tenant=acme-capital | python3 -m json.tool

# Check provider (should show "cosmos")
curl -s http://localhost:8090/api/provider | python3 -m json.tool
```

#### A7 — Stop the app

Press `Ctrl+C` in **Terminal 1** (the terminal running Maven).

To stop the Cosmos DB Emulator itself:

**Windows (PowerShell):**

```powershell
& "$env:ProgramFiles\Azure Cosmos DB Emulator\CosmosDB.Emulator.exe" /Shutdown
```

**macOS / Linux (Docker):**

```bash
docker stop cosmos-emulator && docker rm cosmos-emulator
```

---

### Option B: Run against DynamoDB Local

> **No AWS account, AWS CLI, or IAM credentials required.** DynamoDB Local is a
> standalone Java application that runs entirely on your machine. The SDK
> properties file (`risk-platform-dynamo.properties`) uses fake static
> credentials — no real AWS access is needed.

#### B0 — Prerequisites

| Requirement | Needed? | Notes |
|-------------|---------|-------|
| JDK 17      | Yes     | Already installed if you completed Step 0 (do **not** use JDK 18+) |
| Maven 3.9+  | Yes     | Already installed if you completed Step 1 |
| AWS CLI      | **No**  | Not needed for local emulator |
| AWS account | **No**  | Not needed for local emulator |
| Node.js 18+ | Optional | Only for `dynamodb-admin` GUI (Step B4) |

#### B1 — Download and extract DynamoDB Local

DynamoDB Local is **not bundled** in the repo. Download and extract it into
`.tools/dynamodb-local/` from the repo root.

**Windows (PowerShell):**

```powershell
New-Item -ItemType Directory -Force -Path .tools\dynamodb-local | Out-Null
curl.exe -fSL -o .tools\dynamodb-local\dynamodb_local_latest.zip `
  "https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip"
Expand-Archive -Path .tools\dynamodb-local\dynamodb_local_latest.zip `
  -DestinationPath .tools\dynamodb-local -Force
Remove-Item .tools\dynamodb-local\dynamodb_local_latest.zip
```

**macOS / Linux (Bash):**

```bash
mkdir -p .tools/dynamodb-local
curl -fSL -o .tools/dynamodb-local/dynamodb_local_latest.zip \
  "https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip"
unzip -o .tools/dynamodb-local/dynamodb_local_latest.zip \
  -d .tools/dynamodb-local
rm .tools/dynamodb-local/dynamodb_local_latest.zip
```

Verify the extraction:

```
.tools/dynamodb-local/
├── DynamoDBLocal.jar
├── DynamoDBLocal_lib/
├── LICENSE.txt
├── README.txt
└── THIRD-PARTY-LICENSES.txt
```

> This step is one-time. Once extracted, DynamoDB Local persists across
> sessions. The `.tools/` directory is git-ignored.

#### B2 — Start DynamoDB Local

Open a **new terminal** and run from the **repo root**:

**Windows (PowerShell):**

```powershell
cd .tools\dynamodb-local
java "-Djava.library.path=./DynamoDBLocal_lib" -jar DynamoDBLocal.jar -sharedDb -inMemory
```

**macOS / Linux (Bash):**

```bash
cd .tools/dynamodb-local
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -sharedDb -inMemory
```

> **Keep this terminal open.** DynamoDB Local runs in the foreground.
>
> | Flag | Purpose |
> |------|---------|
> | `-sharedDb` | All clients share a single database file |
> | `-inMemory` | Data is lost on restart (clean slate each time) |

> **If you see `Address already in use: bind`**, another process is already
> occupying port 8000 (e.g., a previous DynamoDB Local instance). Find and
> stop it, then retry:
>
> **Windows (PowerShell):**
>
> ```powershell
> Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue |
>   ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
> ```
>
> **macOS / Linux (Bash):**
>
> ```bash
> lsof -ti:8000 | xargs kill -9
> ```

#### B3 — Verify DynamoDB Local is running

In a **second terminal**:

**Windows (PowerShell):**

```powershell
try {
    Invoke-WebRequest -Uri 'http://localhost:8000' -UseBasicParsing -TimeoutSec 5
} catch {
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)"
}
```

**macOS / Linux (Bash):**

```bash
curl -s -o /dev/null -w "Status: %{http_code}\n" http://localhost:8000/
```

A `400` response is expected — DynamoDB Local only speaks its wire protocol.

| Response | Meaning |
|----------|---------|
| `400`    | DynamoDB Local is running |
| Connection refused | DynamoDB Local is not running — check the terminal from B2 |

#### B4 — Start DynamoDB Admin GUI (optional)

[dynamodb-admin](https://github.com/aaronshaf/dynamodb-admin) is a lightweight
Node.js web UI for browsing DynamoDB Local tables and items. Requires **Node.js
18+** and npm.

Install (one-time, **all platforms**):

```bash
npm install -g dynamodb-admin
```

Start in a **new terminal**:

**Windows (PowerShell):**

```powershell
$env:DYNAMO_ENDPOINT = 'http://localhost:8000'
dynamodb-admin
```

**macOS / Linux (Bash):**

```bash
DYNAMO_ENDPOINT=http://localhost:8000 dynamodb-admin
```

Open **http://localhost:8001** in your browser. You'll see all tables and items
created by the Risk Platform — useful for inspecting the tenant-per-database
isolation model, partition keys, and seeded data.

> **Tip**: Keep DynamoDB Local, `dynamodb-admin`, and the Risk Platform each in
> their own terminal.

#### B5 — Launch the Risk Platform (DynamoDB)

In the **second terminal**, from the **repo root**:

**Windows (PowerShell):**

```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-dynamo.properties"
```

**macOS / Linux (Bash):**

```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-dynamo.properties
```

Wait for the startup log to show:

```
═══════════════════════════════════════════════════
  Risk Analysis Platform started on port 8090
  Provider: dynamo
  Dashboard: http://localhost:8090
═══════════════════════════════════════════════════
```

#### B6 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

#### B7 — Verify via REST API

**Windows (PowerShell):**

```powershell
# List tenants (should return 3)
(Invoke-WebRequest http://localhost:8090/api/tenants -UseBasicParsing).Content |
  ConvertFrom-Json | Measure-Object | Select-Object -Expand Count

# Get dashboard for Acme Capital
Invoke-RestMethod http://localhost:8090/api/dashboard?tenant=acme-capital

# Check provider (should show "dynamo")
Invoke-RestMethod http://localhost:8090/api/provider
```

**macOS / Linux (Bash):**

```bash
# List tenants (should return 3)
curl -s http://localhost:8090/api/tenants | python3 -m json.tool

# Get dashboard for Acme Capital
curl -s http://localhost:8090/api/dashboard?tenant=acme-capital | python3 -m json.tool

# Check provider (should show "dynamo")
curl -s http://localhost:8090/api/provider | python3 -m json.tool
```

#### B8 — Stop the app

Press `Ctrl+C` in the Maven terminal, `dynamodb-admin` terminal (if running),
and the DynamoDB Local terminal.

---

### Option C: Run against Cosmos DB (Azure Cloud)

This option connects to a **real Azure Cosmos DB account** using
**Microsoft Entra ID** (Azure AD / Entra) authentication via `DefaultAzureCredential`.
**No master key is needed** — all authentication uses your Azure identity.

> **What you need:**
> - An existing Azure Cosmos DB (NoSQL API) account
> - Azure CLI installed and on your PATH — not installed? → [Install Azure CLI](#install-azure-cli)
> - Your identity signed in with `az login`
> - The **Cosmos DB Built-in Data Contributor** data-plane RBAC role assigned to your identity
> - **Contributor** or **Owner** ARM role on the resource group (for auto-provisioning databases/containers)
>
> **First time?** See [Azure Setup for Option C](#azure-setup-for-option-c) for
> step-by-step instructions on signing in, collecting credential values, and
> assigning the required RBAC role.

---

#### C0 — Create the properties file (one-time setup)

The cloud properties file contains your subscription ID, tenant ID, and endpoint.
**It is git-ignored and must never be committed.**

**macOS / Linux:**
```bash
cp hyperscaledb-samples/src/main/resources/risk-platform-cosmos-cloud.properties.template \
   hyperscaledb-samples/src/main/resources/risk-platform-cosmos-cloud.properties
```

**Windows (PowerShell):**
```powershell
Copy-Item hyperscaledb-samples\src\main\resources\risk-platform-cosmos-cloud.properties.template `
          hyperscaledb-samples\src\main\resources\risk-platform-cosmos-cloud.properties
```

Open the file and fill in your values:

```ini
hyperscaledb.provider=cosmos
hyperscaledb.connection.endpoint=https://<YOUR-COSMOS-ACCOUNT-NAME>.documents.azure.com:443/
hyperscaledb.connection.connectionMode=direct
hyperscaledb.connection.subscriptionId=<YOUR-SUBSCRIPTION-ID>
hyperscaledb.connection.resourceGroupName=<YOUR-RESOURCE-GROUP>
hyperscaledb.connection.tenantId=<YOUR-TENANT-ID>
```

> Not sure where to find these values? → [Sign In and Collect Credential Values](#sign-in-and-collect-credential-values)

---

#### C1 — Configure Azure credentials (one-time setup)

**macOS / Linux / Windows:**
```bash
az login
```

> - First time with Azure CLI? → [Sign In and Collect Credential Values](#sign-in-and-collect-credential-values)
> - RBAC role not yet assigned? → [Grant the Cosmos DB RBAC Role](#grant-the-cosmos-db-data-plane-rbac-role)
> - Credentials already configured? Skip to C2.

---

#### C2 — Build the project

**macOS / Linux / Windows:**
```bash
mvn clean install -DskipTests
```

---

#### C3 — Launch the Risk Platform (Cosmos DB Cloud)

Run from the **repo root**. No truststore needed — Azure Cosmos DB uses a
publicly trusted TLS certificate.

**macOS / Linux:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-cosmos-cloud.properties
```

**Windows (PowerShell):**
```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos-cloud.properties"
```

On first run, the app auto-provisions all databases and containers via the
Azure Resource Manager API, then seeds demo data. This may take **1–2 minutes**
on first run. Subsequent runs skip existing resources and start in seconds.

Expected output:
```
  Starting Risk Analysis Platform...
  Loaded config: risk-platform-cosmos-cloud.properties
  Provisioning resources for Azure Cosmos DB...
    Database: riskplatform-admin
      Container: tenants
    Database: acme-capital-risk-db
      Container: portfolios
      ...
  Resource provisioning complete.
  Seeding demo data...
  Demo data seeded successfully.

+----------------------------------------------------------+
|   RISK ANALYSIS PLATFORM -- Multi-Tenant Demo            |
+----------------------------------------------------------+
|   Provider:  Azure Cosmos DB                            |
|   Dashboard: http://localhost:8090                      |
|   API:       http://localhost:8090/api                  |
+----------------------------------------------------------+
```

---

#### C4 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

---

#### C5 — Stop the app

Press `Ctrl+C` in the terminal running Maven.

If the port is still in use on the next run:

**macOS / Linux:**
```bash
lsof -ti:8090 | xargs kill -9
```

**Windows (PowerShell):**
```powershell
Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

---

#### C6 — Clean up Cosmos DB resources (optional)

> **Note:** Cosmos DB databases persist until explicitly deleted. Run this when
> you no longer need the demo data and want to remove all provisioned databases.

A cleanup script is provided in `hyperscaledb-samples/scripts/`. It reads your
account name and resource group directly from the properties file — no manual
editing required.

**macOS / Linux:**
```bash
# First time only — make the script executable
chmod +x hyperscaledb-samples/scripts/cleanup-cosmos.sh

./hyperscaledb-samples/scripts/cleanup-cosmos.sh
```

**Windows (PowerShell):**
```powershell
.\hyperscaledb-samples\scripts\cleanup-cosmos.ps1
```

To override the account or resource group without editing any file:

**macOS / Linux:**
```bash
COSMOS_ACCOUNT=my-account RESOURCE_GROUP=my-rg \
  ./hyperscaledb-samples/scripts/cleanup-cosmos.sh
```

**Windows (PowerShell):**
```powershell
.\hyperscaledb-samples\scripts\cleanup-cosmos.ps1 -CosmosAccount my-account -ResourceGroup my-rg
```

Example output:
```
Account        : allekim-test-sm
Resource Group : my-resource-group

The following Cosmos DB databases will be deleted:
  - riskplatform-admin
  - acme-capital-risk-db
  - vanguard-partners-risk-db
  - summit-wealth-risk-db
  - _shared-risk-db

Proceed? [y/N] y

  Deleted : riskplatform-admin
  Deleted : acme-capital-risk-db
  Deleted : vanguard-partners-risk-db
  Deleted : summit-wealth-risk-db
  Deleted : _shared-risk-db

All databases deleted successfully.
```

---

#### Troubleshooting Option C

| Error | Cause | Fix |
|-------|-------|-----|
| `InvalidAuthenticationTokenTenant` (401) | CLI token issued for a different tenant than the subscription's tenant | Set `hyperscaledb.connection.tenantId` — see [Sign In and Collect Credential Values](#sign-in-and-collect-credential-values) |
| `Request blocked by Auth … cannot be authorized by AAD token in data plane` (403, subStatus 5300) | Cosmos DB Built-in Data Contributor role not assigned | See [Grant the Cosmos DB RBAC Role](#grant-the-cosmos-db-data-plane-rbac-role) |
| `DefaultAzureCredential authentication failed` | Not signed in to Azure CLI | Run `az login`, then verify with `az account show` |
| `The subscription … must match the tenant` (401) | Multiple Azure tenants; token issued for wrong tenant | Run `az account list -o table`, then `az account set --subscription <ID>` |
| `Address already in use` on port 8090 | A previous instance is still running | Run the kill command in Step C5 above |
| App exits silently after provisioning | First-run ARM deployments timed out | Re-run the app — it checks resource existence first and skips already-created resources |

---

### Option D: Run against DynamoDB (AWS Cloud)

This option connects to the **real Amazon DynamoDB service** using the
AWS default credential provider chain.
**No access key or secret is stored in the properties file** — credentials are
picked up automatically from your AWS CLI configuration or IAM role.

> **What you need:**
> - An AWS account with permissions to create and manage DynamoDB tables
> - AWS CLI installed and on your PATH — not installed? → [Install AWS CLI](#install-aws-cli)
> - Credentials configured via `aws configure` (or IAM role / environment variables)
> - **AmazonDynamoDBFullAccess** policy (or equivalent) attached to your IAM identity
>
> **First time?** See [AWS Setup for Option D](#aws-setup-for-option-d) for
> step-by-step instructions on creating an IAM user, access keys, and
> configuring credentials.

---

#### D0 — Create the properties file (one-time setup)

The cloud properties file contains your AWS region.
**It is git-ignored and must never be committed.**

**macOS / Linux:**
```bash
cp hyperscaledb-samples/src/main/resources/risk-platform-dynamo-cloud.properties.template \
   hyperscaledb-samples/src/main/resources/risk-platform-dynamo-cloud.properties
```

**Windows (PowerShell):**
```powershell
Copy-Item hyperscaledb-samples\src\main\resources\risk-platform-dynamo-cloud.properties.template `
          hyperscaledb-samples\src\main\resources\risk-platform-dynamo-cloud.properties
```

Open the file and set your AWS region:

```ini
hyperscaledb.provider=dynamo
hyperscaledb.connection.region=us-east-1
```

> Not sure which region to use? → [AWS Regions](#aws-regions)

---

#### D1 — Configure AWS credentials (one-time setup)

**macOS / Linux / Windows:**
```bash
aws configure
```

| Prompt | Value |
|--------|-------|
| AWS Access Key ID | Your IAM access key ID |
| AWS Secret Access Key | Your IAM secret access key |
| Default region name | e.g. `us-east-1` — see [AWS Regions](#aws-regions) |
| Default output format | `json` |

> - No access key yet? → [Create an IAM User and Access Key](#create-an-iam-user-and-access-key)
> - Using corporate SSO? → [AWS SSO Alternative](#aws-sso-alternative-no-long-lived-keys)
> - Credentials already configured? Skip to D2.

---

#### D2 — Build the project

**macOS / Linux / Windows:**
```bash
mvn clean install -DskipTests
```

---

#### D3 — Launch the Risk Platform (DynamoDB Cloud)

Run from the **repo root**.

**macOS / Linux:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-dynamo-cloud.properties
```

**Windows (PowerShell):**
```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-dynamo-cloud.properties"
```

On first run, the app auto-provisions all DynamoDB tables then seeds demo data.
Subsequent runs skip existing tables and start immediately.

Expected output:
```
  Starting Risk Analysis Platform...
  Loaded config: risk-platform-dynamo-cloud.properties
  Provisioning resources for Amazon DynamoDB...
    Table: acme-capital-risk-db__portfolios
    Table: acme-capital-risk-db__positions
    Table: acme-capital-risk-db__risk_metrics
    Table: acme-capital-risk-db__alerts
    ...
  Resource provisioning complete.
  Seeding demo data...
  Demo data seeded successfully.

+----------------------------------------------------------+
|   RISK ANALYSIS PLATFORM -- Multi-Tenant Demo            |
+----------------------------------------------------------+
|   Provider:  Amazon DynamoDB                             |
|   Dashboard: http://localhost:8090                       |
|   API:       http://localhost:8090/api                   |
+----------------------------------------------------------+
```

---

#### D4 — Open the dashboard

Navigate to **http://localhost:8090** in your browser.

---

#### D5 — Stop the app

Press `Ctrl+C` in the terminal running Maven.

If the port is still in use on the next run:

**macOS / Linux:**
```bash
lsof -ti:8090 | xargs kill -9
```

**Windows (PowerShell):**
```powershell
Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

---

#### D6 — Clean up AWS resources (avoid ongoing charges)

> **Cost note:** DynamoDB charges for storage and on-demand read/write capacity.
> Delete the tables after testing to avoid ongoing charges.

A cleanup script is provided in `hyperscaledb-samples/scripts/`. It reads your
region automatically from `aws configure` (or prompts you), lists what it will
delete, and asks for confirmation before proceeding.

**macOS / Linux:**
```bash
# First time only — make the script executable
chmod +x hyperscaledb-samples/scripts/cleanup-dynamo.sh

./hyperscaledb-samples/scripts/cleanup-dynamo.sh
```

**Windows (PowerShell):**
```powershell
.\hyperscaledb-samples\scripts\cleanup-dynamo.ps1
```

To override the region without editing any file, pass it inline:

**macOS / Linux:**
```bash
AWS_REGION=eu-west-1 ./hyperscaledb-samples/scripts/cleanup-dynamo.sh
```

> `chmod +x` only needs to be run once. After that, the script is permanently executable.

**Windows (PowerShell):**
```powershell
.\hyperscaledb-samples\scripts\cleanup-dynamo.ps1 -Region eu-west-1
```

Example output:
```
Region : us-east-1

The following tables will be deleted in region 'us-east-1':
  - acme-capital-risk-db__portfolios
  - acme-capital-risk-db__positions
  ...
  - _shared-risk-db__market_data

Proceed? [y/N] y

  Deleted : acme-capital-risk-db__portfolios
  Deleted : acme-capital-risk-db__positions
  ...
  Deleted : _shared-risk-db__market_data

All tables deleted successfully.
```

---

#### Troubleshooting Option D

| Error | Cause | Fix |
|-------|-------|-----|
| `Unable to load credentials from any provider in the chain` | No credentials configured | Run `aws configure` (see Step D1) or set `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` environment variables |
| `ExpiredTokenException` | Temporary credentials (SSO / assumed role) have expired | Run `aws sso login --profile <profile>` — see [AWS SSO Alternative](#aws-sso-alternative-no-long-lived-keys) |
| `AccessDeniedException` on `CreateTable` | IAM identity lacks DynamoDB permissions | See [Verify IAM Permissions](#verify-iam-permissions) |
| `ResourceNotFoundException` | Table does not exist when reading | Re-run the app — auto-provisioning will recreate missing tables |
| `The security token included in the request is invalid` | Wrong region or credentials | Run `aws sts get-caller-identity` to verify account; check region — see [AWS Regions](#aws-regions) |
| `Address already in use` on port 8090 | A previous instance is still running | Run the kill command in Step D5 above |

---

### Switching Between Providers

The application code is **identical** across all providers — only the
properties file and a few JVM arguments change. Stop the app (`Ctrl+C`),
then re-run with the parameters for the desired provider.

#### Run command (all providers)

**macOS / Linux:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=<CONFIG_FILE> \
  [<EXTRA_ARGS>]
```

**Windows (PowerShell):**
```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=<CONFIG_FILE>" `
  [<EXTRA_ARGS>]
```

Replace `<CONFIG_FILE>` and `<EXTRA_ARGS>` using the table below.

---

#### Provider parameters

| Option | Provider | `<CONFIG_FILE>` | `<EXTRA_ARGS>` | Prerequisites |
|--------|----------|-----------------|----------------|---------------|
| **A** | Cosmos DB Emulator | `risk-platform-cosmos.properties` | `-Djavax.net.ssl.trustStore=.tools/cacerts-local`<br>`-Djavax.net.ssl.trustStorePassword=changeit` | Emulator running ([Step A1](#a1--install-and-start-the-emulator)), truststore created ([Step A3](#a3--create-the-ssl-truststore)) |
| **B** | DynamoDB Local | `risk-platform-dynamo.properties` | *(none)* | DynamoDB Local running ([Step B2](#b2--start-dynamodb-local)) |
| **C** | Cosmos DB Cloud | `risk-platform-cosmos-cloud.properties` | *(none)* | `az login` done ([Step C1](#c1--configure-azure-credentials-one-time-setup)), RBAC role assigned ([Azure Setup](#grant-the-cosmos-db-data-plane-rbac-role)) |
| **D** | DynamoDB Cloud | `risk-platform-dynamo-cloud.properties` | *(none)* | `aws configure` done ([Step D1](#d1--configure-aws-credentials-one-time-setup)) |

> **Custom port?** Add `-Drisk.port=<PORT>` to `<EXTRA_ARGS>` to run on any
> port instead of the default `8090`. See [Changing the Port](#changing-the-port).

---

#### Full examples per provider

**Option A — Cosmos DB Emulator**

**macOS / Linux:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-cosmos.properties \
  -Djavax.net.ssl.trustStore=.tools/cacerts-local \
  -Djavax.net.ssl.trustStorePassword=changeit
```

**Windows (PowerShell):**
```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit"
```

---

**Option B — DynamoDB Local**

**macOS / Linux / Windows:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-dynamo.properties
```

---

**Option C — Cosmos DB Cloud**

**macOS / Linux / Windows:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-cosmos-cloud.properties
```

---

**Option D — DynamoDB Cloud**

**macOS / Linux / Windows:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-dynamo-cloud.properties
```

---

### Running Both Providers Side by Side

Run **two Risk Platform instances simultaneously** on different ports to compare
Cosmos DB and DynamoDB side by side.

#### What you need

| Terminal | Role | Port | Prerequisites |
|----------|------|------|---------------|
| 1 | Cosmos DB Emulator | 8081 | [Step A1](#a1--install-and-start-the-emulator), [Step A3](#a3--create-the-ssl-truststore) |
| 2 | DynamoDB Local | 8000 | [Step B1](#b1--download-and-extract-dynamodb-local) |
| 3 (optional) | DynamoDB Admin GUI | 8001 | Node.js 18+ ([Install Node.js](#install-nodejs-optional--for-dynamodb-admin-gui)) |
| 4 | Risk Platform — Cosmos DB | 8090 | Terminals 1 & 3 running |
| 5 | Risk Platform — DynamoDB | 8091 | Terminal 2 running |

---

#### Terminal 1 — Cosmos DB Emulator

Start as described in [Step A1](#a1--install-and-start-the-emulator). Make sure
the tray icon shows **Running** before continuing.

---

#### Terminal 2 — DynamoDB Local

**macOS / Linux:**
```bash
cd .tools/dynamodb-local
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -sharedDb -inMemory
```

**Windows (PowerShell):**
```powershell
cd .tools\dynamodb-local
java "-Djava.library.path=./DynamoDBLocal_lib" -jar DynamoDBLocal.jar -sharedDb -inMemory
```

> Not downloaded yet? See [Step B1](#b1--download-and-extract-dynamodb-local).

---

#### Terminal 3 — DynamoDB Admin GUI (optional)

**macOS / Linux:**
```bash
export DYNAMO_ENDPOINT=http://localhost:8000
dynamodb-admin
```

**Windows (PowerShell):**
```powershell
$env:DYNAMO_ENDPOINT = 'http://localhost:8000'
dynamodb-admin
```

Open **http://localhost:8001** to browse DynamoDB tables and items.

---

#### Terminal 4 — Risk Platform on Cosmos DB Emulator (port 8090)

**macOS / Linux:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-cosmos.properties \
  -Djavax.net.ssl.trustStore=.tools/cacerts-local \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -Drisk.port=8090
```

**Windows (PowerShell):**
```powershell
mvn -pl hyperscaledb-samples exec:java `
  "-Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp" `
  "-Drisk.config=risk-platform-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit" `
  "-Drisk.port=8090"
```

> Truststore not created yet? See [Step A3](#a3--create-the-ssl-truststore).

---

#### Terminal 5 — Risk Platform on DynamoDB Local (port 8091)

**macOS / Linux / Windows:**
```bash
mvn -pl hyperscaledb-samples exec:java \
  -Dexec.mainClass=com.hyperscaledb.samples.riskplatform.RiskPlatformApp \
  -Drisk.config=risk-platform-dynamo.properties \
  -Drisk.port=8091
```

---

#### Open the dashboards

| Browser window | URL | Provider |
|----------------|-----|----------|
| Left | http://localhost:8090 | Azure Cosmos DB Emulator |
| Right | http://localhost:8091 | Amazon DynamoDB Local |
| Optional | http://localhost:8001 | DynamoDB Admin GUI |

Both dashboards show the same tenants, portfolios, and risk data — the only
difference is the underlying database engine. The **Provider** indicator in the
dashboard header shows which one you're looking at.

### Configuration Files

| File | Provider | Target | Auth | Committed? |
|------|----------|--------|------|------------|
| `risk-platform-cosmos.properties` | Azure Cosmos DB | `https://localhost:8081` (emulator) | Master key | ✅ Yes |
| `risk-platform-cosmos-cloud.properties` | Azure Cosmos DB | Azure cloud endpoint | DefaultAzureCredential (Entra ID) | ❌ **No — git-ignored** |
| `risk-platform-cosmos-cloud.properties.template` | Azure Cosmos DB | *(fill in your values)* | DefaultAzureCredential (Entra ID) | ✅ Yes (no secrets) |
| `risk-platform-dynamo.properties` | Amazon DynamoDB | `http://localhost:8000` (local) | Fake static credentials | ✅ Yes |
| `risk-platform-dynamo-cloud.properties` | Amazon DynamoDB | AWS DynamoDB service | Default AWS credential chain | ❌ **No — git-ignored** |
| `risk-platform-dynamo-cloud.properties.template` | Amazon DynamoDB | *(fill in your values)* | Default AWS credential chain | ✅ Yes (no secrets) |

> **Cloud properties files are git-ignored.** Copy the `.template` file,
> fill in your values, and keep it local. See Step C0 / D0 for instructions.

### Changing the Port

Override the default port (8090) with:

```powershell
"-Drisk.port=9090"
```

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| `Address already in use: bind` on port 8090 | A previous instance is still running | **macOS/Linux:** `lsof -ti:8090 \| xargs kill -9` / **Windows:** `Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue \| ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }` |
| `PKIX path building failed` / SSL errors (Cosmos) | Truststore missing or wrong path | Re-create the truststore (see Step A3) and ensure `$PWD` resolves correctly — run from repo root |
| `Connection refused` on port 8081 (Cosmos) | Emulator not started | Launch the Cosmos DB Emulator from Start Menu and wait for the tray icon |
| `Connection refused` on port 8000 (DynamoDB) | DynamoDB Local not started | Start it in a separate terminal (see Step B1) |
| `UnsatisfiedLinkError` (DynamoDB Local) | Wrong `-Djava.library.path` | Use `-Djava.library.path=./DynamoDBLocal_lib` (not `.`) |
| `BUILD FAILURE` on `mvn install` | JAVA_HOME not set | Run `$env:JAVA_HOME = '...'` and `$env:PATH = ...` first |
| `ClassNotFoundException` when running the app | SDK not built | Run `mvn clean install -DskipTests` from repo root first |
| `InvalidAuthenticationTokenTenant` (401) | CLI token issued for a different tenant than the subscription's tenant | Set `hyperscaledb.connection.tenantId` in the properties file — run `az account show --query tenantId -o tsv` to get the correct value |
| `Request blocked by Auth … cannot be authorized by AAD token in data plane` (403 subStatus 5300) | Cosmos DB Built-in Data Contributor role not assigned | Run `az cosmosdb sql role assignment create` (see Step C2) |
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
hyperscaledb-samples/
├── scripts/
│   ├── cleanup-cosmos.sh          # Cosmos DB database cleanup script (macOS / Linux)
│   ├── cleanup-cosmos.ps1         # Cosmos DB database cleanup script (Windows)
│   ├── cleanup-dynamo.sh          # DynamoDB table cleanup script (macOS / Linux)
│   └── cleanup-dynamo.ps1         # DynamoDB table cleanup script (Windows)
└── src/main/
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

---

## Appendix: Installing Prerequisites

Detailed installation instructions for each required tool. Skip any tool you
already have installed (verified in [Prerequisites](#prerequisites)).

### Install JDK

Install **Eclipse Adoptium Temurin JDK 17 LTS** (recommended).

> **Do not install JDK 18 or above.** This project requires **JDK 17** exactly.
> Higher versions are not supported and will cause build or runtime failures.

**Windows (PowerShell):**

```powershell
# Install via winget (one command)
winget install EclipseAdoptium.Temurin.17.JDK
```

Alternative — download the MSI installer manually from
[adoptium.net](https://adoptium.net/).

**macOS (Homebrew):**

```bash
brew install --cask temurin@17
```

**Linux — Debian / Ubuntu (apt):**

```bash
sudo apt update
sudo apt install -y temurin-17-jdk
```

> If the `temurin-17-jdk` package is not found, add the Adoptium APT repo first:
>
> ```bash
> sudo mkdir -p /etc/apt/keyrings
> wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
>   | sudo tee /etc/apt/keyrings/adoptium.asc >/dev/null
> echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] \
>   https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" \
>   | sudo tee /etc/apt/sources.list.d/adoptium.list
> sudo apt update
> sudo apt install -y temurin-17-jdk
> ```

**Linux — Fedora / RHEL (dnf):**

```bash
sudo dnf install -y temurin-17-jdk
```

After installing, verify:

```bash
java -version
# → openjdk version "17.x.x" ...
# If this shows 18+ or 16-, uninstall and install JDK 17 instead
```

### Set JAVA_HOME

Set `JAVA_HOME` **permanently** so you don't need to set it every terminal.

**Windows (PowerShell — permanent, user-level):**

```powershell
# Find the path first
Get-ChildItem 'C:\Program Files\Eclipse Adoptium' | Select-Object Name

# Set permanently (replace path with your actual folder name)
[System.Environment]::SetEnvironmentVariable(
  'JAVA_HOME',
  'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot',
  'User'
)

# Restart your terminal, then verify:
echo $env:JAVA_HOME
```

**macOS (Zsh — permanent):**

```bash
# Add to ~/.zshrc (runs on every new terminal)
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc
source ~/.zshrc

# Verify:
echo $JAVA_HOME
```

**Linux (Bash — permanent):**

```bash
# Add to ~/.bashrc
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk' >> ~/.bashrc
source ~/.bashrc

# Verify:
echo $JAVA_HOME
```

### Install Maven

**Windows (PowerShell):**

```powershell
# Install via winget
winget install Apache.Maven
```

Alternative — manual install:

```powershell
# Download Maven 3.9.9
curl.exe -fSL -o "$env:TEMP\maven.zip" `
  "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip"

# Extract to C:\tools
Expand-Archive -Path "$env:TEMP\maven.zip" -DestinationPath 'C:\tools' -Force

# Set PATH permanently (user-level)
$currentPath = [System.Environment]::GetEnvironmentVariable('PATH', 'User')
[System.Environment]::SetEnvironmentVariable(
  'PATH',
  "C:\tools\apache-maven-3.9.9\bin;$currentPath",
  'User'
)

# Restart your terminal, then verify:
mvn -version
```

**macOS (Homebrew):**

```bash
brew install maven
```

**Linux — Debian / Ubuntu (apt):**

```bash
sudo apt update
sudo apt install -y maven
```

**Linux — Fedora / RHEL (dnf):**

```bash
sudo dnf install -y maven
```

After installing, verify:

```bash
mvn -version
# → Apache Maven 3.9.x ...
```

### Install Azure CLI

Required for **Option C** (Cosmos DB Cloud). The Azure CLI lets you authenticate
with `az login` and manage Azure resources from the terminal.

**Windows (PowerShell):**

```powershell
# Option 1 — winget (recommended, one command)
winget install Microsoft.AzureCLI

# Option 2 — MSI installer (download and run the wizard)
# Download from: https://aka.ms/installazurecliwindows
Start-Process "https://aka.ms/installazurecliwindows"
```

> Restart your terminal after installing so `az` is on your PATH.

**macOS (Homebrew):**

```bash
brew update && brew install azure-cli
```

**macOS (direct install script — no Homebrew required):**

```bash
curl -L https://aka.ms/InstallAzureCli | bash
```

> Restart your terminal or run `exec -l $SHELL` after installing.

**Linux — Debian / Ubuntu (apt):**

```bash
# Import the Microsoft signing key
curl -sLS https://packages.microsoft.com/keys/microsoft.asc |
  gpg --dearmor | sudo tee /etc/apt/keyrings/microsoft.gpg > /dev/null
sudo chmod go+r /etc/apt/keyrings/microsoft.gpg

# Add the Azure CLI repo
AZ_DIST=$(lsb_release -cs)
echo "Types: deb
URIs: https://packages.microsoft.com/repos/azure-cli/
Suites: ${AZ_DIST}
Components: main
Arch: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/microsoft.gpg" |
  sudo tee /etc/apt/sources.list.d/azure-cli.sources

# Install
sudo apt update && sudo apt install -y azure-cli
```

**Linux — Fedora / RHEL (dnf):**

```bash
sudo rpm --import https://packages.microsoft.com/keys/microsoft.asc
sudo dnf install -y https://packages.microsoft.com/config/rhel/9.0/packages-microsoft-prod.rpm
sudo dnf install -y azure-cli
```

After installing, verify:

```bash
az --version
# → azure-cli  2.x.x  ...
```

Once installed, proceed to [Azure Setup for Option C](#azure-setup-for-option-c) for
sign-in, collecting credential values, and RBAC role assignment.

---

### Azure Setup for Option C

This section covers everything you need to set up Azure credentials for the
first time. Once complete, you won't need to repeat these steps — only the run
commands in [Option C](#option-c-run-against-cosmos-db-azure-cloud) are needed
on subsequent runs.

---

#### Sign In and Collect Credential Values

Run `az login` to authenticate, then use the commands below to find the four
values needed in your properties file.

**macOS / Linux / Windows:**
```bash
# Sign in (opens a browser window)
az login

# Print your tenant ID  -> paste into hyperscaledb.connection.tenantId
az account show --query tenantId -o tsv

# Print your subscription ID  -> paste into hyperscaledb.connection.subscriptionId
az account show --query id -o tsv

# If you have multiple subscriptions, list them all and pick the right one
az account list --query "[].{Name:name, ID:id, TenantID:tenantId}" -o table

# Set the correct subscription
az account set --subscription "<YOUR-SUBSCRIPTION-ID>"

# Print the resource group  -> paste into hyperscaledb.connection.resourceGroupName
az cosmosdb show --name "<YOUR-COSMOS-ACCOUNT-NAME>" --query resourceGroup -o tsv

# Print the endpoint URL  -> paste into hyperscaledb.connection.endpoint
az cosmosdb show --name "<YOUR-COSMOS-ACCOUNT-NAME>" --query documentEndpoint -o tsv
```

> **Multiple tenants / subscriptions?**
> The `tenantId` property is critical — it pins both `DefaultAzureCredential`
> and the ARM management client to the correct tenant, preventing
> `InvalidAuthenticationTokenTenant` 401 errors. Always set it explicitly.

---

#### Grant the Cosmos DB Data-Plane RBAC Role

Your identity needs the **Cosmos DB Built-in Data Contributor** role to read and
write data. This is a Cosmos-native data-plane role, separate from ARM RBAC.

> Run this **once** per Cosmos DB account. Safe to re-run if already assigned.

**macOS / Linux:**
```bash
# Get your principal (object) ID
PRINCIPAL_ID=$(az ad signed-in-user show --query id -o tsv)
echo "Your principal ID: $PRINCIPAL_ID"

# Assign the role
az cosmosdb sql role assignment create \
  --account-name "<YOUR-COSMOS-ACCOUNT-NAME>" \
  --resource-group "<YOUR-RESOURCE-GROUP>" \
  --role-definition-name "Cosmos DB Built-in Data Contributor" \
  --scope "/" \
  --principal-id "$PRINCIPAL_ID"
```

**Windows (PowerShell):**
```powershell
# Get your principal (object) ID
$PRINCIPAL_ID = az ad signed-in-user show --query id -o tsv
Write-Host "Your principal ID: $PRINCIPAL_ID"

# Assign the role
az cosmosdb sql role assignment create `
  --account-name "<YOUR-COSMOS-ACCOUNT-NAME>" `
  --resource-group "<YOUR-RESOURCE-GROUP>" `
  --role-definition-name "Cosmos DB Built-in Data Contributor" `
  --scope "/" `
  --principal-id $PRINCIPAL_ID
```

> **Why is this needed?**
> When a Cosmos DB account has **local auth disabled** (Entra-ID-only mode),
> the data-plane rejects any request not authorized by a Cosmos-native RBAC role
> — even if you have Contributor on the ARM resource. This assignment grants
> read and write access.

---

#### Verify Azure Prerequisites

**macOS / Linux / Windows:**
```bash
# Confirm signed in and on the right subscription/tenant
az account show --query "{Name:name, SubscriptionID:id, TenantID:tenantId}" -o table

# Confirm the Cosmos DB account is reachable
az cosmosdb show \
  --name "<YOUR-COSMOS-ACCOUNT-NAME>" \
  --resource-group "<YOUR-RESOURCE-GROUP>" \
  --query "{Name:name, Endpoint:documentEndpoint, Location:location}" -o table

# Confirm the RBAC role is assigned
az cosmosdb sql role assignment list \
  --account-name "<YOUR-COSMOS-ACCOUNT-NAME>" \
  --resource-group "<YOUR-RESOURCE-GROUP>" \
  --query "[].{Role:roleDefinitionId, Principal:principalId}" -o table
```

---

### Install AWS CLI

Required for **Option D** (DynamoDB Cloud). The AWS CLI lets you configure
credentials with `aws configure` and manage AWS resources from the terminal.

**Windows (PowerShell):**

```powershell
# Option 1 — winget (recommended)
winget install Amazon.AWSCLI

# Option 2 — MSI installer
curl.exe -fSL -o "$env:TEMP\AWSCLIV2.msi" "https://awscli.amazonaws.com/AWSCLIV2.msi"
Start-Process msiexec.exe -ArgumentList "/i `"$env:TEMP\AWSCLIV2.msi`"" -Wait
```

> Restart your terminal after installing so `aws` is on your PATH.

**macOS:**

```bash
# Option 1 — Homebrew
brew install awscli

# Option 2 — official pkg installer
curl "https://awscli.amazonaws.com/AWSCLIV2.pkg" -o /tmp/AWSCLIV2.pkg
sudo installer -pkg /tmp/AWSCLIV2.pkg -target /
```

**Linux — x86_64:**

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip /tmp/awscliv2.zip -d /tmp/aws-install
sudo /tmp/aws-install/aws/install
```

**Linux — ARM64:**

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o /tmp/awscliv2.zip
unzip /tmp/awscliv2.zip -d /tmp/aws-install
sudo /tmp/aws-install/aws/install
```

After installing, verify:

```bash
aws --version
# → aws-cli/2.x.x ...
```

Once installed, proceed to [AWS Setup for Option D](#aws-setup-for-option-d) for
credential configuration, IAM user creation, region selection, and permission setup.

---

### AWS Setup for Option D

This section covers everything you need to set up AWS credentials for the first
time. Once complete, you won't need to repeat these steps — only the run
commands in [Option D](#option-d-run-against-dynamodb-aws-cloud) are needed on
subsequent runs.

---

#### AWS Regions

Set `hyperscaledb.connection.region` in your properties file to the AWS region
where you want DynamoDB tables created.

**How to find your region:**

| Method | Where to look |
|--------|--------------|
| Already ran `aws configure` | `aws configure get region` |
| AWS Console | Top-right corner of any page — e.g. `US East (N. Virginia)` |
| Existing DynamoDB tables | AWS Console → DynamoDB → Tables — region shown in the browser URL |
| Account admin | Ask which region your team uses for DynamoDB |

**Common region codes:**

| Region name | Code |
|-------------|------|
| US East (N. Virginia) | `us-east-1` |
| US East (Ohio) | `us-east-2` |
| US West (Oregon) | `us-west-2` |
| EU (Ireland) | `eu-west-1` |
| EU (Frankfurt) | `eu-central-1` |
| Asia Pacific (Tokyo) | `ap-northeast-1` |
| Asia Pacific (Sydney) | `ap-southeast-2` |

> Pick the region **closest to you** if starting fresh, or the region where
> your existing DynamoDB tables live if you have prior data. If unsure, use
> `us-east-1` — it is the oldest and most commonly used AWS region.

**Commands to discover your region:**

**macOS / Linux / Windows:**
```bash
# Print your currently configured region
aws configure get region

# Check if you have existing DynamoDB tables in common regions
aws dynamodb list-tables --region us-east-1 --output table
aws dynamodb list-tables --region us-west-2 --output table
```

> **macOS / Linux** — list all available regions:
> ```bash
> aws ec2 describe-regions \
>   --query "Regions[?starts_with(RegionName,'us') || starts_with(RegionName,'eu') || starts_with(RegionName,'ap')].RegionName" \
>   --output table 2>/dev/null
> ```
>
> **Windows (PowerShell)** — list all available regions:
> ```powershell
> aws ec2 describe-regions `
>   --query "Regions[].RegionName" `
>   --output table 2>$null
> ```

---

#### Configure AWS Credentials

Run the interactive wizard to store your credentials in `~/.aws/credentials`:

**macOS / Linux / Windows:**
```bash
aws configure
```

| Prompt | Value |
|--------|-------|
| AWS Access Key ID | Your IAM access key ID |
| AWS Secret Access Key | Your IAM secret access key |
| Default region name | Region code — see [AWS Regions](#aws-regions) above |
| Default output format | `json` |

**Verify:**

```bash
aws sts get-caller-identity
# Expected:
# {
#   "UserId": "AIDAXXXXXXXXXXXXXXXXX",
#   "Account": "123456789012",
#   "Arn": "arn:aws:iam::123456789012:user/hyperscaledb-dev"
# }
```

**Multiple profiles:**

**macOS / Linux / Windows:**
```bash
aws configure list-profiles
```

Then set the profile for your session:

**macOS / Linux:**
```bash
export AWS_PROFILE=<YOUR-PROFILE-NAME>
```

**Windows (PowerShell):**
```powershell
$env:AWS_PROFILE = "<YOUR-PROFILE-NAME>"
```

---

#### Create an IAM User and Access Key

Follow these steps if you don't have an IAM user or access key yet.
Everything is done in the **AWS Management Console** — no CLI needed.

---

##### Part A — Create a new IAM user (skip if you already have one)

**Step A1 — Sign in to the AWS Console**

Open [https://console.aws.amazon.com](https://console.aws.amazon.com) and sign
in as the **root user** or an existing administrator.

**Step A2 — Open IAM**

In the top search bar, type **IAM** and click the IAM service.

**Step A3 — Create the user**

- In the left sidebar, click **Users** → **Create user**.
- Enter a **User name** (e.g. `hyperscaledb-dev`).
- **Do NOT check** "Provide user access to the AWS Management Console" —
  this user only needs programmatic (CLI/SDK) access, not console sign-in.
- Click **Next**.

**Step A4 — Attach permission policies**

On the "Set permissions" page, choose **Attach policies directly**, then
decide which policy to attach:

| Option | Policy to attach | When to use |
|--------|-----------------|-------------|
| **Quick start** | `AmazonDynamoDBFullAccess` | Development / personal use. Grants full DynamoDB access. |
| **Least privilege** | Custom inline policy (see below) | Team / shared environments. Grants only what the SDK needs. |

**Quick start — attach AmazonDynamoDBFullAccess:**

- Search for `AmazonDynamoDBFullAccess`, check the box, click **Next** → **Create user**.

**Least privilege — create a custom policy:**

- Click **Create policy** (opens a new tab).
- Select the **JSON** tab and paste the policy below, replacing
  `<YOUR-AWS-REGION>` and `<YOUR-ACCOUNT-ID>`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "HyperscaleDbDynamoAccess",
      "Effect": "Allow",
      "Action": [
        "dynamodb:CreateTable",
        "dynamodb:DescribeTable",
        "dynamodb:ListTables",
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:UpdateItem",
        "dynamodb:DeleteItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:BatchWriteItem",
        "dynamodb:BatchGetItem"
      ],
      "Resource": [
        "arn:aws:dynamodb:<YOUR-AWS-REGION>:<YOUR-ACCOUNT-ID>:table/*"
      ]
    }
  ]
}
```

- Click **Next**, name the policy `HyperscaleDbDynamoPolicy`, click **Create policy**.
- Go back to the user creation tab, refresh, search for `HyperscaleDbDynamoPolicy`,
  check it, click **Next** → **Create user**.

> **How to find your Account ID:**
> Top-right corner of the AWS Console, or run:
> `aws sts get-caller-identity --query Account --output tsv`

**Step A5 — Confirm the user was created**

You should now see `hyperscaledb-dev` in the Users list.

---

##### Part B — Create the access key

**Step B1 — Open your user**

In IAM → **Users**, click your username.

**Step B2 — Go to Security credentials**

- Click the **Security credentials** tab.
- Scroll down to **Access keys** → click **Create access key**.

**Step B3 — Select use case**

- Select **Command Line Interface (CLI)**.
- Check the confirmation checkbox and click **Next** → **Create access key**.

**Step B4 — Copy both values immediately**

| Field | Where to paste |
|-------|----------------|
| **Access key ID** | `AWS Access Key ID` prompt in `aws configure` |
| **Secret access key** | `AWS Secret Access Key` prompt in `aws configure` |

> ⚠️ The **Secret access key is shown only once**. If you close this page
> without copying it, delete the key and create a new one.
> Store it in a password manager — never in a source file or properties file.

---

#### Verify IAM Permissions

Run this smoke test to confirm your identity has the required DynamoDB permissions:

**macOS / Linux / Windows:**
```bash
aws sts get-caller-identity --output table

aws dynamodb create-table \
  --table-name sdk-permission-test \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region <YOUR-AWS-REGION>

aws dynamodb delete-table --table-name sdk-permission-test --region <YOUR-AWS-REGION>
```

**macOS / Linux:**
```bash
echo "Permissions OK"
```

**Windows (PowerShell):**
```powershell
Write-Host "Permissions OK"
```

**Required permissions:**

| Permission | Purpose in this app |
|------------|---------------------|
| `dynamodb:CreateTable` | Auto-provisioning tenant tables on first startup |
| `dynamodb:DescribeTable` | Checking whether a table already exists before creating |
| `dynamodb:ListTables` | Listing tables during verification |
| `dynamodb:PutItem` | Writing demo data and all create/upsert operations |
| `dynamodb:GetItem` | Reading a single item by key |
| `dynamodb:UpdateItem` | Updating existing items |
| `dynamodb:DeleteItem` | Deleting items |
| `dynamodb:Query` | Partition-scoped queries (e.g. positions for a portfolio) |
| `dynamodb:Scan` | Cross-partition queries when no partition key is specified |
| `dynamodb:BatchWriteItem` | Bulk-writing demo seed data efficiently |
| `dynamodb:BatchGetItem` | Bulk-reading multiple items in one request |

> `dynamodb:DeleteTable` is **not required** to run the app. It is only needed
> for the cleanup commands in Step D6. You can delete tables manually from the
> AWS Console if you prefer not to grant this permission.

---

#### AWS SSO Alternative (no long-lived keys)

If your organisation uses **AWS IAM Identity Center (SSO)**, use `aws sso login`
instead of `aws configure` with static access keys:

**macOS / Linux / Windows:**
```bash
# One-time SSO profile setup
aws configure sso

# Log in (opens a browser window)
aws sso login --profile <YOUR-SSO-PROFILE-NAME>

# Verify
aws sts get-caller-identity
```

Then set the profile for your session:

**macOS / Linux:**
```bash
export AWS_PROFILE=<YOUR-SSO-PROFILE-NAME>
```

**Windows (PowerShell):**
```powershell
$env:AWS_PROFILE = "<YOUR-SSO-PROFILE-NAME>"
```

> SSO sessions expire (typically after 8–12 hours). Re-run
> `aws sso login --profile <profile>` when you get an `ExpiredTokenException`.

---

### Install OpenSSL (Windows only — needed for Option A)

macOS and Linux ship with OpenSSL pre-installed. On Windows:

```powershell
winget install ShiningLight.OpenSSL.Light
```

Restart your terminal after installing, then verify:

```powershell
openssl version
# → OpenSSL 3.x.x ...
```

### Install Node.js (optional — for dynamodb-admin GUI)

**Windows (PowerShell):**

```powershell
winget install OpenJS.NodeJS.LTS
```

**macOS (Homebrew):**

```bash
brew install node@18
```

**Linux — Debian / Ubuntu:**

```bash
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs
```

Verify:

```bash
node -v
# → v18.x.x or higher
```

