# Configuration Reference

All configuration flows through `MulticloudDbClientConfig` or a `.properties` file.
Select a provider and supply its connection and auth properties.

---

## Common Properties

| Property | Description | Example |
|----------|-------------|---------|
| `multiclouddb.provider` | Provider ID | `cosmos`, `dynamo`, `spanner` |
| `multiclouddb.feature.*` | Feature flags | Provider-specific opt-ins |

## Partial Update and Operation Options

Partial update has no provider-specific configuration switch. Cosmos DB and
DynamoDB declare `Capability.PARTIAL_UPDATE`, and `update()` uses shallow
top-level set/replace semantics for those providers. The Spanner
provider omits this capability, so API normalization supplies the unsupported default
and the shared client rejects a valid update before provider I/O.

`OperationOptions.ttlSeconds()` applies only to `create()` and `upsert()`, and
is honored only when the selected provider advertises
`Capability.ROW_LEVEL_TTL`. Cosmos DB and DynamoDB advertise that capability.
Unsupported providers, including the current Spanner provider, ignore the value
and store the document without expiry. Callers that require expiry must check
`ROW_LEVEL_TTL` before writing.

Supplying any non-null `ttlSeconds` to `update()` returns non-retryable
`INVALID_REQUEST` before provider I/O on every provider.

The portable 10-field limit, 31-level replacement-value nesting limit, and
390 KiB serialized/structural input limits are not configurable or exposed as
compile-time Java constants. Runtime discovery and customer configuration are
tracked in [#116](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/116).

Native result-item ceilings are also not configurable:

| Provider | Partial-update envelope | Observed TTL behavior (outside portable contract) |
|----------|-------------------------|--------------------------------------------------|
| Cosmos DB | One direct patch for up to 10 fields; resulting document subject to the Cosmos DB native ceiling after the attempted update | Not preserved: `patchItem` advances `_ts` and restarts the TTL countdown |
| DynamoDB | One `UpdateItem` for up to 10 fields; resulting item subject to the DynamoDB native ceiling after the attempted update | Preserved: `UpdateItem` leaves `ttlExpiry` unchanged |
| Spanner | Omits `PARTIAL_UPDATE`; the API supplies the unsupported default | Not reached |

Shared write preflight rejects binary values, cyclic graphs, non-collection
iterables, and over-limit field names. Partial-update and nested names are capped
at 50,000 UTF-8 bytes. Complete-document top-level names are capped at 128
Unicode characters and must be unique ignoring case. The 31-level count starts
at level 1 when a supplied top-level value is itself a map or list. Structural
footprint includes UTF-8 field names and native map/list overhead. Shared
violations return non-retryable `INVALID_REQUEST` before the capability gate or
provider I/O, and providers receive the detached bounded snapshot rather than
caller-owned nested values.

Cosmos and Dynamo report `Capability.PARTIAL_UPDATE=true`. Case-distinct
non-reserved field names are part of the core partial-update contract: `foo` and `Foo` remain
separate even in one atomic request. Update fields matching `id`, `partitionKey`,
`sortKey`, `ttl`, `ttlExpiry`, or `data` case-insensitively, and names beginning
with `_`, fail before I/O. Complete create/upsert documents apply the same
top-level provider-owned-name rule. Provider-native limits are reported through
structured error reasons and limit values. When a provider omits
`PARTIAL_UPDATE`, the API supplies an unsupported default; a valid Spanner
update is rejected by the shared core gate before any Spanner I/O.

Portable partial-update behavior is guaranteed only when both the resulting
logical document's serialized JSON and portable structural footprint are at or
below 390 KiB. A state-dependent result above either bound is outside this
release's portable contract and may succeed or fail under native provider
limits. No read/merge preflight is performed; a native result-size rejection is
non-retryable `UNSUPPORTED_CAPABILITY`, reason-coded, and follows at most one
write attempt.

TTL timing is outside this release's portable partial-update contract.
DynamoDB `UpdateItem` happens to leave `ttlExpiry` unchanged, while Cosmos DB
`patchItem` advances `_ts` and restarts relative TTL. Until this behavior is
normalized, callers requiring a fixed absolute expiry must not call `update()`
on TTL-bearing items.

`CapabilitySet` supplies an unsupported default only for an omitted
`PARTIAL_UPDATE` declaration. Every built-in provider exposes 18 effective rows:
Cosmos DB and DynamoDB explicitly declare 18, while Spanner declares 17 plus
that one default. Unrelated omitted names remain absent.

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
> (see [Compatibility — Default Sort-Key Ordering](compatibility.md#default-sort-key-ordering)).
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
| `multiclouddb.connection.changeStream.<collection>` | Per-collection change-stream name override (optional). Defaults to `<collection>_changes`. **By default** the change stream must be provisioned out of band with `CREATE CHANGE STREAM <name> FOR <collection> OPTIONS (value_capture_type='NEW_ROW')` — the SDK does not create change streams. **Exception:** when the caller opts in via `MulticloudDbClientConfig.changeFeed(ChangeFeedConfig.builder().extendedRetention(d).build())`, `ensureContainer(...)` emits the DDL itself (including `OPTIONS (value_capture_type = 'NEW_ROW', retention_period = '…')`) and resolves the stream name through this override so producer and reader stay in sync. See `docs/guide.md` → *"Extending change-feed history beyond 24 hours"*. |

---

## Change-Feed Configuration (opt-in)

Beyond the per-collection `changeStream.<collection>` *connection* override
above, change-feed–specific behavioural knobs live on
`MulticloudDbClientConfig.changeFeed(ChangeFeedConfig)`:

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.SPANNER)
    .changeFeed(ChangeFeedConfig.builder()
        .extendedRetention(Duration.ofDays(7))  // > 24 h; opt-in only
        .build())
    .build();
```

| Setter | Default | Purpose |
|---|---|---|
| `ChangeFeedConfig.Builder.extendedRetention(Duration)` | unset (24 h portable baseline) | Requests server-side change-feed history beyond 24 h. Must be strictly > 24 h. Fails fast at client-build time with `UNSUPPORTED_CAPABILITY(reason="extended_retention_unavailable")` on providers that do not declare `Capability.EXTENDED_CHANGE_FEED_HISTORY` (Dynamo). See `docs/guide.md` → *"Extending change-feed history beyond 24 hours"* for the per-provider price drivers, ceilings, and substrate-provisioning semantics. |

Callers that never invoke `changeFeed(...)` get the portable 24-hour baseline
on every provider — the default `ChangeFeedConfig.defaults()` is the cached
no-op singleton, so the on-the-wire behaviour is bit-for-bit identical to a
client built without this knob.

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
    "app-db", List.of("tenants", "portfolios", "positions", "risk_metrics")
);

// Single call - SDK handles parallel creation internally
client.provisionSchema(schema);
```

| Provider | Database Phase | Container/Table Phase |
|----------|---------------|----------------------|
| **Cosmos DB** | Uses data-plane database creation; the caller needs creation permission | Creates containers in parallel via the data-plane SDK |
| **DynamoDB** | No-op (no native database concept) | Creates tables in parallel, waits for ACTIVE |
| **Spanner** | Creates the configured database; emulator mode also creates the configured instance if absent, while production requires the instance to pre-exist | Creates tables in parallel |

For cross-provider provisioning, the schema map must use the Spanner client's
configured `databaseId` as its single database entry. Cosmos DB and DynamoDB
can represent multiple logical database entries; one Spanner client cannot
provision databases other than its configured database.

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
