# Multicloud DB E2E Tests

End-to-end portability tests for the Multicloud DB SDK. The portable CRUD/query
baseline runs against Azure Cosmos DB, Amazon DynamoDB, or Google Cloud Spanner
by switching one properties file. A separate wider-than-10-field update runs
only on Cosmos DB and DynamoDB because the existing Spanner path is fixed-schema.

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
`products` table must already contain the columns used by its existing CRUD and
query scenario: `id`, `name`, `category`, `price`, and `inStock` (in addition to
the SDK key and `data` columns). Partial-update steps are capability-gated and
skipped because the unchanged Spanner provider does not advertise this feature.

---

## What the tests do

Each run exercises the full CRUD surface on a `products` collection:

| Step | Operation | SDK method |
|------|-----------|------------|
| 1 | Create 5 products | `client.upsert(...)` |
| 2 | Read one by ID | `client.read(...)` |
| 3 | Partially update price and stock (Cosmos/Dynamo only) | `client.update(...)` |
| 4 | Verify changed fields and omitted name/category preservation (Cosmos/Dynamo only) | `client.read(...)` |
| 5 | Update 11 ordinary fields (Cosmos/Dynamo only) and verify preservation | `client.update(...)`, `client.read(...)` |
| 6 | List all (paged) | `client.query(...)` |
| 7 | Filter by category | `client.query(expression)` |
| 8 | Filter in-stock + price | `client.query(expression)` |
| 9 | Delete one item | `client.delete(...)` |
| 10 | Confirm deletion | `client.query(...)` |
| 11 | Cleanup all items | `client.delete(...)` |

The partial-update cases exercise Cosmos's patch/batch paths and DynamoDB's
single `UpdateItem`. They are skipped for providers that do not advertise
`PARTIAL_UPDATE`, including unchanged Spanner in this release.

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
