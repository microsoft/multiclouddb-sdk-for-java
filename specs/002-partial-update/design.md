# Portable Partial Update Design

**Branch**: `002-partial-update`
**Binding**: This document defines the implementation for this feature.

## 1. Decision

Keep the existing Java signatures and change `update()` from ambiguous
provider-specific replacement to shallow set/replace semantics:

```java
void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields,
    OperationOptions options);
```

Cosmos DB and DynamoDB move to native partial-update operations and advertise
the core capability. Spanner is excluded from this release and its provider
module remains byte-for-byte unchanged from the PR base.

## 2. Portable contract

For field names and value shapes supported by the provider mapping:

- each present top-level field is set/replaced;
- omitted fields are preserved;
- map/list values replace the complete top-level value;
- null stores provider-native null and does not remove the field;
- the call is atomic and replay-idempotent; and
- a missing document returns `NOT_FOUND` without creating it.

The operation does not support nested paths, remove, increment, conditional
field predicates, or update TTL.

### 2.1 Release boundary

Only providers advertising `partial_update` enter a provider data path. Cosmos
DB and DynamoDB advertise it. Spanner retains its pre-feature capability set,
so the default client rejects a valid update after shared validation and before
delegation with:

```text
category=UNSUPPORTED_CAPABILITY
retryable=false
operation=update
capability=partial_update
```

This boundary avoids changing or releasing any Spanner provider code while
keeping unsupported behavior explicit rather than silently invoking its legacy
implementation.
## 3. Shared preflight

`DefaultMulticloudDbClient.update()` runs:

```text
checkOpen
  -> validate non-null/non-empty fields
  -> validate names
  -> reject update TTL
  -> validate serialized size <= 408,576 bytes
  -> gate Capability.PARTIAL_UPDATE
  -> delegate once
```

Name validation:

- non-null, non-empty, non-blank;
- not `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`,
  case-insensitively;
- not underscore-prefixed;
- unique ignoring case; and
- accepted names are not trimmed or rewritten.

The validator accepts literal `.`, `/`, `~`, and surrounding spaces. Mapping
constraints apply only after the provider passes the core capability gate.

All validation failures are non-retryable `INVALID_REQUEST` and perform zero
provider I/O. Exactly 408,576 serialized bytes passes; 408,577 fails.

## 4. Capabilities

Three declarations are retained:

| Capability | Meaning |
|---|---|
| `partial_update` | Provider implements the core shallow set/replace operation. |
| `partial_update_extended_payload` | Supported provider field mappings do not encounter a lower native request or resulting-item envelope before the common 408,576-byte field-map limit. |
| `partial_update_case_sensitive_fields` | Case-distinct field names retain separate literal identities rather than aliasing one provider column. |

The default client gates only `partial_update`. The two extensions are
descriptive and never disable ordinary updates.

| Provider | Core | Extended payload | Case-sensitive fields |
|---|---|---|---|
| Cosmos DB | supported | unsupported | supported |
| DynamoDB | supported | unsupported | supported |
| Spanner | not advertised | not advertised | not advertised |

Cosmos DB and DynamoDB declare all 20 known capability names. Unchanged Spanner
retains its existing 17 declarations.

## 5. Cosmos DB design

### 5.1 Literal paths

Each raw field name becomes one RFC 6901 segment:

```java
"/" + rawName.replace("~", "~0").replace("/", "~1")
```

Every assignment uses `CosmosPatchOperations.set`. No key or TTL operation is
added.

### 5.2 Direct and wide plans

```text
1..10 fields
  -> one patchItem

11+ fields
  -> chunks of at most 10 set operations
  -> one CosmosBatch
  -> every batch operation targets the same item ID and partition key
  -> one executeCosmosBatch
```

There is no read, merge, replace, independent patch loop, or adapter retry loop.

### 5.3 Local batch envelope

Before constructing an executable wide request, the package-private planner
mirrors the public SDK JSON body shape:

```json
[
  {
    "operationType": "Patch",
    "id": "item-id",
    "resourceBody": {
      "operations": [
        {"op": "set", "path": "/field", "value": "value"}
      ]
    }
  }
]
```

The planner measures UTF-8 bytes and batch-operation count. It rejects:

- more than 100 batch operations; or
- more than 2,097,152 serialized bytes.

The local error is non-retryable `UNSUPPORTED_CAPABILITY`,
`capability=partial_update_extended_payload`, with:

- `reason=cosmos_transactional_batch_limit`
- `actualOperations`
- `maximumOperations=100`
- `actualBytes`
- `maximumBytes=2097152`

Both direct and wide accepted plans issue one adapter SDK call. Wide-plan cost
is still proportional to the number of patch chunks.

### 5.4 Service result-item envelope

