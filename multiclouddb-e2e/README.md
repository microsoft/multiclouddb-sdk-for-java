# Multicloud DB E2E Tests

End-to-end portability tests for the Multicloud DB SDK. The portable
create/read/upsert/delete and query baseline runs against Azure Cosmos DB,
Amazon DynamoDB, or Google Cloud Spanner by switching one properties file.
Partial update is exercised within the portable
10-field, 31-level replacement-depth, and 390 KiB serialized/structural input
envelopes on Cosmos DB and DynamoDB; Spanner is capability-gated.

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
cd multiclouddb-e2e/src/main/resources

cp cosmos.properties.template   cosmos.properties
cp dynamo.properties.template   dynamo.properties
cp spanner.properties.template  spanner.properties
```

Then open the copied file and replace the `<placeholder>` values.

---

## Running the tests

All commands are run from the **repo root**. The `process-resources` phase
ensures your local `*.properties` files are copied to `target/classes/` before
the app starts.

### Azure Cosmos DB

1. Fill in `multiclouddb-e2e/src/main/resources/cosmos.properties`:
   ```properties
   multiclouddb.connection.endpoint=https://<your-account>.documents.azure.com:443/
   multiclouddb.connection.key=<your-primary-key>
   ```
   Find these in **Azure Portal → Cosmos DB account → Keys**.

2. Run:
   ```bash
   mvn -pl multiclouddb-e2e process-resources exec:java
   # or explicitly:
   mvn -pl multiclouddb-e2e process-resources exec:java -Dmulticlouddb.config=cosmos.properties
   ```

### Amazon DynamoDB

1. Fill in `multiclouddb-e2e/src/main/resources/dynamo.properties`:
   ```properties
   multiclouddb.connection.region=us-east-1
   # Optional static credentials (leave commented to use default credential chain):
   # multiclouddb.auth.accessKeyId=<your-access-key-id>
   # multiclouddb.auth.secretAccessKey=<your-secret-access-key>
   ```

2. Run:
   ```bash
   mvn -pl multiclouddb-e2e process-resources exec:java -Dmulticlouddb.config=dynamo.properties
   ```

### Google Cloud Spanner

1. Fill in `multiclouddb-e2e/src/main/resources/spanner.properties`:
   ```properties
   multiclouddb.connection.projectId=<your-gcp-project-id>
   multiclouddb.connection.instanceId=<your-spanner-instance-id>
   multiclouddb.connection.databaseId=<your-spanner-database-id>
   ```

2. Authenticate:
   ```bash
   gcloud auth application-default login
   ```

3. Run:
   ```bash
   mvn -pl multiclouddb-e2e process-resources exec:java -Dmulticlouddb.config=spanner.properties
   ```

The E2E runner does not add application columns to Spanner. The configured
`products` table must already contain the columns used by its existing base
operation and query scenario: `name`, `category`, `price`, and `inStock`
(in addition to the SDK key and `data` columns). Partial-update steps are capability-gated and
skipped because the API defaults Spanner's omitted capability to unsupported.

---

## What the tests do

Each run exercises the portable base operations and queries on a `products`
collection. Partial-update steps run only when `PARTIAL_UPDATE` is advertised:

| Step | Operation | SDK method |
|------|-----------|------------|
| 1 | Create 5 products | `client.upsert(...)` |
| 2 | Read one by ID | `client.read(...)` |
| 3 | Partially update price and stock (Cosmos/Dynamo only) | `client.update(...)` |
| 4 | Verify changed fields and omitted name/category preservation (Cosmos/Dynamo only) | `client.read(...)` |
| 5 | List all (paged) | `client.query(...)` |
| 6 | Filter by category | `client.query(expression)` |
| 7 | Filter in-stock + price | `client.query(expression)` |
| 8 | Delete one item | `client.delete(...)` |
| 9 | Confirm deletion | `client.query(...)` |
| 10 | Cleanup all items | `client.delete(...)` |


The partial-update case exercises Cosmos's single-patch path and DynamoDB's
single `UpdateItem`. They are skipped for providers that do not advertise
`PARTIAL_UPDATE`, including Spanner receiving the API unsupported default.

---

## Switching providers

Edit the corresponding properties file in `src/main/resources/`, then pass it
via `-Dmulticlouddb.config`:

```
src/main/resources/
├── cosmos.properties    ← Azure Cosmos DB
├── dynamo.properties    ← Amazon DynamoDB
└── spanner.properties   ← Google Cloud Spanner
```

You can also override individual properties at the command line without editing
the file:

```bash
mvn -pl multiclouddb-e2e process-resources exec:java \
  -Dmulticlouddb.config=cosmos.properties \
  -Dmulticlouddb.connection.endpoint=https://myaccount.documents.azure.com:443 \
  -Dmulticlouddb.connection.key=<key>
```

---

## Project structure

```
multiclouddb-e2e/
├── pom.xml                              ← Child module POM; inherits SDK versions from parent
├── README.md
└── src/main/
    ├── java/com/microsoft/multiclouddb/e2e/
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

> ⚠️ **Never commit credentials** — `src/main/resources/*.properties` is broadly
> git-ignored. Only `*.properties.template` files (with placeholder values) are
> version-controlled. Any new `*.properties` file you add is automatically excluded.
