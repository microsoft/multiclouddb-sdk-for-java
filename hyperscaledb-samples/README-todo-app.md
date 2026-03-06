# Hyperscale DB SDK — TODO Sample Application

A simple CRUD **web application** that demonstrates the Hyperscale DB SDK's
provider-portable API. The same Java code runs against **Azure Cosmos DB**,
**Amazon DynamoDB**, or **Google Cloud Spanner** — switch providers by changing
a single properties file.

The app starts an embedded HTTP server on `http://localhost:8080` with a
browser-based UI for creating, reading, updating, and deleting TODO items.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Emulator Setup](#emulator-setup)
   - [Azure Cosmos DB Emulator](#azure-cosmos-db-emulator)
   - [DynamoDB Local](#dynamodb-local)
   - [DynamoDB Admin (GUI)](#dynamodb-admin-gui)
   - [Google Cloud Spanner Emulator](#google-cloud-spanner-emulator)
3. [Running the Sample](#running-the-sample)
   - [Against Cosmos DB Emulator](#run-against-cosmos-db-emulator)
   - [Against DynamoDB Local](#run-against-dynamodb-local)
   - [Against Spanner Emulator](#run-against-spanner-emulator)
4. [Web UI Features](#web-ui-features)

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 17 LTS  | Required — e.g. [Eclipse Adoptium](https://adoptium.net/) |
| Maven | 3.9+   | Build tool |
| Docker | 20+   | Required for Spanner Emulator |
| Node.js + npm | 18+ | Only if you want `dynamodb-admin` GUI |

Make sure `JAVA_HOME` points to JDK 17 and is on your `PATH`:

```powershell
# PowerShell example
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # should say 17.x
```

---

## Emulator Setup

### Azure Cosmos DB Emulator

The Cosmos DB emulator provides a free local instance of Azure Cosmos DB for
development and testing.

#### 1. Install

Download and install from:\
<https://learn.microsoft.com/en-us/azure/cosmos-db/emulator#install-the-emulator>

> **Windows**: Run the MSI installer. The emulator is added to Start Menu.\
> **Docker** (Linux/macOS):
> ```bash
> docker pull mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
> docker run -p 8081:8081 -p 10250-10255:10250-10255 \
>   mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:latest
> ```

#### 2. Start the emulator

On Windows, launch **Azure Cosmos DB Emulator** from the Start Menu (or
system tray). It starts on **https://localhost:8081** by default.

Open the Data Explorer in your browser:\
<https://localhost:8081/_explorer/index.html>

#### 3. Create the database and container

In Data Explorer → **New Container**:

| Field | Value |
|-------|-------|
| Database id | `todoapp` |
| Container id | `todos` |
| Partition key | `/id` |

Or via the Cosmos DB SDK / CLI:

```powershell
# Using Azure CLI (if installed)
az cosmosdb create --name todoapp --resource-group local --kind GlobalDocumentDB
```

#### 4. Emulator connection details (already in `todo-app.properties`)

| Property | Value |
|----------|-------|
| Endpoint | `https://localhost:8081` |
| Key      | `C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==` |
| Connection mode | `gateway` (required for emulator) |

#### 5. SSL certificate trust

The emulator uses a self-signed TLS certificate. If you see SSL errors, import
the certificate into a local truststore:

```powershell
# 1. Export the certificate (PowerShell)
$cert = (New-Object System.Net.Sockets.TcpClient('localhost',8081)).GetStream() |
        ForEach-Object {
            $ssl = New-Object System.Net.Security.SslStream($_, $false, {$true})
            $ssl.AuthenticateAsClient('localhost')
            $ssl.RemoteCertificate
        }
[System.IO.File]::WriteAllBytes('cosmos-emulator.cer', $cert.Export('Cert'))

# 2. Import into a local keystore
keytool -importcert -alias cosmosemulator -file cosmos-emulator.cer `
        -keystore .tools/cacerts-local -storepass changeit -noprompt

# 3. Tell Maven/Surefire to use it (already configured in conformance POM)
#    -Djavax.net.ssl.trustStore=.../.tools/cacerts-local
#    -Djavax.net.ssl.trustStorePassword=changeit
```

---

### DynamoDB Local

DynamoDB Local is a small Java application provided by AWS that emulates the
DynamoDB service on your machine.

#### 1. Download

```powershell
# Create a tools directory
New-Item -ItemType Directory -Force -Path .tools/dynamodb-local | Out-Null

# Download the latest release
Invoke-WebRequest `
  -Uri 'https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip' `
  -OutFile '.tools/dynamodb-local/dynamodb_local.zip'

# Extract
Expand-Archive -Path '.tools/dynamodb-local/dynamodb_local.zip' `
               -DestinationPath '.tools/dynamodb-local' -Force

# Clean up the zip
Remove-Item '.tools/dynamodb-local/dynamodb_local.zip'
```

> **macOS / Linux alternative** (using curl):
> ```bash
> mkdir -p .tools/dynamodb-local
> curl -L https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.zip \
>   -o .tools/dynamodb-local/dynamodb_local.zip
> cd .tools/dynamodb-local && unzip dynamodb_local.zip && rm dynamodb_local.zip
> ```

#### 2. Start DynamoDB Local

```powershell
# Make sure JAVA_HOME points to JDK 17
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"

cd .tools/dynamodb-local
java "-Djava.library.path=./DynamoDBLocal_lib" -jar DynamoDBLocal.jar -sharedDb -port 8000
```

DynamoDB Local starts on **http://localhost:8000**. Keep this terminal open.

| Flag | Purpose |
|------|---------|
| `-sharedDb` | All clients share a single database file |
| `-port 8000` | Listening port (default 8000) |
| `-inMemory` | Optional — data is lost on restart |

#### 3. Verify it's running

```powershell
# A 400 Bad Request is expected (DynamoDB only speaks its own wire protocol)
Invoke-WebRequest -Uri 'http://localhost:8000' -UseBasicParsing
```

#### 4. Connection details (already in `todo-app-dynamo.properties`)

| Property | Value |
|----------|-------|
| Endpoint | `http://localhost:8000` |
| Region   | `us-east-1` (any value works locally) |
| Access Key ID | `fakeMyKeyId` (any non-empty string) |
| Secret Access Key | `fakeSecretAccessKey` (any non-empty string) |

> The sample app's `DynamoProviderClient` automatically creates the `todos`
> table when it runs the conformance tests. For the interactive sample, the
> table is also created on first write.

---

### DynamoDB Admin (GUI)

[dynamodb-admin](https://github.com/aaronshaf/dynamodb-admin) is a lightweight
Node.js web UI for browsing DynamoDB Local tables and data.

#### 1. Install

```bash
npm install -g dynamodb-admin
```

#### 2. Start

```powershell
# Point it at DynamoDB Local
$env:DYNAMO_ENDPOINT = 'http://localhost:8000'
dynamodb-admin
```

#### 3. Open the GUI

Navigate to **http://localhost:8001** in your browser.

From there you can:
- Browse all tables and their items
- Create / edit / delete individual items
- Run scans and queries
- View table schemas and indexes

> **Tip**: Keep DynamoDB Local running in one terminal and `dynamodb-admin`
> in another. Any data written by the sample app will be visible in the GUI
> immediately.

---

### Google Cloud Spanner Emulator

The Spanner emulator is a Docker container that provides a local Spanner
environment for development and testing.

#### 1. Start the emulator

```powershell
docker run -d --name spanner-emulator -p 9010:9010 -p 9020:9020 `
  gcr.io/cloud-spanner-emulator/emulator
```

| Port | Protocol | Purpose |
|------|----------|----------|
| 9010 | gRPC | Client library connections |
| 9020 | REST | Admin API (instance/database creation) |

#### 2. Create instance and database

Use the REST Admin API to bootstrap the emulator:

```powershell
# Create a Spanner instance
Invoke-RestMethod -Method POST `
  -Uri 'http://localhost:9020/v1/projects/test-project/instances' `
  -ContentType 'application/json' `
  -Body '{"instanceId":"test-instance","instance":{"config":"emulator-config","displayName":"Test","nodeCount":1}}'

# Create the todoapp database with a 'todos' table
Invoke-RestMethod -Method POST `
  -Uri 'http://localhost:9020/v1/projects/test-project/instances/test-instance/databases' `
  -ContentType 'application/json' `
  -Body '{"createStatement":"CREATE DATABASE todoapp","extraStatements":["CREATE TABLE todos (id STRING(MAX) NOT NULL, sortKey STRING(MAX) NOT NULL, title STRING(MAX), completed BOOL, partitionKey STRING(MAX)) PRIMARY KEY (id, sortKey)"]}'
```

#### 3. Connection details (already in `todo-app-spanner.properties`)

| Property | Value |
|----------|-------|
| Project ID | `test-project` |
| Instance ID | `test-instance` |
| Database ID | `todoapp` |
| Emulator Host | `localhost:9010` |

---

## Running the Sample

First, build the entire SDK from the repo root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot'
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
mvn clean install -DskipTests
```

### Run against Cosmos DB Emulator

Make sure the Cosmos DB emulator is running and the `todoapp` / `todos`
database/container exists.

```powershell
# Start the web app (Cosmos DB)
mvn -pl hyperscaledb-samples exec:java `
  -Dexec.mainClass="com.hyperscaledb.samples.todo.TodoApp" `
  "-Dtodo.config=todo-app-cosmos.properties" `
  "-Djavax.net.ssl.trustStore=$PWD/.tools/cacerts-local" `
  "-Djavax.net.ssl.trustStorePassword=changeit"
```

Then open **http://localhost:8080** in your browser.

> **Note**: The `-Djavax.net.ssl.trustStore` flags are needed because the Cosmos
> emulator uses a self-signed certificate (see the SSL section above).

### Run against DynamoDB Local

Make sure DynamoDB Local is running on port 8000.

```powershell
# Start the web app (DynamoDB)
mvn -pl hyperscaledb-samples exec:java `
  -Dexec.mainClass="com.hyperscaledb.samples.todo.TodoApp" `
  "-Dtodo.config=todo-app-dynamo.properties"
```

Then open **http://localhost:8080** in your browser.

> **Tip**: Open **http://localhost:8001** (dynamodb-admin) side-by-side to see
> the raw data as you interact with the web UI.

### Run against Spanner Emulator

Make sure the Spanner emulator is running and the instance/database exists
(see [Spanner Emulator Setup](#google-cloud-spanner-emulator) below).

```powershell
# Start the web app (Spanner)
mvn -pl hyperscaledb-samples exec:java `
  -Dexec.mainClass="com.hyperscaledb.samples.todo.TodoApp" `
  "-Dtodo.config=todo-app-spanner.properties"
```

Then open **http://localhost:8080** in your browser.

### Changing the port

By default the server listens on port 8080. Override with:

```powershell
-Dtodo.port=9090
```

---

## Web UI Features

The browser-based UI at `http://localhost:8080` allows you to:

| Action | How |
|--------|-----|
| **Create** a TODO | Enter an optional ID and a title, then click **Add** |
| **List** all TODOs | Displayed automatically on page load |
| **Toggle complete** | Click the circle checkbox next to any TODO |
| **Delete** a TODO | Hover over a TODO and click the trash icon |
| **View capabilities** | Click **Provider Capabilities** to see what the current provider supports |

### REST API

The web app also exposes a JSON REST API:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/todos` | List all TODOs |
| `GET`  | `/api/todos/{id}` | Get a single TODO |
| `POST` | `/api/todos` | Create a TODO (`{ "id": "...", "title": "..." }`) |
| `PUT`  | `/api/todos/{id}` | Update fields (`{ "completed": true }`) |
| `DELETE` | `/api/todos/{id}` | Delete a TODO |
| `GET`  | `/api/capabilities` | Provider name and supported capabilities |
