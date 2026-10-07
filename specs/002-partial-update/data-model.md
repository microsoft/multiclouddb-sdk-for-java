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

- non-null, non-empty, non-blank, and at most 50,000 UTF-8 bytes;
- not one of `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`
  under a case-insensitive comparison;
- not underscore-prefixed;
- case-distinct non-reserved names remain separate literal names, including
  when both occur in one request; and
- never trimmed or rewritten.

Punctuation remains literal for participating Cosmos DB and DynamoDB mappings.
Spanner never reaches field-name mapping in this release: after shared
validation, the capability gate rejects every otherwise-valid update before provider delegation.

### Value rules

- scalar → replace scalar;
- map/list → replace complete top-level value;
- null → stored null for a supported provider mapping;
- values must be serializable with the SDK-owned Jackson configuration used by
  bounded shared preflight;
- binary values are outside the portable JSON value model, including values
  exposed while serializing a POJO;
- cyclic graphs and non-collection iterables fail bounded inspection, and
  serialized JSON output is capped while it is produced;
- every nested map key is at most 50,000 UTF-8 bytes; and
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

The logical-field transition is atomic and replay-idempotent. Provider-maintained
metadata and TTL timing are outside that guarantee.

## 3. Shared validation state

```text
RECEIVED
  -> closed                         CLIENT_CLOSED
  -> invalid map/name/TTL/count/value     INVALID_REQUEST
  -> serialization failure                 INVALID_REQUEST
  -> serialized bytes > 390 KiB           INVALID_REQUEST
  -> replacement map/list depth > 31      INVALID_REQUEST
  -> structural footprint > 390 KiB       INVALID_REQUEST
  -> partial_update unsupported           UNSUPPORTED_CAPABILITY
  -> provider plan
```

All local failures delegate zero provider update operations. Structural failures
use stable `partial_update_nesting_depth_limit` or
`partial_update_structural_footprint_limit` reasons with actual/maximum details.
Complete create/upsert documents share the 31-level, nested-name, binary-value,
and 390 KiB structural checks. They also reject a null document and the
case-insensitive top-level provider-owned names `id`, `partitionKey`, `sortKey`,
`ttl`, `ttlExpiry`, and `data`, plus every underscore-prefixed top-level name.
Remaining top-level names must be unique ignoring case and contain at most 128
Unicode characters. The detached result of bounded serialization is the exact
provider snapshot. The update structure calculation
applies only to the incoming replacement field map;
it does not model omitted existing state.

### Portable limit model

Shared preflight enforces:

| Limit | Value |
|---|---:|
| Serialized input | 399,360 bytes |
| Structural footprint | 399,360 bytes |
| Nested and partial-update field name | 50,000 UTF-8 bytes |
| Complete-document top-level field name | 128 Unicode characters |
| Nested map/list containers | 31 |
| Partial-update fields | 10 |

The implementation defaults are package-private and are not part of the public
Java API. Typed `INVALID_REQUEST` details report the applicable maximum.

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
 -> shared INVALID_REQUEST before provider delegation
```

### Result-item limit

```text
resulting document > 2,097,152 bytes
  -> Cosmos reports HTTP 413 after one attempted patch
  -> reason-coded UNSUPPORTED_CAPABILITY
  -> stored document remains unchanged
```

The state-dependent path has no adapter read/merge preflight. HTTP 413 is
specialized only for `update()`.

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
shared field count <= 10
 -> generated expression remains safely below the native ceiling
 -> one UpdateItem

condition failure
  -> NOT_FOUND

resulting item exceeds the native ceiling
  -> DynamoDB rejects the one attempted UpdateItem atomically
  -> reason-coded UNSUPPORTED_CAPABILITY
```

The result-size path is state-dependent and has no adapter read/merge preflight.
Only the matching update `ValidationException` is normalized; other validation
errors remain `INVALID_REQUEST`.

## 6. Provider release boundary

Spanner is deliberately not a supported Feature 002 update data path. The shared
default client owns portable read/query identity cleanup after provider mapping,
and the Spanner write path remains unchanged. The only Spanner production
adjustment matches `FIELD_DATA` metadata to physical columns case-insensitively so
mapped results preserve the caller's field spelling. Because the provider omits
the core `partial_update` capability, `CapabilitySet` supplies its unsupported
default and rejects valid calls before provider delegation. The Spanner
emulator validated this gate, the provider-direct legacy regression, and the mapper
casing behavior; no live production Spanner validation is claimed.
## 7. Capabilities

| Provider | `partial_update` |
|---|---|
| Cosmos DB | supported |
| DynamoDB | supported |
| Spanner | unsupported by API default |

The core partial-update operation guarantees results whose serialized JSON and
portable structural footprint are each at most 390 KiB. A state-dependent result above either bound
is outside this release's portable contract and may succeed or fail under native
provider limits. Native size errors remain reason-coded because the SDK does not
read/merge stored state before writing. Case-distinct field identity is part of
the core partial-update contract. TTL timing is not: DynamoDB `UpdateItem` happens to leave
`ttlExpiry` unchanged, while Cosmos DB `patchItem` advances `_ts` and restarts
relative TTL. Until behavior is normalized, callers requiring fixed absolute
expiry must not call `update()` on TTL-bearing items.

`CapabilitySet` supplies only the omitted core capability default, so every
built-in provider exposes 18 effective rows; Cosmos DB and DynamoDB explicitly
declare 18, while an older Spanner provider's 17 declarations become 18 without
a production change. Unrelated omitted names remain absent.

## 8. Structured provider-limit errors

All values in `providerDetails` are strings.

The shared capability gate uses the `UnsupportedCapability` error
representation with `category=UNSUPPORTED_CAPABILITY`, `retryable=false`, and
`capability=partial_update`. State-dependent native envelope failures use the
same category with a stable provider-specific `reason`.

### Cosmos

```text
reason=cosmos_result_item_size_limit
maximumResultBytes
subStatusCode
requestId                          (when available)
requestCharge
```

The result-item rejection follows one attempted direct
patch; the failed native operation leaves the document unchanged.

### Dynamo

The shared 10-field limit keeps the generated expression below the DynamoDB
native expression ceiling. The planner retains a defensive internal guard for
direct SPI misuse, but that guard is not part of the portable caller-visible
envelope.

```text
reason=dynamodb_result_item_size_limit
maximumResultBytes
errorCode=ValidationException       (when available)
requestId                          (when available)
serviceName                        (when available)
```

The Dynamo result-item rejection is returned after one attempted `UpdateItem`;
the failed native operation leaves the item unchanged.

## 9. Conformance fixture rule

Supported partial-update behavior runs only for providers advertising the core
capability. Shared invalid-request checks still run on every provider because
validation precedes the gate. Shared coverage accepts same-request
case-distinct non-reserved names, rejects all provider-owned and
underscore-prefixed complete-write top-level names, and verifies the shared write envelope. Capability coverage verifies all 18 effective rows and
the single core partial-update matrix. Native result-limit and TTL behaviors
remain provider implementation evidence rather than portable capabilities.
Spanner receives a dedicated assertion for the API-default unsupported state and
for non-retryable `UNSUPPORTED_CAPABILITY` with zero provider mutation.

Current local validation ran DynamoDB Local and the Spanner emulator. The Cosmos
emulator is unavailable, so its post-remediation rerun and T061 remain pending.
