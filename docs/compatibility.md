# Portable API Surface

The Multicloud DB SDK exposes provider-neutral operations and capability-gated
features. Use `client.capabilities()` before optional operations; unsupported
calls fail locally with `UNSUPPORTED_CAPABILITY`.

---

## Portable Common Contract

Create, read, upsert, delete, and query form the common every-provider contract.
Shallow partial `update()` is a separate, capability-gated operation; it is not
part of that common contract.

### Point Operations

| Operation | Description |
|-----------|-------------|
| **Create** | Insert a new document (fails if the key already exists) |
| **Read** | Point-read by partition key + sort key |
| **Upsert** | Create or fully replace; creates the item when it is missing |
| **Delete** | Remove by key (idempotent — silent on missing; use `read()` to detect a missing key, since `read()` returns `null` on every provider) |

Portable query and paging behavior is described in
[Query - Portable Expression DSL](#query---portable-expression-dsl).

### Capability-Gated Partial Update

Callers must check `Capability.PARTIAL_UPDATE` before using `update()`. Cosmos DB
and DynamoDB advertise it in this release; the current Spanner provider does
not, and a valid Spanner call is rejected by the shared client before provider
I/O. For advertising providers, `update()` never creates a missing item. It
replaces supplied top-level values atomically, preserves omitted fields, and
treats map/list values as complete top-level replacements. Non-null update TTL
and maps above 10 fields are rejected before provider I/O with `INVALID_REQUEST`.
The same shared preflight limits every replacement value to 31 nested map/list
containers (top-level replacement container = level 1), rejects binary values, cyclic graphs, and non-collection iterables,
caps every field name at 50,000 UTF-8 bytes, and independently caps both
serialized input and its native-style structural footprint at 390 KiB.
The 50,000-byte UTF-8 limit is intentionally no larger than the AWS SDK's
50,000-character DynamoDB response-parser limit; measuring bytes is the stricter
portable rule for multibyte names and guarantees the parser's character ceiling
is not exceeded. Violations use stable reason and actual/maximum limit details
with `provider=null`.

Non-reserved update names are literal and case-sensitive. `foo` and `Foo` remain
separate fields even when both occur in one atomic update. Names matching `id`,
`partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`
case-insensitively, and names beginning with `_`, fail shared preflight before
provider I/O.

Each built-in provider exposes 18 effective capability rows: Cosmos DB and
DynamoDB explicitly declare all 18, while Spanner declares 17 and
`CapabilitySet` supplies the unsupported default only for the omitted core
`PARTIAL_UPDATE` capability. Unrelated omitted capability names remain absent.

| Provider | `PARTIAL_UPDATE` | Native mechanism / request count | Portable contract and native boundary |
|----------|:----------------:|----------------------------------|---------------------------------------|
| Cosmos DB | ✅ | One `patchItem` for up to 10 fields | Portable only when serialized JSON and structural footprint are each <= 390 KiB; larger state-dependent results are outside the contract and remain subject to the 2 MiB native item limit |
| DynamoDB | ✅ | One conditional aliased `UpdateItem SET` for up to 10 fields | Portable only when serialized JSON and structural footprint are each <= 390 KiB; larger state-dependent results are outside the contract and remain subject to the 400 KiB native item limit |
| Spanner | ❌ (API default) | No provider call; rejected by the shared capability gate | Zero Spanner I/O |

The base capability covers results whose serialized JSON and portable structural
footprint are each at or below 390 KiB. Complete create/upsert documents share the
same structural, nesting, name, and binary-value preflight. Their top-level names
must be unique ignoring case and contain at most 128 Unicode characters; nested
names retain the 50,000-byte UTF-8 limit. Shared preflight also rejects a null
complete document, top-level names matching `id`, `partitionKey`, `sortKey`,
`ttl`, `ttlExpiry`, or `data` case-insensitively, and top-level names beginning
with `_`. Values must be serializable with the SDK-owned Jackson configuration;
caller-registered modules are not consulted. One bounded serialization creates
the detached normalized provider input, including POJO values. Binary values
hidden inside POJOs are rejected. The shared layer keeps all six serialized,
structural, name, nesting, and partial-update limits internal rather than
exposing compile-time Java constants.
The structural preflight covers only incoming replacements; it does not read and
merge existing state. A result above either 390 KiB bound is outside this
release's portable contract and may succeed or fail under native provider
limits. Native size rejections remain non-retryable
`UNSUPPORTED_CAPABILITY`, use the structured errors below, and follow at most
one attempted atomic write.

TTL timing is outside this release's portable partial-update contract.
DynamoDB `UpdateItem` happens to leave `ttlExpiry` unchanged, while Cosmos DB
`patchItem` advances `_ts` and restarts relative TTL. Until this behavior is
normalized, callers requiring a fixed absolute expiry must not call `update()`
on TTL-bearing items.

Follow-up normalization is tracked in [#113](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/113) for absolute TTL expiry and [#114](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/114) for state-dependent resulting size.

A valid Spanner `update()` call returns non-retryable `UNSUPPORTED_CAPABILITY`
with `capability=partial_update`. Shared invalid-request validation still runs
first and remains provider-neutral.

For full replacement, use `upsert()` with the complete document. It creates a
missing item; read-then-upsert is not an atomic update-only replacement and can
recreate an item deleted or expired between the calls. This release has no exact
portable atomic full-document replace-if-present equivalent.

Read and query results omit adapter-injected storage fields. Cosmos strips
`id`, `partitionKey`, `ttl`, and its underscore-prefixed system metadata;
DynamoDB strips `partitionKey`, `sortKey`, and `ttlExpiry`; Spanner strips
`partitionKey`, `sortKey`, and its internal `data` metadata column. Read metadata
remains available through `DocumentMetadata` when requested.

### Query - Portable Expression DSL
Write a WHERE-clause filter once. The SDK translates it to the native query
language of whichever provider is configured - Cosmos SQL, DynamoDB PartiQL,
or Spanner GoogleSQL.

| Feature | Operators / Functions | Example |
|---------|----------------------|---------|
| Comparison | `=`, `!=`, `<`, `>`, `<=`, `>=` | `status = 'active'` |
| Logical | `AND`, `OR`, `NOT` | `age > 18 AND active = true` |
| String functions | `STARTS_WITH`, `CONTAINS` | `STARTS_WITH(name, 'A')` |
| Field introspection | `FIELD_EXISTS` | `FIELD_EXISTS(metadata)` |
| Length functions | `STRING_LENGTH`, `COLLECTION_SIZE` | `STRING_LENGTH(name) > 3` |
| Named parameters | `@paramName` | `price > @minPrice` |

```java
QueryRequest query = QueryRequest.builder()
    .expression("STARTS_WITH(name, @prefix) AND age >= @minAge")
    .parameter("prefix", "J")
    .parameter("minAge", 21)
    .maxPageSize(50)
    .build();

QueryPage page = client.query(address, query);
```

### Pagination

| Feature | Description |
|---------|-------------|
| **Cursor-based paging** | Continuation-token pagination across all providers |
| **Page size control** | `maxPageSize` to limit results per page |

### Data Management

| Feature | Description |
|---------|-------------|
| **Schema provisioning** | `provisionSchema()` creates provider-compatible standard-schema databases, containers, and tables |
| **Transactions** | Multi-document transactional operations |
| **Batch operations** | Batch read/write for throughput efficiency |
| **Strong consistency** | Strongly-consistent reads |
| **Change feed** | Change feed / change streams — see [guide.md - Change Feeds](guide.md#change-feeds) |

### Diagnostics & Error Handling

| Feature | Description |
|---------|-------------|
| **Structured diagnostics** | Latency, request charge, and provider correlation IDs per operation |
| **Portable error categories** | All provider exceptions mapped to `MulticloudDbErrorCategory` |
| **Capability introspection** | `client.capabilities()` reports what the current provider supports |

---

## Portable Error Mapping

All provider exceptions are mapped to portable `MulticloudDbErrorCategory` values.
The raw HTTP or gRPC status code is also available via `error.statusCode()`.

| Category  | Cosmos DB  | DynamoDB  | Spanner  |
|-----------|------------|-----------|----------|
| `INVALID_REQUEST`  | HTTP 400  | ValidationException, HTTP 400, except the update result-item-size variant below  | INVALID_ARGUMENT, FAILED_PRECONDITION  |
| `AUTHENTICATION_FAILED`  | HTTP 401  | UnrecognizedClientException, HTTP 401/403  | UNAUTHENTICATED  |
| `AUTHORIZATION_FAILED`  | HTTP 403  | AccessDeniedException  | PERMISSION_DENIED  |
| `NOT_FOUND`  | HTTP 404  | ResourceNotFoundException, HTTP 404, or `ConditionalCheckFailedException` from the `update()` existence guard  | NOT_FOUND  |
| `CONFLICT` (409 - duplicate key)  | HTTP 409  | `ConditionalCheckFailedException` from `create()` - `attribute_not_exists` guard fails when the item already exists  | ALREADY_EXISTS  |
| `CONFLICT` (412 - precondition)  | HTTP 412  | Other conditional-write precondition failures¹  | ABORTED  |
| `THROTTLED`  | HTTP 429  | ProvisionedThroughputExceededException, ThrottlingException  | RESOURCE_EXHAUSTED  |
| `TRANSIENT_FAILURE` | `update()` HTTP 408/410 (substatus retained), plus HTTP 449/500/502/503 | `update()` `RequestTimeout`/`RequestTimeoutException` and SDK API-call or attempt timeout, plus HTTP 500–5xx | UNAVAILABLE |
| `PERMANENT_FAILURE`  | -  | ItemCollectionSizeLimitExceededException  | -  |
| `UNSUPPORTED_CAPABILITY`  | HTTP 400 with AVAD-not-enabled fingerprint (`providerDetails.reason="avad_not_enabled"`); update HTTP 413 (`reason="cosmos_result_item_size_limit"`, native ceiling in `maximumResultBytes`)  | `InvalidArgumentException` / `ResourceNotFoundException` for streams not enabled (`reason="stream_not_enabled"`); update result-item-size `ValidationException` (`reason="dynamodb_result_item_size_limit"`, native ceiling in `maximumResultBytes`)  | UNIMPLEMENTED, change-stream-not-provisioned (`reason="stream_not_enabled"`), valid `update()` calls are rejected by the shared pre-I/O capability gate with non-retryable `UNSUPPORTED_CAPABILITY` (`providerDetails.capability="partial_update"`) and no provider-specific `reason`  |
| `CURSOR_EXPIRED` (change-feed) | HTTP 410 GONE (`reason="PROVIDER_TRIMMED"`)  | `TrimmedDataAccessException` (`reason="PROVIDER_TRIMMED"`), `ExpiredIteratorException` (`reason="ITERATOR_EXPIRED"`)  | `INVALID_ARGUMENT` / `OUT_OF_RANGE` / `NOT_FOUND` for partition outside retention (`reason="PROVIDER_TRIMMED"`)  |
| `PROVIDER_ERROR`  | Other  | Other  | INTERNAL, Other  |

> ¹ DynamoDB uses `ConditionalCheckFailedException` for several condition
> failures. The SDK maps the specific `update()` existence guard to `NOT_FOUND`;
> duplicate `create()` remains `CONFLICT`. The portable API does not yet expose
> ETag-based conditional updates.

Cosmos DB's result-item-size capability error follows one attempted
`patchItem`. DynamoDB's follows one attempted `UpdateItem`; expression
overflow remains a local zero-I/O rejection. No read/merge preflight is
performed. Cosmos HTTP 413 from other operations retains the general provider
mapping, and other Dynamo `ValidationException` messages remain
`INVALID_REQUEST`.

Change-feed HTTP 410 remains `CURSOR_EXPIRED`; the transient Cosmos 410 mapping
above is restricted to `update()`. Supported Cosmos and DynamoDB partial updates
normalize native timeout equivalents to retryable `TRANSIENT_FAILURE`. Retryability
covers replay of logical field assignments, not provider-maintained metadata or TTL
timing; another Cosmos patch advances `_ts`. Partial update has no
transactional-batch execution path.

## Change-Feed History Retention

The portable change-feed read path guarantees a **24-hour** history floor on
every provider out of the box — a cursor token minted by `ChangeFeedCursor#toToken()`
can be replayed for 24 hours regardless of which provider produced it.

To request a longer server-side retention window, opt in via
`ChangeFeedConfig.builder().extendedRetention(Duration)` on
`MulticloudDbClientConfig`. The SDK fails fast at client-build time with
`UNSUPPORTED_CAPABILITY` (`reason=extended_retention_unavailable`) if the
target provider does not declare the `EXTENDED_CHANGE_FEED_HISTORY` capability.

| Provider | Declares `EXTENDED_CHANGE_FEED_HISTORY` | How it is honoured | Practical ceiling |
|---|---|---|---|
| Cosmos DB | ✅ | `ensureContainer()` provisions an AVAD `ChangeFeedPolicy` carrying the requested retention. The account must have Continuous Backup enabled; the SDK normalises the "continuous backup required" failure to `UNSUPPORTED_CAPABILITY` (`reason=continuous_backup_required`). | Up to **30 days** on a Continuous Backup 30-day tier; 7 days is the most common ceiling. |
| Spanner | ✅ | `ensureContainer()` emits `CREATE CHANGE STREAM <table>_changes FOR <table> OPTIONS (value_capture_type = 'NEW_ROW', retention_period = '<value>')` after the table-create (the `NEW_ROW` capture type matches what the SDK's change-feed reader requires for full-row payloads). Requests beyond the database's native maximum are normalised to `UNSUPPORTED_CAPABILITY` (`reason=retention_exceeds_native_max`). If a stream of the same name already exists with a different retention, `ensureContainer()` reads back the active retention via `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and surfaces the mismatch as `UNSUPPORTED_CAPABILITY` (`reason=extended_retention_not_enacted`) so the divergence cannot be silently swallowed. | **7 days** natively; up to **1 year** only on a database explicitly configured for extended retention. |
| DynamoDB | ❌ | DynamoDB Streams is fixed at 24 h server-side. Calling `client(...).provisionSchema(...)` (or any container-create call) with an `extendedRetention` opt-in fails fast at client-build time. | Drain Streams into a customer-provisioned Kafka cluster (outside the SDK) for >24 h today. SDK-managed archive-on-read via Kafka (customer-provisioned brokers) is on the v1.x roadmap. |

**Cost is provider-shaped** — extending the change-feed history window changes
your bill differently on each provider; the windows are not interchangeable.
See `docs/guide.md` → *"Extending change-feed history beyond 24 hours"* for the
per-provider price-driver detail before opting in.
## Default Sort-Key Ordering

All Cosmos DB and DynamoDB query paths return results sorted by the document's
sort key ascending.

> **Design note:** The default `ORDER BY` is applied to **all** Cosmos queries
> (both partition-scoped and cross-partition), not just partition-scoped ones.
> This gives the strongest consistency guarantee: every query, on every provider,
> returns items sorted by sort key. The early PR description mentioned
> partition-scoped only as a starting point; the final implementation was
> intentionally broadened to cover all queries.

### Cosmos DB

Cosmos DB appends `ORDER BY c.id ASC` to every query that does not already carry
an explicit `ORDER BY` clause (and is not an aggregate / `GROUP BY` query). This
is applied server-side, so the order is globally consistent across all pages.

> **⚠️ Custom indexing policy - composite index required**
> If your Cosmos container uses a **custom indexing policy** that does not include
> a composite index on `(filterField ASC, id ASC)`, Cosmos DB will throw a
> `400 Bad Request` at runtime for cross-partition queries that combine `WHERE` and
> the default `ORDER BY c.id ASC`. The default indexing policy includes all paths
> and supports this automatically. If you have tuned your indexing policy, add the
> composite index for every field you filter on:
> ```json
> { "compositeIndexes": [ [{ "path": "/filterField", "order": "ascending" },
>                          { "path": "/id", "order": "ascending" }] ] }
> ```
>
> **⚠️ RU cost**
> Appending `ORDER BY c.id ASC` to all Cosmos queries incurs an additional RU
> charge versus unordered queries, proportional to result-set size. This cost is
> the price of cross-provider consistency and is expected behavior.
>
> **⚠️ Aggregates and GROUP BY**
> Cosmos DB rejects `ORDER BY` on aggregate expressions (`COUNT`, `SUM`, `MIN`,
> `MAX`, `AVG`) and `GROUP BY` queries. The SDK automatically detects these patterns
> and omits the default `ORDER BY` for them.

### DynamoDB

DynamoDB results are sorted in memory per page after fetching (client-side).
Within a single page, items are returned sorted by sort key ascending.
For multi-page scans the overall order across pages is determined by DynamoDB's
internal token-based traversal, not sort key - this is a known limitation.

### Spanner

The Spanner provider does not yet implement default sort-key ordering.
Consumers relying on consistent cross-provider sort behavior should not use
the Spanner provider until this gap is addressed.

> **Tracking**: A follow-up issue will be filed to implement default sort-key
> ordering for the Spanner provider. Until resolved, do not mix Spanner with
> Cosmos or DynamoDB in conformance-sensitive workloads.

## Escape Hatch Policy

The SDK does not expose a `nativeClient()` method. Direct access to the
underlying provider client is intentionally omitted to enforce portability
guarantees - code written against the SDK must remain switchable between
providers by configuration alone.
