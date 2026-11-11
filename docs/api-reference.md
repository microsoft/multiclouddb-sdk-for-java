# API Reference

The Multicloud DB SDK publishes Javadoc for the public API surface.

---

## Core Package: `com.multiclouddb.api`

The `multiclouddb-api` module is the only compile-time dependency your
application needs. All types in `com.multiclouddb.api.*` are the portable
contract.

This reference describes the development Map API. Use the exact source-installed
[module versions](getting-started.md#2-add-dependencies), not older beta
dependencies with these signatures. Additional application libraries are needed
only when your own mapping or cursor JSON callbacks use them.

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
| `DocumentResult` | Point-read result: mutable detached `Map<String, Object>` and optional metadata; `read()` itself returns null if the item is missing |
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
| `ChangeFeedCursor` | Opaque, immutable position in a change feed. `now()` mints a sentinel at the live tip; `toToken(serialize)` / `fromToken(token, deserialize)` persist it. |
| `ChangeFeedPage` | A page of change events plus `nextCursor`, `hasMore`, and `terminal` flags. |
| `ChangeEvent` | A single change with `key`, `type`, `commitTimestamp`, `data`, and `providerEventId`. |
| `ChangeType` | Enum: `CREATE`, `UPDATE`, `DELETE`. |
| `CursorExpiredException` | Thrown when a cursor cannot be honoured (trimmed, aged-out, mismatched). `error().providerDetails().get("reason")` carries the cause. |
| `ChangeFeedConfig` | Immutable opt-in configuration for change-feed retention. `builder().extendedRetention(Duration)` requests server-side history > 24 h (provider-gated; see `Capability.EXTENDED_CHANGE_FEED_HISTORY`). `defaults()` is the cached no-op singleton; bit-for-bit identical v1 behaviour when not set. |

See [guide.md - Change Feeds](guide.md#change-feeds) for the full workflow,
provisioning prerequisites, and multi-thread pattern.

### Payloads, ownership and JSON callbacks

- Writes take non-null `Map<String, Object>` with the
  [supported neutral values](guide.md#supported-values-and-numeric-precision).
  Do not mutate inputs during a synchronous call; the SDK does not retain them.
- `QueryPage.items()` is `List<Map<String, Object>>`. Its list and row Maps are
  shallow read-only copies; nested containers remain mutable. Query parameter
  coercion is not the document-write validation contract.
- `ChangeEvent.data()` returns Object. Null means unavailable, while a supplied
  empty Map remains a payload. There is no `hasData()` member or completeness
  guarantee. Provider-returned payload containers are detached and mutable.
  Direct `DocumentResult`/`ChangeEvent` constructors retain their supplied data.
- Cursor persistence takes `Function<Object, String>` and restoration takes
  `BiFunction<String, Class<?>, Object>` with `Object.class` as the target.
  No client-level serializer configuration or SDK POJO adapter is implemented.
  See the [callback compatibility and error contract](guide.md#application-owned-json-processing):
  parse processing failures become `CURSOR_EXPIRED/MALFORMED`; callback/result
  contract failures are sanitized `IllegalStateException`s.

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
