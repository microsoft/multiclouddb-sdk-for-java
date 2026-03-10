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
10. [Appendix: Installing Prerequisites](#appendix-installing-prerequisites)

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

**Windows (PowerShell):**

```powershell
java -version
```

**macOS / Linux (Bash):**

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

**Windows (PowerShell):**

```powershell
mvn -version
```

**macOS / Linux (Bash):**

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
| **C** — Cosmos DB Cloud | Azure CLI | `az login` required |
| **D** — DynamoDB Cloud | AWS CLI | `aws configure` required |
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

**Windows (PowerShell):**

```powershell
mvn clean install -DskipTests
```

**macOS / Linux (Bash):**

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
(see Steps A1 and B2 above), then open **four terminals**:

**Terminal 1** — DynamoDB Local (if not already running, see B1 for download):

```powershell
cd .tools\dynamodb-local
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
