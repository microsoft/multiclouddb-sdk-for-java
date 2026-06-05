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
    # ⚠ Not recommended for production - use Entra ID (see next tab)
    multiclouddb.provider=cosmos
    multiclouddb.connection.endpoint=https://your-account.documents.azure.com:443/
    multiclouddb.connection.key=your-master-key
    multiclouddb.connection.connectionMode=direct
    ```

=== "Azure Identity (Entra ID) - Recommended"

    ```properties
    multiclouddb.provider=cosmos
    multiclouddb.connection.endpoint=https://your-account.documents.azure.com:443/
    # No key - uses DefaultAzureCredential (Managed Identity, Azure CLI, etc.)
    # multiclouddb.connection.tenantId=your-tenant-id   # optional, for multi-tenant
    ```

| Key | Description |
|-----|-------------|
| `multiclouddb.connection.endpoint` | Cosmos DB account URI or emulator URI |
| `multiclouddb.connection.key` | Master key (omit for Azure Identity auth) |
| `multiclouddb.connection.connectionMode` | `gateway` (default) or `direct` |
| `multiclouddb.connection.tenantId` | Azure AD tenant ID (optional, for Entra ID) |
| `multiclouddb.connection.consistencyLevel` | Read consistency override (optional — see below) |

### Authentication Modes

!!! tip "Recommended: Use identity-based authentication"

    Key-based / shared-key authentication is supported for local development
    and emulator use. For production workloads, **always use identity-based auth**
    (Entra ID for Cosmos DB, IAM roles for DynamoDB, GCP service accounts for Spanner).

- **Azure Identity / Entra ID** (**recommended**) - when no key is provided, uses `DefaultAzureCredential`
  (supporting Managed Identity, Azure CLI, environment variables, and the full
  Azure credential chain)
- **Master key** - when `connection.key` is provided, uses shared-key authentication.
  Suitable for local emulator development only.

### Connection Modes

- **Gateway** (default) - HTTP-based routing through the Cosmos DB gateway. Required for the emulator.
- **Direct** - TCP-based direct connectivity. Better performance for production workloads.

### Consistency Level

When `multiclouddb.connection.consistencyLevel` is **not** set, read requests inherit the account's
configured default consistency level (set in the Azure portal or ARM template).

When the property **is** set, the level is configured once on the underlying `CosmosClientBuilder`
and the Cosmos DB SDK applies it to every read (point-reads and queries) issued from that client
instance. Writes are unaffected — Cosmos DB write durability is independent of the consistency setting.

**Valid values** (case-insensitive):

| Value | Description |
|-------|-------------|
| `STRONG` | Linearizability — reads guaranteed to see the latest committed write |
| `BOUNDED_STALENESS` | Reads lag behind writes by at most a configured number of versions or time |
| `SESSION` | Consistent within a single client session (default for new accounts) |
| `CONSISTENT_PREFIX` | Reads never see out-of-order writes but may lag behind |
| `EVENTUAL` | Lowest latency; reads may return stale data |

> **Note:** Any non-aggregate query without an explicit `ORDER BY` has
> `ORDER BY c.id ASC` appended automatically, including single-partition queries
> (see [Compatibility — Result-set ordering](compatibility.md#result-set-ordering)).
> Aggregate and `GROUP BY` queries are excluded from this default ordering behavior.
> Selecting EVENTUAL consistency reduces per-item read cost but does not eliminate
> the sort-merge RU overhead introduced by this default ordering.

!!! warning "Override must be ≤ account default"
    The client-level override must be **equal to or weaker** than the account's default consistency level.
    For example, if the account is configured for `SESSION`, you may override to `CONSISTENT_PREFIX` or
    `EVENTUAL`, but **not** to `BOUNDED_STALENESS` or `STRONG`. Specifying a stronger level than the
    account default causes a runtime error from the Cosmos DB service.

!!! warning "SESSION default and read-your-own-writes (RYOW) guarantees"
    Cosmos DB uses session tokens to guarantee read-your-own-writes (RYOW) when the
    account default is `SESSION`. Overriding reads to `EVENTUAL` or `CONSISTENT_PREFIX`
    abandons session token tracking for those requests — subsequent reads may not reflect
    writes made in the same client session. This does **not** produce a runtime error
    (the override is still valid since it is weaker than `SESSION`), making it a silent
    semantic change. If RYOW semantics are required, keep the override at `SESSION` or
    omit the property entirely.

!!! note "Client-level setting"
    `consistencyLevel` is configured once at client construction and applies uniformly to
    all read operations from that client instance. Writes are not affected — Cosmos DB
    ignores consistency overrides on write operations at the service level. To use different
    consistency levels for different reads, create separate `MulticloudDbClient` instances
    with the desired override.

**Example** — use eventual consistency for reads while keeping the account default for everything else:

```properties
multiclouddb.connection.consistencyLevel=EVENTUAL
```

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
    # ⚠ Static credentials shown for reference - use IAM roles in production
    multiclouddb.provider=dynamo
    # No endpoint - uses the AWS default endpoint for the region
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

!!! info "Maven artifact coming soon"

    The Spanner provider source code and conformance tests are included in
    the repository, but Maven artifacts are not yet published. You can build
    from source to use the Spanner provider today.

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

## Change Feed Provisioning

The portable change-feed API (`MulticloudDbClient.readChanges`) requires
out-of-band provisioning on every provider before reads will succeed. The SDK
itself does **not** create the underlying CDC resource — gate calls with
`client.capabilities().isSupported(Capability.CHANGE_FEED)` and provision once during
deployment.

### Cosmos DB

The container must be created with the `AllVersionsAndDeletes` change-feed
mode to receive distinct CREATE / UPDATE / DELETE events. Containers created
without it surface only the *latest* version of each document and never emit
DELETE events. No SDK-level config key is required.

### DynamoDB

The table must have `StreamSpecification.StreamEnabled=true` and a
`StreamViewType` of `NEW_AND_OLD_IMAGES` (or `NEW_IMAGE` if old values are not
required). Streams expose `TRIM_HORIZON` / `LATEST` / sequence-number
iterators, which map to `StartPosition.beginning()`, `StartPosition.now()`,
and `StartPosition.fromContinuationToken(...)` respectively. The SDK fans
out across shards internally via `FeedScope.entireCollection()`.

### Google Cloud Spanner

A change stream must be created via DDL ahead of time:

```sql
CREATE CHANGE STREAM events_changes FOR events
  OPTIONS (value_capture_type = 'NEW_ROW');
```

| Key | Description |
|-----|-------------|
| `multiclouddb.connection.changeStream.<collection>` | Optional. Override the change-stream name to read for `<collection>`. Defaults to `<collection>_changes`. |

`value_capture_type` should be `NEW_ROW` or `NEW_ROW_AND_OLD_VALUES` for
`newItemStateMode = INCLUDE_IF_AVAILABLE` to populate event payloads;
otherwise `data()` is `null`. The SDK fans out across Spanner partition
tokens internally. The Spanner emulator
does **not** support change streams — exercise the feature against a real
Spanner instance.

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

// Single call - SDK handles parallel creation internally
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
