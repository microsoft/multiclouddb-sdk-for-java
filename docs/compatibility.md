# Portable API Surface

The Multicloud DB SDK's portable API surface covers capabilities that work
identically across all three providers. The features listed below require no
runtime capability checks - they are guaranteed to work on Azure Cosmos DB,
Amazon DynamoDB, and Google Cloud Spanner. Some providers offer additional
capabilities (e.g., `CROSS_PARTITION_QUERY`, `ORDER_BY`, `LIKE`); use
`client.capabilities()` to discover what the current provider supports.

---

## What Works Everywhere

Every capability listed below is fully supported on **all** providers. There are
no asterisks, no provider-specific caveats, and no runtime checks required.

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
| **Capability introspection** | `client.capabilities()` reports what the current provider supports |

---

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

### No native client access

The SDK does not expose a `nativeClient()` method. Direct access to the
underlying provider client is intentionally omitted to enforce portability
guarantees - code written against the SDK must remain switchable between
providers by configuration alone.

The same rule applies to code-level hooks, interceptors, and async callbacks:
provider-specific behaviour is reached through capability-gated features and
SDK configuration only.

### `nativeExpression()` - the one supported escape hatch

Queries are the single exception. When the portable expression DSL cannot
express what you need (for example Cosmos DB `LIKE`, or provider-specific
aggregate and ordering syntax), `QueryRequest.nativeExpression()` bypasses the
portable translation pipeline and sends a complete, provider-native statement
to the database. (The statement is never translated between dialects, though
the adapter may append a partition-key condition to it - see
[What happens on a provider mismatch](#what-happens-on-a-provider-mismatch).)

```java
// Cosmos DB - Cosmos SQL
QueryRequest q = QueryRequest.builder()
        .nativeExpression("SELECT * FROM c WHERE c.title LIKE '%flight%'")
        .maxPageSize(25)
        .build();
```

All three providers accept the field, but each dialect differs:

| Provider | Dialect passed through |
|---|---|
| Cosmos DB | Cosmos SQL (`SELECT * FROM c ...` - `c` is a container alias; any identifier works) |
| DynamoDB | PartiQL (`SELECT * FROM "database__collection" ...`) |
| Spanner | GoogleSQL (`SELECT * FROM collection ...`) |

Note what selects the target. On Cosmos DB the container comes from the
`ResourceAddress` you pass to `query()`, and the alias in the statement is
just an alias. On DynamoDB and Spanner the `ResourceAddress` is **ignored**
on the native path - the target is whatever table your statement names, so
you must spell it out yourself. DynamoDB table names are the `database` and
`collection` of your `ResourceAddress` joined by a double underscore
(`database__collection`); Spanner table names are the `collection` alone.
Addressing collection A while naming collection B in the statement reads
collection B, with no error.

`expression()` and `nativeExpression()` are mutually exclusive - setting both
throws `IllegalArgumentException` when `QueryRequest` is built.

`orderBy()` and `limit()` are **not** reliably applied to a native statement -
put `ORDER BY` and `LIMIT` in the statement itself. Setting `orderBy()`
alongside `nativeExpression()` fails fast with `UNSUPPORTED_CAPABILITY` on
DynamoDB, and is silently ignored on Cosmos DB and Spanner. `limit()` is
honoured on DynamoDB (it caps the page size) and ignored on the other two.

### What happens on a provider mismatch

The SDK does not dialect-check or translate the native statement, so a query
written for one provider is **not** rejected before execution. It is handed to
the configured provider, and the resulting failure is surfaced through the
normal error model:

| Stage | Behaviour |
|---|---|
| Build time | No dialect validation - the statement is stored as-is (the only build-time check is the `expression()` / `nativeExpression()` mutual-exclusivity rule above) |
| SDK dispatch | No dialect validation. **Exception:** when `partitionKey()` is also set, the DynamoDB and Spanner adapters append a partition-key equality condition to your statement (see below); Cosmos DB scopes the query through a request option and leaves the statement untouched |
| Provider execution | The database rejects it, mapped into a portable category (see [Portable Error Mapping](#portable-error-mapping)) |

Because of that rewrite, **avoid combining `partitionKey()` with
`nativeExpression()`** - put the partition-key predicate in your own statement
instead. The adapters choose between appending `WHERE ...` and `AND ...` using
a plain case-insensitive search for `WHERE`, so a statement containing `WHERE`
inside a string literal or a subquery can be rewritten incorrectly on DynamoDB
and Spanner while working correctly on Cosmos DB. The two are also not
equivalent in cost: on Cosmos DB `partitionKey()` bounds the query to a single
partition, whereas on DynamoDB and Spanner it becomes an ordinary predicate
that does not by itself bound the scan.

Three consequences are worth planning for:

- **The failure costs a round trip.** You get a runtime
  `MulticloudDbException` raised by the database, not an SDK-side dialect
  check, so a wrong dialect is only detected once the query reaches the
  provider. (`orderBy()` on DynamoDB is the one exception - it is rejected
  client-side before any network call.)
- **The error category depends on how the target rejects it.** A syntax error
  maps to `INVALID_REQUEST`, but a statement naming a table that does not exist
  on the target - the usual outcome of pasting another provider's dialect -
  maps to `NOT_FOUND`. Do not branch on `INVALID_REQUEST` alone.
- **A mismatch can silently succeed.** A statement valid in more than one
  dialect (for example `SELECT * FROM "app__todos" WHERE status = 'active'`,
  which parses under both PartiQL and GoogleSQL when the table name matches on
  both sides) executes without error, even though results and semantics may
  differ between providers.

Because of this, treat `nativeExpression()` as a last resort. Any query that
uses it must be rewritten and re-tested when the target provider changes;
prefer the portable `expression()` DSL wherever it can express the filter.
