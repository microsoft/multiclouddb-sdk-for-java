# API Reference

The Multicloud DB SDK publishes Javadoc for the public API surface.

---

## Core Package: `com.multiclouddb.api`

The `multiclouddb-api` module is the only compile-time dependency your
application needs. All types in `com.multiclouddb.api.*` are the portable
contract.

### Key Types

| Type | Description |
|------|-------------|
| `MulticloudDbClient` | The main client interface - CRUD, query, provisioning, and capabilities |
| `MulticloudDbClientFactory` | Creates a client by discovering providers via `ServiceLoader` |
| `MulticloudDbClientConfig` | Builder-pattern configuration for provider, connection, and auth |
| `ResourceAddress` | A `(database, collection)` pair targeting a container/table |
| `MulticloudDbKey` | A `(partitionKey, sortKey)` identity for every document |
| `QueryRequest` | Query input with expression, parameters, pagination, and partition scoping |
| `QueryPage` | Query result: items, continuation token, and diagnostics |
| `DocumentResult` | Read result: document payload and optional metadata |
| `DocumentMetadata` | Write timestamps, TTL expiry, and version/ETag |
| `CapabilitySet` | Runtime introspection of supported provider capabilities |
| `MulticloudDbException` | Structured error with portable error category |
| `OperationOptions` | Per-operation timeout, TTL, and metadata controls |
| `OperationDiagnostics` | Latency, request charge, request ID, and item count |

### Query Expression Types

| Type | Description |
|------|-------------|
| `Expression` | AST node interface for parsed query expressions |
| `ExpressionParser` | Parses portable expression strings into an AST |
| `ExpressionValidator` | Validates parameter bindings and function signatures |
| `ExpressionTranslator` | SPI - translates AST to provider-native query syntax |
| `TranslatedQuery` | Translation result: query string + bound parameters |

### Change-Feed Types (`com.multiclouddb.api.changefeed`)

| Type | Description |
|------|-------------|
| `ChangeFeedCursor` | Opaque, immutable position in a change feed. `now()` mints a sentinel at the live tip; `toToken()` / `fromToken(String)` persist it. |
| `ChangeFeedPage` | A page of change events plus `nextCursor`, `hasMore`, and `terminal` flags. |
| `ChangeEvent` | A single change with `key`, `type`, `commitTimestamp`, `data`, and `providerEventId`. |
| `ChangeType` | Enum: `CREATE`, `UPDATE`, `DELETE`. |
| `CursorExpiredException` | Thrown when a cursor cannot be honoured (trimmed, aged-out, mismatched). `error().providerDetails().get("reason")` carries the cause. |
| `ChangeFeedConfig` | Immutable opt-in configuration for change-feed retention. `builder().extendedRetention(Duration)` requests server-side history > 24 h (provider-gated; see `Capability.EXTENDED_CHANGE_FEED_HISTORY`). `defaults()` is the cached no-op singleton; bit-for-bit identical v1 behaviour when not set. |

See [guide.md - Change Feeds](guide.md#change-feeds) for the full workflow,
provisioning prerequisites, and multi-thread pattern.

---

## Building Javadoc Locally

Generate the full API documentation with:

```bash
mvn javadoc:javadoc -pl multiclouddb-api
```

The generated HTML is written to:

```
multiclouddb-api/target/apidocs/index.html
```

Open this file in a browser to explore the full Javadoc with cross-references
and search.

---

## SPI Package: `com.multiclouddb.spi`

Provider implementors use the SPI interfaces - application code should not
import from this package.

| Type | Description |
|------|-------------|
| `MulticloudDbProviderAdapter` | Factory SPI - creates a provider client from config |
| `MulticloudDbProviderClient` | Implementation SPI - CRUD + query + provisioning |
| `SdkUserAgent` | Builds the canonical user-agent header token |
