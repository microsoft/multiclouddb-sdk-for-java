# Phase 1 Data Model: Portable Partial Update

This feature adds no persisted domain type. It defines a request, validation
state, and provider-native plans.

## 1. Request

| Field | Type | Rule |
|---|---|---|
| `address` | `ResourceAddress` | Existing addressing rules apply. |
| `key` | `MulticloudDbKey` | Identifies an existing document. |
| `fields` | `Map<String,Object>` | Non-null/non-empty literal top-level assignments. |
| `options` | `OperationOptions` | Defaults allowed; `ttlSeconds` must be null. |

### Name rules

- non-null, non-empty, non-blank;
- not a case-insensitive reserved name;
- not underscore-prefixed;
- unique ignoring case; and
- never trimmed or rewritten.

Punctuation remains literal. A Spanner name is usable only if it matches the
row's established logical spelling or, when new, an exactly matching
provisioned column. A case-only alias is rejected before mutation.

### Value rules

- scalar → replace scalar;
- map/list → replace complete top-level value;
- null → stored null for a supported provider mapping;
- no remove, increment, nested path, condition, or TTL mutation.

## 2. State transition

```text
EXISTING document D
  + fields F
  -> D' where:
       D'[name] = F[name] for name in F
       D'[name] = D[name] for omitted names

MISSING document
  + update(F)
  -> NOT_FOUND; remains missing
```

The transition is atomic and replay-idempotent.

## 3. Shared validation state

```text
RECEIVED
  -> closed                         CLIENT_CLOSED
  -> invalid map/name/TTL           INVALID_REQUEST
  -> serialized bytes > 408,576     INVALID_REQUEST
  -> partial_update unsupported     UNSUPPORTED_CAPABILITY
  -> provider plan
```

All local failures delegate zero provider update operations.

## 4. Cosmos plan

### Assignment

| Field | Meaning |
|---|---|
| `rawName` | caller field name |
| `path` | one RFC 6901 segment |
| `value` | absolute `set` value |

### Selection

```text
fieldCount <= 10
  -> DirectPlan(CosmosPatchOperations)

fieldCount > 10
  -> chunks of <=10 sets
  -> measure prospective batch
  -> if operations >100 or bytes >2,097,152: local extension error
  -> BatchPlan(CosmosBatch)
```

The batch contains repeated patch operations for the same item ID and partition
key.

### Result-item limit

```text
resulting document > 2,097,152 bytes
  -> Cosmos reports HTTP 413 after one attempted patch/batch
  -> extension UNSUPPORTED_CAPABILITY
  -> stored document remains unchanged
```

The state-dependent path has no adapter read/merge preflight. HTTP 413 is
specialized only for `update()`.

### Failed batch state

```text
first failed usable non-424 result
  -> map result status/substatus
else usable non-424 aggregate status
  -> map aggregate status/substatus
else
  -> PROVIDER_ERROR(no root operation status)
```

## 5. Dynamo plan

| Field | Meaning |
|---|---|
| `key` | existing partition/sort-key map |
| `names` | `#fN` aliases plus `#pk` |
| `values` | `:vN` structured `AttributeValue`s |
| `updateExpression` | one `SET` clause |
| `conditionExpression` | `attribute_exists(#pk)` |
| `expressionBytes` | UTF-8 size of the update expression |

```text
expressionBytes <= 4096
  -> one UpdateItem

expressionBytes > 4096
  -> local extension UNSUPPORTED_CAPABILITY

condition failure
  -> NOT_FOUND

resulting item > 409600 bytes
  -> DynamoDB rejects the one attempted UpdateItem atomically
  -> extension UNSUPPORTED_CAPABILITY
```

The result-size path is state-dependent and has no adapter read/merge preflight.
Only the matching update `ValidationException` is normalized; other validation
errors remain `INVALID_REQUEST`.

## 6. Provider release boundary

Spanner is not a feature-002 data model. Its provider module and capability set
remain unchanged. Because it does not advertise `partial_update`, the default
client rejects valid calls before provider delegation; no row, schema, metadata,
or mapping behavior is changed by this feature.
## 7. Capabilities

| Provider | `partial_update` | `partial_update_extended_payload` | `partial_update_case_sensitive_fields` |
|---|---|---|---|
| Cosmos DB | supported | unsupported | supported |
| DynamoDB | supported | unsupported | supported |
| Spanner | not advertised | not advertised | not advertised |

The payload extension describes request/result-envelope reach, not schema
breadth. The case capability describes literal identity across calls.

## 8. Structured provider-limit errors

All values in `providerDetails` are strings.

### Cosmos

```text
reason=cosmos_transactional_batch_limit
capability=partial_update_extended_payload
actualOperations
maximumOperations=100
actualBytes
maximumBytes=2097152
```

This request-envelope rejection is local and performs zero Cosmos DB I/O.

```text
reason=cosmos_result_item_size_limit
capability=partial_update_extended_payload
maximumResultBytes=2097152
subStatusCode
requestId                          (when available)
requestCharge
```

The result-item rejection follows one attempted direct patch or transactional
batch; the failed native operation leaves the document unchanged.

### Dynamo

```text
reason=dynamodb_update_expression_limit
capability=partial_update_extended_payload
actualExpressionBytes
maximumExpressionBytes=4096
```

This expression rejection is local and performs zero DynamoDB I/O.

```text
reason=dynamodb_result_item_size_limit
capability=partial_update_extended_payload
maximumResultBytes=409600
errorCode=ValidationException       (when available)
requestId                          (when available)
serviceName                        (when available)
```

The Dynamo result-item rejection is returned after one attempted `UpdateItem`;
the failed native operation leaves the item unchanged.

## 9. Conformance fixture rule

Supported partial-update behavior runs only for providers advertising the core
capability. Shared invalid-request checks still run on every provider because
validation precedes the gate. Unchanged Spanner receives a dedicated assertion
for non-retryable `UNSUPPORTED_CAPABILITY` with zero provider mutation.