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
| `MulticloudDbClient` | The main client interface - point operations, capability-gated partial update, query, provisioning, and capabilities |
| `MulticloudDbClientFactory` | Creates a client by discovering providers via `ServiceLoader` |
| `MulticloudDbClientConfig` | Builder-pattern configuration for provider, connection, and auth |
| `ResourceAddress` | A `(database, collection)` pair targeting a container/table |
| `MulticloudDbKey` | A `(partitionKey, sortKey)` identity for every document |
| `QueryRequest` | Query input with expression, parameters, pagination, and partition scoping |
| `QueryPage` | Query result: items, continuation token, and diagnostics |
| `DocumentResult` | Read result: document payload and optional metadata |
| `DocumentMetadata` | Independently nullable provider metadata: write timestamp, TTL expiry, and version/ETag |
| `CapabilitySet` | Runtime introspection of supported provider capabilities |
| `MulticloudDbException` | Structured error with portable error category |
| `OperationOptions` | Per-operation timeout and metadata controls; `ttlSeconds` is create/upsert-only |
| `OperationDiagnostics` | Latency, request charge, request ID, and item count |

### `update()` Partial-Update Contract

```java
void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields,
    OperationOptions options);
```

- Sets or replaces only the supplied top-level fields; omitted fields remain.
- Map/list values replace the complete top-level value. Java `null` stores null.
- Values are inspected and serialized with the SDK-owned Jackson configuration
  during bounded shared preflight.
- A missing item returns `NOT_FOUND` and is not created.
- Non-null `options.ttlSeconds()` returns pre-I/O, non-retryable
  `INVALID_REQUEST`.
- At most 10 fields may be supplied per call.
- Non-reserved names are literal and case-sensitive: `foo` and `Foo` may both
  appear in one atomic update and remain separate fields. Names matching `id`,
  `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`
  case-insensitively, and names beginning with `_`, are rejected before I/O.
- Binary values are rejected, including when exposed by a POJO. Cyclic graphs
  and non-collection iterables are rejected, and serialized JSON output is capped
  while it is produced.
- Every field name, including nested map keys, is limited to 50,000 UTF-8 bytes.
- The serialized field map and its structural footprint must each be at most 390 KiB.
  Structural footprint includes UTF-8 names plus native map/list container and element overhead.
- Each replacement value may contain at most 31 nested map/list containers, counting its
  top-level container as level 1. A shallow update path does not make the replacement value flat.

`Capability.PARTIAL_UPDATE` is supported by Cosmos DB and DynamoDB. The
Spanner provider omits it, so API normalization supplies the unsupported default and
the default client returns non-retryable `UNSUPPORTED_CAPABILITY` with
`capability=partial_update` before provider delegation.

Cosmos and Dynamo preserve literal field case as part of the base
`PARTIAL_UPDATE` contract. Portable behavior is guaranteed only when both the
resulting logical document's serialized JSON and portable structural footprint
remain at or below 390 KiB. A state-dependent result above either bound is
outside this release's portable contract and may succeed or fail under native
provider limits.

TTL timing is outside the portable partial-update contract. DynamoDB
`UpdateItem` happens to leave `ttlExpiry` unchanged, while Cosmos DB
`patchItem` advances `_ts` and restarts relative TTL. Until this behavior is
normalized, callers requiring a fixed absolute expiry must not call `update()`
on TTL-bearing items.

Follow-up normalization is tracked in [#113](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/113) for absolute TTL expiry and [#114](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/114) for state-dependent resulting size.

Each built-in provider exposes 18 effective capability rows. Cosmos DB and
DynamoDB explicitly declare all 18; Spanner declares 17 and `CapabilitySet`
supplies only the omitted core `PARTIAL_UPDATE` unsupported default. Unrelated
omitted capability names remain absent.

Shared name-size, binary-value, unsafe-graph, nesting, and
structural-footprint failures return non-retryable
`INVALID_REQUEST` before provider I/O. Their stable reasons include `partial_update_field_name_size_limit`,
`non_portable_binary_value`, `non_portable_iterable`,
`partial_update_value_cycle`, and `partial_update_nesting_depth_limit` (with `valuePath`, `actualNestingDepth`, and
`maximumNestingDepth`) and `partial_update_structural_footprint_limit` (with
`actualPortableFootprintBytes` and `maximumPortableFootprintBytes`).

No read/merge preflight is performed. Native result-size rejections remain
non-retryable `UNSUPPORTED_CAPABILITY` errors with stable
`providerDetails.reason` and limit values: `cosmos_result_item_size_limit` or
`dynamodb_result_item_size_limit`. Other Dynamo `ValidationException` errors remain
`INVALID_REQUEST`.

For complete replacement, call `upsert()` with the complete desired document.
`upsert()` creates a missing item, so it is not an update-only replacement.
Read-then-upsert is not atomic, and this release has no exact portable atomic
full-document replace-if-present equivalent.
See [guide.md - update](guide.md#update---partial-update-existing) for native
request counts, costs, and migration guidance.

### Complete-Document Write Contract

`create()` and `upsert()` reject a null document, top-level names matching `id`,
`partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data` case-insensitively,
top-level names beginning with `_`, binary values (including values hidden in
POJOs), cyclic graphs, non-collection iterables, and over-limit names before
provider I/O. Complete-document top-level names must be unique ignoring case and
contain at most 128 Unicode characters; nested names remain limited to 50,000
UTF-8 bytes. Values must be serializable with the SDK-owned Jackson configuration;
caller-registered modules are not consulted. One bounded serialization produces
a detached normalized snapshot, and that exact snapshot is delegated. Serialized
UTF-8 input and structural footprint have separate 390 KiB limits, with at most
31 map/list containers below the document root. These limits are enforced
internally and are not exposed as compile-time Java constants. Violations are
non-retryable `INVALID_REQUEST` and include structured limit details.

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
| `MulticloudDbProviderClient` | Implementation SPI - point operations, capability-gated partial update, query, and provisioning |
| `SdkUserAgent` | Builds the canonical user-agent header token |
