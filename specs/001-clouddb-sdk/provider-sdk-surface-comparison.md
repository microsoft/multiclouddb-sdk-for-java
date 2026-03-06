# Provider SDK Surface Comparison (Java) — Cosmos DB vs DynamoDB vs Spanner

This is **supporting, non-normative research** for the Hyperscale DB SDK spec. It documents how the *official Java SDKs* expose common operations and diagnostic signals, to help design a portable contract.

Providers / SDKs:
- **Azure Cosmos DB (SQL API)**: `com.azure:azure-cosmos`
- **AWS DynamoDB**: `software.amazon.awssdk:dynamodb` (AWS SDK for Java v2)
- **Google Cloud Spanner**: `com.google.cloud:google-cloud-spanner`

## Comparison Table (Portability-Focused)

| Topic | Cosmos DB (`com.azure:azure-cosmos`) | DynamoDB (AWS SDK v2) | Spanner (`google-cloud-spanner`) |
|---|---|---|---|
| Read-by-key | `CosmosContainer.readItem(id, new PartitionKey(pk), JsonNode.class)` | `DynamoDbClient.getItem(GetItemRequest.builder().tableName(...).key(keyMap).build())` | `DatabaseClient.singleUse().readRow(table, Key.of(...), columns)` (or SQL with primary key filter) |
| Upsert / Put | `CosmosContainer.upsertItem(doc, options)` (replace semantics by key) | `DynamoDbClient.putItem(PutItemRequest.builder()...)` (replace-by-key unless conditional) | `DatabaseClient.readWriteTransaction().run(tx -> { tx.buffer(Mutation.newInsertOrUpdateBuilder(table)...); })` |
| Delete-by-key | `CosmosContainer.deleteItem(id, new PartitionKey(pk), options)` (404 when missing) | `DynamoDbClient.deleteItem(DeleteItemRequest.builder()...)` (idempotent when unconditional) | `Mutation.delete(table, KeySet.singleKey(Key.of(...)))` (missing row is effectively idempotent) |
| Query API shape | SQL string via `CosmosContainer.queryItems(querySpec, options, JsonNode.class)` | Expression-based `QueryRequest` (key condition, filter expressions) / `ScanRequest` | SQL string via `DatabaseClient.singleUse().executeQuery(Statement.of(sql))` |
| Pagination / continuation | `CosmosPagedFlux.byPage(token, preferredPageSize)` continuation tokens are opaque strings | Responses contain `LastEvaluatedKey`; next page uses `ExclusiveStartKey` (requires adapter-defined token serialization) | SQL result sets are streamed; no user-facing continuation token for query paging (capability-gate as unsupported) |
| Request ID / correlation ID | `CosmosDiagnostics` + response headers include request/trace ids (surface sanitized) | AWS response metadata includes request id; exceptions include request id and status code | gRPC/HTTP codes via `SpannerException`; request identifiers are not consistently user-facing; rely on tracing hooks where available |
| Auth failures | Cosmos exceptions for 401/403 (`CosmosException.getStatusCode()`) | `DynamoDbException` / `AwsServiceException` with status 401/403-style failures | `SpannerException` with `ErrorCode.UNAUTHENTICATED` / `PERMISSION_DENIED` |
| Throttling / quota | Cosmos 429 with retry-after; throttling retry options are first-class | Provisioned throughput / throttling exceptions are retryable per AWS retry strategy | `RESOURCE_EXHAUSTED` / `UNAVAILABLE` map to retryable transient failures depending on operation |
| Not found | Cosmos 404 `CosmosException` | Item missing often returns empty `GetItemResponse` rather than an exception; missing table surfaces service errors | `NOT_FOUND` errors for missing database/table (depending on call) |
| Conflict | Cosmos 409 / precondition failures | Conditional check failures (`ConditionalCheckFailedException`) | `ALREADY_EXISTS` / `FAILED_PRECONDITION` / `ABORTED` (ABORTED is usually retryable) |

## Portability Pitfalls (What the Hyperscale DB SDK must normalize or flag)

- **Key model mismatch**
  - Cosmos DB point ops require both `id` and `partition_key`.
  - DynamoDB requires partition key and optionally sort key (typed attribute values).
  - Spanner keys are *the full primary key tuple across columns*.

- **“Upsert” isn’t identical**
  - DynamoDB `put_item` is a full replace-by-key unless you add conditions.
  - Cosmos `upsert_item` replaces the document for the key.
  - Spanner `insert_or_update` overwrites provided columns and preserves others (different from full replace).

- **Delete idempotency differs by default**
  - DynamoDB unconditional deletes are idempotent.
  - Spanner `Transaction.delete` is idempotent for non-existent rows.
  - Cosmos `delete_item` raises `CosmosResourceNotFoundError` when the item does not exist.

- **Pagination tokens are not portable**
  - Cosmos uses opaque continuation tokens (SDK exposes them via `ItemPaged.by_page(continuation_token=...)`).
  - DynamoDB uses `LastEvaluatedKey` (structured key dict).
  - Spanner query results are streamed; pagination is typically done with `LIMIT` + ordering/keyset patterns, not continuation tokens.

- **Transaction semantics**
  - Spanner is transaction-centric; read-write work is typically wrapped in `run_in_transaction()` and may retry on `Aborted`.
  - DynamoDB and Cosmos have more limited/structured transactional semantics (e.g., per-item conditionals; Cosmos transactional batches are scoped).

## Design Implications for Hyperscale DB’s Portable Contract

- Treat **continuation** as an opaque `continuation_token` string in the portable API, even if the provider uses a structured key (DynamoDB) or has no native token (Spanner).
- Consider making **delete-by-key idempotent** in the portable contract (normalize Cosmos 404-on-delete into success), and provide an opt-in “strict delete” mode.
- Model **upsert semantics explicitly** (e.g., `UPSERT_REPLACE_ALL` vs `UPSERT_PATCH_COLUMNS`) and capability-gate the stronger semantics for providers that can’t match it.
