# Configuration Reference

All configuration flows through `MulticloudDbClientConfig` or a `.properties` file.
Select a provider and supply its connection and auth properties.

---

## Common Properties

| Property | Description | Example |
|----------|-------------|---------|
| `multiclouddb.provider` | Provider ID | `cosmos`, `dynamo`, `spanner` |
| `multiclouddb.feature.*` | Feature flags | Provider-specific opt-ins |

---

## Azure Cosmos DB

=== "Emulator (local)"

    ```properties
    multiclouddb.provider=cosmos
    multiclouddb.connection.endpoint=https://localhost:8081
    multiclouddb.connection.key=C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==
    multiclouddb.connection.connectionMode=gateway
    ```

=== "Azure Cloud (key-based)"

    ```properties
    # ⚠ Not recommended for production — use Entra ID (see next tab)
    multiclouddb.provider=cosmos
    multiclouddb.connection.endpoint=https://your-account.documents.azure.com:443/
    multiclouddb.connection.key=your-master-key
    multiclouddb.connection.connectionMode=direct
    ```

=== "Azure Identity (Entra ID) — Recommended"

    ```properties
    multiclouddb.provider=cosmos
    multiclouddb.connection.endpoint=https://your-account.documents.azure.com:443/
    # No key — uses DefaultAzureCredential (Managed Identity, Azure CLI, etc.)
    # multiclouddb.connection.tenantId=your-tenant-id   # optional, for multi-tenant
    ```

| Key | Description |
|-----|-------------|
| `multiclouddb.connection.endpoint` | Cosmos DB account URI or emulator URI |
| `multiclouddb.connection.key` | Master key (omit for Azure Identity auth) |
| `multiclouddb.connection.connectionMode` | `gateway` (default) or `direct` |
| `multiclouddb.connection.tenantId` | Azure AD tenant ID (optional, for Entra ID) |

### Authentication Modes

!!! tip "Recommended: Use identity-based authentication"

    Key-based / shared-key authentication is supported for local development
    and emulator use. For production workloads, **always use identity-based auth**
    (Entra ID for Cosmos DB, IAM roles for DynamoDB, GCP service accounts for Spanner).

- **Azure Identity / Entra ID** (**recommended**) — when no key is provided, uses `DefaultAzureCredential`
  (supporting Managed Identity, Azure CLI, environment variables, and the full
  Azure credential chain)
- **Master key** — when `connection.key` is provided, uses shared-key authentication.
  Suitable for local emulator development only.

### Connection Modes

- **Gateway** (default) — HTTP-based routing through the Cosmos DB gateway. Required for the emulator.
- **Direct** — TCP-based direct connectivity. Better performance for production workloads.

---

## Amazon DynamoDB

=== "DynamoDB Local"

    ```properties
    multiclouddb.provider=dynamo
    multiclouddb.connection.endpoint=http://localhost:8000
    multiclouddb.connection.region=us-east-1
    multiclouddb.auth.accessKeyId=fakeMyKeyId
    multiclouddb.auth.secretAccessKey=fakeSecretAccessKey
    ```

=== "AWS Cloud"

    ```properties
    # ⚠ Static credentials shown for reference — use IAM roles in production
    multiclouddb.provider=dynamo
    # No endpoint — uses the AWS default endpoint for the region
    multiclouddb.connection.region=us-east-1
    multiclouddb.auth.accessKeyId=your-access-key
    multiclouddb.auth.secretAccessKey=your-secret-key
    ```

| Key | Description |
|-----|-------------|
| `multiclouddb.connection.endpoint` | DynamoDB Local URI (omit for AWS) |
| `multiclouddb.connection.region` | AWS region (e.g., `us-east-1`) |
| `multiclouddb.auth.accessKeyId` | AWS access key ID |
| `multiclouddb.auth.secretAccessKey` | AWS secret access key |

!!! note "DynamoDB table naming"

    DynamoDB has no native "database" concept. The `ResourceAddress` database
    and collection are composed into a single table name using the pattern
    `database__collection` (double underscore separator).

---

## Google Cloud Spanner

=== "Emulator"

    ```properties
    multiclouddb.provider=spanner
    multiclouddb.connection.projectId=test-project
    multiclouddb.connection.instanceId=test-instance
    multiclouddb.connection.databaseId=test-database
    multiclouddb.connection.emulatorHost=localhost:9010
    ```

=== "GCP Cloud"

    ```properties
    multiclouddb.provider=spanner
    multiclouddb.connection.projectId=my-gcp-project
    multiclouddb.connection.instanceId=my-instance
    multiclouddb.connection.databaseId=my-database
    ```

| Key | Description |
|-----|-------------|
| `multiclouddb.connection.projectId` | GCP project ID |
| `multiclouddb.connection.instanceId` | Spanner instance ID |
| `multiclouddb.connection.databaseId` | Spanner database ID |
| `multiclouddb.connection.emulatorHost` | Emulator host:port (omit for GCP) |

---

## Programmatic Configuration

You can also configure the client programmatically using the builder:

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.COSMOS)
    .connection("endpoint", "https://localhost:8081")
    .connection("key", "your-key")
    .connection("connectionMode", "gateway")
    .build();

MulticloudDbClient client = MulticloudDbClientFactory.create(config);
```

---

## Resource Provisioning

The SDK can provision databases and containers/tables automatically:

```java
// Define your schema: database name → list of collection/table names
Map<String, List<String>> schema = Map.of(
    "admin-db",    List.of("tenants"),
    "acme-risk-db", List.of("portfolios", "positions", "risk_metrics")
);

// Single call — SDK handles parallel creation internally
client.provisionSchema(schema);
```

| Provider | Database Phase | Container/Table Phase |
|----------|---------------|----------------------|
| **Cosmos DB** | Creates databases in parallel | Creates containers in parallel |
| **DynamoDB** | No-op (no native database concept) | Creates tables in parallel, waits for ACTIVE |
| **Spanner** | No-op (database set at client construction) | Creates tables in parallel |

See the [Developer Guide](guide.md#provisioning-resources-with-provisionschema)
for the full provisioning reference.

---

## Custom User-Agent

Append your own identifier to the SDK user-agent for downstream diagnostics:

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.COSMOS)
    .connection("endpoint", "https://my-account.documents.azure.com:443/")
    .userAgentSuffix("my-app/1.2.3")
    .build();
```

Results in: `multiclouddb-sdk-java/0.1.0-beta.1 (17.0.5; Windows 11) my-app/1.2.3`
