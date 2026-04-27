# Quick Start

Get up and running with the Multicloud DB SDK in minutes.

---

## Prerequisites

| Tool | Version | Required For |
|------|---------|-------------|
| JDK  | 17+     | Build and run |
| Maven | 3.9+   | Build |

```bash
java -version    # must be 17 or newer
mvn -version     # must be 3.9+
```

---

## 1. Build from Source

```bash
git clone https://github.com/microsoft/multiclouddb-sdk-for-java.git
cd multiclouddb-sdk-for-java
mvn clean install -DskipTests
```

---

## 2. Add Dependencies

Add the portable API as a compile dependency and one or more providers as
runtime dependencies:

```xml
<!-- Portable API (compile scope) -->
<dependency>
    <groupId>com.microsoft.multiclouddb</groupId>
    <artifactId>multiclouddb-api</artifactId>
    <version>0.1.0-beta.1</version>
</dependency>

<!-- Pick a provider (runtime scope - swap without recompiling) -->
<dependency>
    <groupId>com.microsoft.multiclouddb</groupId>
    <artifactId>multiclouddb-provider-cosmos</artifactId>
    <version>0.1.0-beta.1</version>
    <scope>runtime</scope>
</dependency>
```

??? note "Add more providers"

    You can include multiple providers in the same project. Each is discovered
    via `ServiceLoader` and selected by `ProviderId` at runtime:

    ```xml
    <dependency>
        <groupId>com.microsoft.multiclouddb</groupId>
        <artifactId>multiclouddb-provider-dynamo</artifactId>
        <version>0.1.0-beta.1</version>
        <scope>runtime</scope>
    </dependency>
    ```

    Spanner support is source-available but its Maven artifact is not yet
    published. Do not add `multiclouddb-provider-spanner` as a normal Maven
    dependency until it is published; instead, build it from source if you
    need to evaluate it locally.
---

## 3. Write Portable Code

```java
import com.multiclouddb.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

// Configure - provider selected entirely by config, not code
Properties props = new Properties();
props.load(getClass().getResourceAsStream("/app.properties"));

ProviderId provider = ProviderId.fromId(props.getProperty("multiclouddb.provider"));

MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
        .provider(provider)
        .connection("endpoint", props.getProperty("multiclouddb.connection.endpoint"))
        // Auth properties (key, credentials, etc.) are loaded from the
        // properties file. See Configuration Reference for recommended
        // identity-based auth patterns for each provider.
        .build();

// Create client via ServiceLoader discovery
MulticloudDbClient client = MulticloudDbClientFactory.create(config);

// CRUD - same code for every provider
Map<String, Object> doc = Map.of(
        "title", "Buy groceries",
        "completed", false
);

ResourceAddress todos = new ResourceAddress("mydb", "todos");
MulticloudDbKey key = MulticloudDbKey.of("todo-1", "todo-1");

client.upsert(todos, key, doc);                  // Create or replace
DocumentResult result = client.read(todos, key); // Point read
JsonNode document = result.document();           // The document payload
client.delete(todos, key);                       // Delete
```

---

## 4. Query with Portable Expressions

Write a WHERE-clause filter once - the SDK translates it for each provider:

```java
QueryRequest query = QueryRequest.builder()
        .expression("status = @status AND category = @cat")
        .parameters(Map.of("status", "active", "cat", "shopping"))
        .maxPageSize(25)
        .build();

QueryPage page = client.query(todos, query);
for (JsonNode item : page.items()) {
    System.out.println(item);
}
```

The same expression produces different native queries per provider:

| Provider | Generated Native Query |
|----------|----------------------|
| **Cosmos DB** | `SELECT * FROM c WHERE (c.status = @status AND c.category = @cat)` |
| **DynamoDB** | `SELECT * FROM "todos" WHERE (status = ? AND category = ?)` |
| **Spanner** | `SELECT * FROM todos WHERE (status = @status AND category = @cat)` |

---

## 5. Native Query Escape Hatch

When you need provider-specific query syntax, use `nativeExpression()`:

=== "Cosmos DB"

    ```java
    QueryRequest q = QueryRequest.builder()
            .nativeExpression("SELECT * FROM c WHERE c.title LIKE '%flight%'")
            .maxPageSize(25)
            .build();
    ```

=== "DynamoDB"

    ```java
    QueryRequest q = QueryRequest.builder()
            .nativeExpression("SELECT * FROM \"todos\" WHERE begins_with(title, 'Ship')")
            .maxPageSize(25)
            .build();
    ```

=== "Spanner"

    ```java
    QueryRequest q = QueryRequest.builder()
            .nativeExpression("SELECT * FROM todos WHERE STARTS_WITH(title, 'Ship')")
            .maxPageSize(25)
            .build();
    ```

---

## 6. Switch Providers

Change **only** the properties file - no code changes required:

!!! warning "Use identity-based authentication in production"

    The examples below use **key-based auth for local emulators only**.
    For production workloads, use identity-based authentication:
    Azure Entra ID for Cosmos DB, IAM roles for DynamoDB, or
    GCP service accounts for Spanner.
    See the [Configuration Reference](configuration.md) for details.

=== "Cosmos DB"

    ```properties
    # Local emulator only - do not use key-based auth in production
    multiclouddb.provider=cosmos
    multiclouddb.connection.endpoint=https://localhost:8081
    multiclouddb.connection.key=C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==
    ```

=== "DynamoDB"

    ```properties
    # Local emulator only - do not use static credentials in production
    multiclouddb.provider=dynamo
    multiclouddb.connection.endpoint=http://localhost:8000
    multiclouddb.connection.region=us-east-1
    multiclouddb.auth.accessKeyId=fakeMyKeyId
    multiclouddb.auth.secretAccessKey=fakeSecretAccessKey
    ```

=== "Spanner"

    ```properties
    multiclouddb.provider=spanner
    multiclouddb.connection.projectId=my-gcp-project
    multiclouddb.connection.instanceId=my-instance
    multiclouddb.connection.databaseId=my-database
    ```

---

## Next Steps

- [Configuration Reference](configuration.md) - full connection and auth properties per provider
- [Developer Guide](guide.md) - keys, CRUD semantics, query DSL, partitioning, multi-tenant patterns
- [Provider Compatibility](compatibility.md) - capability matrix and error mapping
- [Samples](https://github.com/microsoft/multiclouddb-sdk-for-java-samples) - TODO app, Risk Analysis Platform, and Portable CRUD + Query sample