A fields map can pass shared and batch preflight but push the existing Cosmos
document over the service's 2,097,152-byte item limit. No read/merge preflight
is added. If the one attempted direct patch or transactional batch reports HTTP
413 during `update()`, it maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=cosmos_result_item_size_limit`
- `capability=partial_update_extended_payload`
- `maximumResultBytes=2097152`

A thrown direct-patch exception preserves its cause and sanitized native
metadata. A failed batch preserves aggregate/result diagnostics. HTTP 413 from
other operations retains the normal Cosmos provider-error mapping.

### 5.5 Failed batch selection

For a non-success `CosmosBatchResponse`:

1. select the first failed operation with a usable HTTP 4xx/5xx status other
   than 424;
2. otherwise use a usable non-424 aggregate 4xx/5xx status;
3. otherwise return `PROVIDER_ERROR` stating that no root operation status was
   supplied.

The selected status uses the same category/retry policy as a thrown
`CosmosException`. In particular:

- 404 → `NOT_FOUND`, not retryable;
- 408 → `TRANSIENT_FAILURE`, retryable;
- 410 → `TRANSIENT_FAILURE`, retryable, substatus retained;
- 413 during `update()` → `UNSUPPORTED_CAPABILITY`, not retryable;
- 429 → `THROTTLED`, retryable; and
- 5xx → `TRANSIENT_FAILURE`, retryable.

HTTP 424 is rollback fallout and is never caller-facing root cause.

### 5.6 Response bodies and diagnostics

`contentResponseOnWriteEnabled(false)` is safe because existing write methods
return `void` and consume only response metadata. Tests retain status,
activity ID, request charge, duration, and diagnostics for create, patch,
upsert, and delete paths.

Batch diagnostics log only operation/address, aggregate status/substatus,
activity ID, charge, operation count, latency, and native diagnostics. They do
not log field values or request bodies.

## 6. DynamoDB design

The package-private planner emits one request:

```text
UpdateExpression:
  SET #f0 = :v0, #f1 = :v1, ...

ConditionExpression:
  attribute_exists(#pk)
```

- `#fN` maps to the raw caller field name.
- `:vN` maps through `DynamoItemMapper.objectToAttributeValue`.
- `#pk` maps to the partition-key attribute.
- no raw field name is embedded in the expression;
- no TTL assignment is generated; and
- the request asks for total consumed capacity.

The value mapper preserves STRING/NUMBER/BOOL/NULL/MAP/LIST shapes.

The planner measures:

```java
updateExpression.getBytes(StandardCharsets.UTF_8).length
```

An expression above 4,096 bytes fails locally with non-retryable
`UNSUPPORTED_CAPABILITY` and:

- `reason=dynamodb_update_expression_limit`
- `capability=partial_update_extended_payload`
- `actualExpressionBytes`
- `maximumExpressionBytes=4096`

The provider executes exactly one `updateItem`. A
`ConditionalCheckFailedException` from the existence guard maps to
`NOT_FOUND`. No read, `PutItem`, or adapter retry loop is used.

The existing item can make an otherwise-valid update exceed DynamoDB's
409,600-byte resulting-item limit. No read/merge preflight is added. When the
single `UpdateItem` returns the size-specific `ValidationException` message,
only that variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=dynamodb_result_item_size_limit`
- `capability=partial_update_extended_payload`
- `maximumResultBytes=409600`

Sanitized native error code, status, request ID, and service details remain
available where supplied. Other `ValidationException` messages remain
`INVALID_REQUEST`, the original cause is preserved, and no payload data is
added to diagnostics.

The module descriptor explicitly reads the AWS utility and identity modules
needed by the pinned SDK so a clean module-path compilation succeeds.

## 7. Test design

### Completed focused unit layer

- API validator/default-client/size/public-contract/capability tests
- Cosmos planner, direct provider, wide provider, error mapping, diagnostics,
  response-body configuration, update-only 413 normalization, and updated
  consistency tests
- Dynamo planner, provider, and structured mapper tests
- Spanner row-mapper coverage for exact logical spelling projection

### Shared layer

All providers inherit shared validation coverage for invalid maps, names, TTL,
and the 408,577-byte rejection because validation precedes the core gate.
Supported behavior—preservation, missing-item handling, replay, concurrency,
wide updates, and case identity—runs only when `partial_update` is advertised.
A dedicated shared assertion verifies that unchanged Spanner returns
`UNSUPPORTED_CAPABILITY` with `capability=partial_update` and does not mutate
state.

The exact 408,576-byte runtime assertion remains gated by
`partial_update_extended_payload`. Neither participating provider advertises
that extension, so the positive boundary is locked by API validator tests while
Cosmos and Dynamo exercise their lower native envelopes in concrete emulator
regressions.

`CosmosConformanceTest` and `DynamoConformanceTest` seed native items below their
service limits, apply small portable updates that would push the results above
those limits, and assert normalized capability errors plus unchanged state.
## 8. Migration

Before this feature, Cosmos and Dynamo `update()` replaced the complete stored
document. Callers that require replacement use:

```java
client.upsert(address, key, completeDocument);
```

`upsert()` creates a missing document. Read-then-upsert is not an atomic
guarded replacement and can recreate a concurrently deleted or expired item.

TTL-bearing updates also move to complete create/upsert writes:

```java
client.upsert(
    address,
    key,
    completeDocument,
    OperationOptions.builder().ttlSeconds(3600).build());
```

## 9. Scope boundaries

This design does not add a `replace()` method, general patch model, Spanner
typed-null/DDL/automatic-schema work, native-client escape hatch, cancellation,
configurable retry policy, or changes under `multiclouddb-perf/`.
