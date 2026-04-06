# Hyperscale DB E2E Tests

End-to-end portability tests for the Hyperscale DB SDK. Runs the **same CRUD
code** against Azure Cosmos DB, Amazon DynamoDB, or Google Cloud Spanner by
switching a single properties file — no code changes required.

---

## Prerequisites

1. **Java 17+** and **Maven 3.8+** installed.
2. **Build the SDK** from the repo root (populates your local `~/.m2`):
   ```bash
   mvn install -DskipTests
   ```
3. **Set up credentials** for the provider(s) you want to test (see
   [Configuration](#configuration) below).

---

## Configuration

Properties files are **not committed** (they contain credentials). Use the
provided templates to create your local copies:

```bash
cd hyperscaledb-e2e/src/main/resources

cp cosmos.properties.template   cosmos.properties
cp dynamo.properties.template   dynamo.properties
cp spanner.properties.template  spanner.properties
```

Then open the copied file and replace the `<placeholder>` values.

---

## Running the tests

All commands are run from the **repo root**.

### Azure Cosmos DB

1. Fill in `hyperscaledb-e2e/src/main/resources/cosmos.properties`:
   ```properties
   hyperscaledb.connection.endpoint=https://<your-account>.documents.azure.com:443/
   hyperscaledb.connection.key=<your-primary-key>
   ```
   Find these in **Azure Portal → Cosmos DB account → Keys**.

2. Run:
   ```bash
   mvn -pl hyperscaledb-e2e exec:java
   # or explicitly:
   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=cosmos.properties
   ```

### Amazon DynamoDB

1. Fill in `hyperscaledb-e2e/src/main/resources/dynamo.properties`:
   ```properties
   hyperscaledb.connection.region=us-east-1
   # Optional static credentials (leave commented to use default credential chain):
   # hyperscaledb.auth.accessKeyId=<your-access-key-id>
   # hyperscaledb.auth.secretAccessKey=<your-secret-access-key>
   ```

2. Run:
   ```bash
   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=dynamo.properties
   ```

### Google Cloud Spanner

1. Fill in `hyperscaledb-e2e/src/main/resources/spanner.properties`:
   ```properties
   hyperscaledb.connection.projectId=<your-gcp-project-id>
   hyperscaledb.connection.instanceId=<your-spanner-instance-id>
   hyperscaledb.connection.databaseId=<your-spanner-database-id>
   ```

2. Authenticate:
   ```bash
   gcloud auth application-default login
   ```

3. Run:
   ```bash
   mvn -pl hyperscaledb-e2e exec:java -Dhyperscaledb.config=spanner.properties
   ```

---

## What the tests do

Each run exercises the full CRUD surface on a `products` collection:

| Step | Operation | SDK method |
|------|-----------|------------|
| 1 | Create 5 products | `client.upsert(...)` |
| 2 | Read one by ID | `client.read(...)` |
| 3 | Update a product | `client.upsert(...)` |
| 4 | Verify update | `client.read(...)` |
| 5 | List all (paged) | `client.query(...)` |
| 6 | Filter by category | `client.query(expression)` |
| 7 | Filter in-stock + price | `client.query(expression)` |
| 8 | Delete one item | `client.delete(...)` |
| 9 | Confirm deletion | `client.query(...)` |
| 10 | Cleanup all items | `client.delete(...)` |

---

## Switching providers

Edit the corresponding properties file in `src/main/resources/`, then pass it
via `-Dhyperscaledb.config`:

```
src/main/resources/
├── cosmos.properties    ← Azure Cosmos DB
├── dynamo.properties    ← Amazon DynamoDB
└── spanner.properties   ← Google Cloud Spanner
```

You can also override individual properties at the command line without editing
the file:

```bash
mvn -pl hyperscaledb-e2e exec:java \
  -Dhyperscaledb.config=cosmos.properties \
  -Dhyperscaledb.connection.endpoint=https://myaccount.documents.azure.com:443 \
  -Dhyperscaledb.connection.key=<key>
```

---

## Project structure

```
hyperscaledb-e2e/
├── pom.xml                              ← Child module POM; inherits SDK versions from parent
├── README.md
└── src/main/
    ├── java/com/microsoft/hyperscaledb/e2e/
    │   ├── Main.java                    ← Entry point; orchestrates the E2E run
    │   └── ConfigLoader.java            ← Loads *.properties, builds SDK config
    └── resources/
        ├── cosmos.properties.template   ← Cosmos DB config template (committed)
        ├── dynamo.properties.template   ← DynamoDB config template (committed)
        ├── spanner.properties.template  ← Spanner config template (committed)
        ├── cosmos.properties            ← Your local credentials (git-ignored)
        ├── dynamo.properties            ← Your local credentials (git-ignored)
        ├── spanner.properties           ← Your local credentials (git-ignored)
        └── logback.xml                  ← Logging configuration
```

> ⚠️ **Never commit credentials** — `*.properties` is in `.gitignore`. Only the
> `*.properties.template` files (with placeholder values) are version-controlled.
