# Multicloud DB E2E Tests

End-to-end portability tests for the Multicloud DB SDK. Runs the **same CRUD
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

## Change Feed test

A second runnable (`ChangeFeedMain`) exercises the portable Change Feed API
end-to-end. It runs four phases against the configured provider and fails
loudly if any of them break the portable contract:

1. **entireCollection round-trip + replay** — anchors at `StartPosition.now()`,
   seeds one CREATE / UPDATE / DELETE, drains forward until all three are
   observed, then replays from the *original* anchor token and asserts every
   event re-delivers (at-least-once contract).
2. **NewItemStateMode.OMIT** — seeds a CREATE and asserts the surfaced event
   has `data() == null`.
3. **maxPageSize=1 paging** — seeds 3 CREATEs and asserts the cursor returns
   at most one event per page while still surfacing every seeded key.

It skips cleanly when the configured provider does not advertise
`CHANGE_FEED`.

```bash
# Cosmos DB (default config). Container must be created with
# changeFeedPolicy = AllVersionsAndDeletes — see docs/configuration.md.
mvn -pl multiclouddb-e2e process-resources exec:java \
    -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain

# DynamoDB (table must have StreamSpecification = NEW_AND_OLD_IMAGES)
mvn -pl multiclouddb-e2e process-resources exec:java \
    -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain \
    -Dmulticlouddb.config=dynamo.properties

# Spanner (a `CHANGE STREAM <collection>_changes FOR <collection>` must exist)
mvn -pl multiclouddb-e2e process-resources exec:java \
    -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain \
    -Dmulticlouddb.config=spanner.properties
```

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
    │   ├── Main.java                    ← CRUD/query E2E entry point
    │   ├── ChangeFeedMain.java          ← Change Feed E2E entry point
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
