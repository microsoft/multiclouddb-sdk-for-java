# Portable API Surface

The Multicloud DB SDK separates a **common Lowest-Common-Denominator (LCD)
baseline** from optional capability-gated extensions. The baseline targets
Azure Cosmos DB, Amazon DynamoDB, and Google Cloud Spanner. Optional extensions
may have different provider support and must declare that difference explicitly.

`EXTENDED_CHANGE_FEED_HISTORY` remains a supported opt-in on Cosmos and Spanner
and an explicitly unsupported extension on DynamoDB. Its factory gate reports
`UNSUPPORTED_CAPABILITY` before I/O when requested on an unsupported provider.
Native query passthrough via `QueryRequest.nativeExpression()` remains available.
Per-document TTL and opt-in read metadata remain available with the provider
differences described below.

---

## What Works Everywhere

The following sections describe the common baseline. Provider prerequisites and
optional-extension gates still apply; see the retention support matrix below.

### CRUD Operations

| Operation | Description |
|-----------|-------------|
| **Create** | Insert a new document (fails if the key already exists) |
| **Read** | Point-read by partition key + sort key |
| **Update** | Replace an existing document (fails if not found) |
| **Upsert** | Create or replace - always succeeds |
| **Delete** | Remove by key (idempotent — silent on missing; use `read()` to detect a missing key, since `read()` returns `null` on every provider) |

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
| **Schema provisioning** | `provisionSchema()` creates databases, containers, and tables portably |
| **Transactions** | Multi-document transactional operations |
| **Batch operations** | Batch read/write for throughput efficiency |
| **Strong consistency** | Strongly-consistent reads |
| **Change feed** | Change feed / change streams — see [guide.md - Change Feeds](guide.md#change-feeds) |

### Diagnostics & Error Handling

| Feature | Description |
|---------|-------------|
| **Structured diagnostics** | Latency, request charge, and provider correlation IDs per operation |
| **Portable error categories** | All provider exceptions mapped to `MulticloudDbErrorCategory` |
| **Capability introspection** | `client.capabilities()` declares baseline and optional features, including explicitly unsupported ones |

---

## TTL and Read Metadata

| Capability / field | Cosmos DB | DynamoDB | Spanner |
|--------------------|-----------|----------|---------|
| `ROW_LEVEL_TTL` | Supported; enable container TTL | Supported; enable table TTL on `ttlExpiry` | Unsupported; `ttlSeconds` ignored |
| `WRITE_TIMESTAMP` | Supported | Unsupported | Unsupported |
| `metadata().lastModified()` | `_ts` | null | null |
| `metadata().ttlExpiry()` | null | Stored `ttlExpiry`, if present | null |
| `metadata().version()` | ETag | null | null |

**Implementation gap (FR-057):** The table describes current behavior, not
fulfillment of the required unsupported-TTL error. Spanner currently ignores a
TTL hint and can write without expiry. The
[FR-057 MUST requirement](../specs/001-clouddb-sdk/spec.md#document-ttl-and-write-metadata-requirements)
is unchanged; the fail-fast capability gate remains
[deferred, unimplemented US6 follow-up](../specs/001-clouddb-sdk/tasks.md#phase-16-user-story-6--document-ttl-and-write-metadata-priority-p2).
Check `ROW_LEVEL_TTL` before requesting expiry; do not rely on the SDK to reject
an unsupported TTL request today.

`OperationOptions.ttlSeconds()` applies to create/update/upsert and must be
positive. `includeMetadata(true)` requests metadata on point reads; metadata is
null by default. Unsupported `WRITE_TIMESTAMP` does not disable this read
option: DynamoDB returns available TTL expiry, and Spanner returns an empty
metadata object. A missing document still returns null.
See [TTL setup and write behavior](guide.md#document-ttl-time-to-live) and
[metadata availability](guide.md#provider-metadata-availability).

## Portable Error Mapping

All provider exceptions are mapped to portable `MulticloudDbErrorCategory` values.
The raw HTTP or gRPC status code is also available via `error.statusCode()`.

| Category  | Cosmos DB  | DynamoDB  | Spanner  |
|-----------|------------|-----------|----------|
| `INVALID_REQUEST`  | HTTP 400  | ValidationException, HTTP 400  | INVALID_ARGUMENT, FAILED_PRECONDITION  |
| `AUTHENTICATION_FAILED`  | HTTP 401  | UnrecognizedClientException, HTTP 401/403  | UNAUTHENTICATED  |
| `AUTHORIZATION_FAILED`  | HTTP 403  | AccessDeniedException  | PERMISSION_DENIED  |
| `NOT_FOUND`  | HTTP 404  | ResourceNotFoundException, HTTP 404  | NOT_FOUND  |
| `CONFLICT` (409 - duplicate key)  | HTTP 409  | `ConditionalCheckFailedException` from `create()` - `attribute_not_exists` guard fails when the item already exists  | ALREADY_EXISTS  |
| `CONFLICT` (412 - precondition)  | HTTP 412  | `ConditionalCheckFailedException` from `update()`/`upsert()` with a condition expression¹  | ABORTED  |
| `THROTTLED`  | HTTP 429  | ProvisionedThroughputExceededException, ThrottlingException  | RESOURCE_EXHAUSTED  |
| `TRANSIENT_FAILURE`  | HTTP 449, 500, 502, 503  | HTTP 500–5xx  | UNAVAILABLE  |
| `PERMANENT_FAILURE`  | -  | ItemCollectionSizeLimitExceededException  | -  |
| `UNSUPPORTED_CAPABILITY`  | HTTP 400 with AVAD-not-enabled fingerprint (`providerDetails.reason="avad_not_enabled"`)  | `InvalidArgumentException` / `ResourceNotFoundException` for streams not enabled (`reason="stream_not_enabled"`)  | UNIMPLEMENTED, plus change-stream-not-provisioned (`reason="stream_not_enabled"`)  |
| `CURSOR_EXPIRED` (change-feed) | HTTP 410 GONE (`reason="PROVIDER_TRIMMED"`)  | `TrimmedDataAccessException` (`reason="PROVIDER_TRIMMED"`), `ExpiredIteratorException` (`reason="ITERATOR_EXPIRED"`)  | `INVALID_ARGUMENT` / `OUT_OF_RANGE` / `NOT_FOUND` for partition outside retention (`reason="PROVIDER_TRIMMED"`)  |
| `PROVIDER_ERROR`  | Other  | Other  | INTERNAL, Other  |

> ¹ DynamoDB uses `ConditionalCheckFailedException` for both the 409 (duplicate-key on `create`) and 412
> (precondition failure on conditional `update`/`upsert`) cases - both currently map to `CONFLICT`.
> The portable API does not yet expose ETag-based conditional updates; when it does, the 412-equivalent
> path will be split into a dedicated `PRECONDITION_FAILED` category (tracked in issue #29).

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

---

## Query Extensions and Native Expressions

Query partition scoping remains optional. Cosmos and Spanner support
cross-partition queries; DynamoDB retains its unscoped Scan route but declares
`CROSS_PARTITION_QUERY` unsupported because a scan is not a partition-targeted
query. The builder does not require a partition key.
The unsupported `CROSS_PARTITION_QUERY` and `NATIVE_SQL_QUERY` declarations
do not automatically block DynamoDB's existing Scan and native PartiQL routes.
This is a limitation of the current declaration/legacy-execution contract,
not the fail-fast behavior used for DynamoDB's explicit `orderBy`.

| Capability | Cosmos | DynamoDB | Spanner |
|------------|--------|----------|---------|
| `CROSS_PARTITION_QUERY` | Supported | Unsupported (Scan route retained) | Supported |
| `NATIVE_SQL_QUERY` | Supported | Unsupported (native PartiQL passthrough retained) | Supported |
| `ORDER_BY` | Supported | Unsupported, including explicit `sortKey` | Supported |
| `LIKE_OPERATOR`, `ENDS_WITH`, `REGEX_MATCH`, `CASE_FUNCTIONS` | Supported | Unsupported | Supported |
| `RESULT_LIMIT` | Supported (`TOP N`) | Unsupported as a total cap; per-page limit only | Supported; per-page limit only |

`QueryRequest.nativeExpression()` preserves native query passthrough (Cosmos
SQL, DynamoDB PartiQL, or Spanner GoogleSQL). Native syntax is not portable and
is not validated as the portable expression DSL. `MulticloudDbClient` does not
expose a `nativeClient()` accessor.

`QueryRequest.limit()` has provider-specific semantics. It is not
a portable cumulative limit across continuation tokens; the client does not
truncate returned pages to impose a cumulative cap. Applications must
inspect provider capabilities rather than assume every query option is common.

### Ordering and Pagination Boundaries

Cosmos uses its existing server-side field ordering and default `c.id ASC`
when no caller ordering or aggregate prevents it. DynamoDB retains native
partition Query ordering and page-local ascending sorting on scan/translated
paths; this does not create globally sorted scan pagination or new DESC support.
Spanner retains default partition-key/sort-key ordering and missing-key
tiebreakers, with guards for caller SQL ordering, literals, and aggregates.
Deterministic ordering does not guarantee a snapshot across concurrent writes
or successive page requests.

### Cosmos ORDER BY Indexing and RU Cost

The default `ORDER BY c.id ASC` affects the indexing and cost of queries that
do not supply their own ordering and are not aggregate/GROUP BY queries.
For a custom indexing policy, verify that filter and order-by paths are
covered. Multiple-field `ORDER BY` requires a matching composite index; some
filter/order combinations may also need or benefit from a composite index.
Do not assume every policy or every single-field query requires one: review
the actual query shape and indexing policy, including the appended `id` sort.
Insufficient index coverage can cause a query to be rejected by Cosmos DB.

Ordering and cross-partition fan-out can increase RU consumption. Inspect
`QueryPage.diagnostics().requestCharge()` when diagnostics are available and
compare representative query shapes and indexes before choosing a policy.
This guidance complements the default-ordering entry in the
[Cosmos provider changelog](../multiclouddb-provider-cosmos/CHANGELOG.md);
it is not a claim that all providers have equal cost or snapshot behavior.
