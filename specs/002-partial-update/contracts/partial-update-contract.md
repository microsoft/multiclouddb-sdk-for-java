# Java Contract: `MulticloudDbClient.update`

**Branch**: `002-partial-update`

## Signatures

```java
void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields,
    OperationOptions options);

default void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields);
```

The three-argument overload supplies `OperationOptions.defaults()`.

## Shared preconditions

After the closed-client guard and before provider planning:

1. `fields` is non-null and non-empty.
2. `fields` contains at most 10 entries.
3. Names are non-null, non-empty, non-blank, and at most 50,000 UTF-8 bytes.
4. Names do not match `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or
   `data` ignoring case.
5. Names do not begin with `_`.
6. `options.ttlSeconds()` is null.
7. No replacement value is binary, including a value exposed while serializing
   a POJO.
8. Cyclic graphs and non-collection `Iterable` values are rejected, and
   serialized JSON output is capped at 390 KiB while it is produced;
9. serialized `fields` is at most 390 KiB;
10. every replacement value contains at most 31 nested map/list containers,
    counting a top-level replacement container as level 1;
11. the incoming field map's structural footprint is at most 390 KiB; and
12. `partial_update` is supported.

Structural footprint is the sum of UTF-8 attribute-name bytes and scalar-value
bytes, plus three bytes for every map/list container and one byte for every
nested map/list element. The top-level `fields` object itself is not a stored
container and adds no container overhead.

Preconditions 1–11 fail with non-retryable `INVALID_REQUEST`; precondition 12
fails with non-retryable `UNSUPPORTED_CAPABILITY` and
`providerDetails.capability=partial_update`. Every failure performs zero
provider update operations. Local failures carry these stable details:

- `reason=partial_update_field_count_limit`, `maximumFields=10`, and `observedFields=11`; inspection stops at the first excess field, so the observed count is a lower bound rather than the total input size;
- `reason=partial_update_field_name_size_limit`, `actualFieldNameBytes`, and `maximumFieldNameBytes=50000`;
- `reason=non_portable_binary_value`, `valuePath`, and `valueType`;
- `reason=non_portable_iterable` or `reason=partial_update_value_cycle`;
- `reason=partial_update_serialized_size_limit` and `maximumSerializedBytes=399360`;
- `reason=partial_update_nesting_depth_limit`, `valuePath`,
  `actualNestingDepth`, and `maximumNestingDepth=31`; or
- `reason=partial_update_structural_footprint_limit`,
  `actualPortableFootprintBytes`, and
  `maximumPortableFootprintBytes=399360`.

No raw replacement value is included in the error.

After name/TTL validation and portable-value inspection, the shared API
snapshots the caller's top-level map and uses the SDK-owned Jackson configuration
for bounded serialization. Values requiring caller-registered modules must be
converted to serializable values before writing.

Accepted names are not trimmed or rewritten. Punctuation is literal, but
provider mapping remains relevant. Case-distinct non-reserved names such as
`foo` and `Foo` are separate literal fields and MAY occur together in the same
atomic request.

## Postconditions

For a provider-supported field mapping on an existing document:

- named fields equal the supplied values;
- omitted fields retain their values;
- map/list values replace the entire top-level value;
- null stores provider-native null;
- all assignments commit atomically;
- replaying the assignments is idempotent for logical document fields, not
  provider-maintained metadata or TTL timing; and
- a result whose serialized JSON and portable structural footprint are each at most
  390 KiB is inside the portable base envelope.

A missing document returns `NOT_FOUND` and is not created.
A state-dependent result above either 390 KiB bound is outside this release's
portable contract and may succeed or fail under native provider limits. TTL
timing is also outside the portable contract. DynamoDB `UpdateItem` happens to
leave `ttlExpiry` unchanged, while Cosmos DB `patchItem` advances `_ts` and
restarts relative TTL. Until behavior is normalized, callers requiring fixed
absolute expiry must not call `update()` on TTL-bearing items.

## Complete-document write addendum

The shared create/upsert preflight uses one bounded Jackson serialization for
unsafe-graph, binary-value, field-name, depth, serialized-size, and
structural-footprint validation. Its detached normalized result is the exact
provider input. A null complete document fails with non-retryable
`INVALID_REQUEST` before provider I/O. Top-level names matching `id`,
`partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data` case-insensitively, and
names beginning with `_`, fail through the same zero-I/O category. Remaining
top-level names must be unique ignoring case and contain at most 128 Unicode
characters; nested names retain the 50,000-byte UTF-8 limit.

Shared preflight enforces these six input/structure limits:

- serialized input: 399,360 bytes
- structural footprint: 399,360 bytes
- nested and partial-update field names: 50,000 UTF-8 bytes
- complete-document top-level field names: 128 Unicode characters
- nested map/list containers: 31
- partial-update fields: 10

The values are behavioral contract limits, not public Java constants. Typed
`INVALID_REQUEST` details report the applicable maximum at runtime.

## Provider release boundary

Cosmos DB and DynamoDB advertise `partial_update` and enter their native update
paths. Spanner omits the core Feature 002 capability, so `CapabilitySet`
supplies its unsupported default. After shared preconditions 1–11 pass, the default client rejects a Spanner call with
non-retryable `UNSUPPORTED_CAPABILITY`,
`providerDetails.capability=partial_update`, and zero provider update
operations. The Spanner emulator validates this shared boundary and the
provider-direct legacy regression; no live production Spanner validation or
portable Spanner update support is claimed.

## Capability contract

| Provider | `partial_update` |
|---|---|
| Cosmos DB | supported |
| DynamoDB | supported |
| Spanner | unsupported by API default |

The base capability guarantees results whose serialized JSON and portable
structural footprint are each at or below 390 KiB. Above either bound, the
state-dependent result is outside this release's portable contract and may
succeed or fail under native provider limits. The SDK performs no read/merge
preflight; native rejections remain non-retryable, reason-coded provider-limit
errors after at most one attempted native update. TTL timing is outside the
portable contract, and callers requiring fixed absolute expiry MUST NOT call
`update()` on TTL-bearing items until behavior is normalized.

Each built-in provider's effective set has 18 rows: Cosmos DB and DynamoDB
explicitly declare all 18, while Spanner's 17 declarations receive the one core
Feature 002 unsupported API default.
`CapabilitySet` does not backfill unrelated known names omitted by an arbitrary
legacy or third-party provider.

## Provider execution

| Provider | Accepted plan | Missing document | Observed TTL behavior (outside portable contract) |
|---|---|---|---|
| Cosmos DB | one direct patch through 10 fields | direct 404 → `NOT_FOUND` | not preserved; `_ts` advances |
| DynamoDB | one aliased conditional `UpdateItem` | failed existence guard → `NOT_FOUND` | preserved; `ttlExpiry` is not assigned |
| Spanner | no provider plan; shared capability rejection | not reached | not reached |

Cosmos and Dynamo add no adapter read, replacement write, or retry loop.

## Timeout error normalization

Timeout mappings are scoped to `update()`. When a supported provider's native
SDK surfaces one of the failures below, the adapter MUST return a
`MulticloudDbException` with category `TRANSIENT_FAILURE`, `retryable=true`,
the matching provider, and operation `update`:

- **Cosmos DB**: HTTP `408` or `410`. The portable error MUST retain the HTTP
  status, original cause, and `subStatusCode`, including a surfaced HTTP 410
  substatus. Activity/request metadata MUST be retained when available.
- **DynamoDB service timeout**: error code `RequestTimeout` or
  `RequestTimeoutException`. The portable error MUST retain the HTTP status,
  original cause, native error code, request ID, and service name when
  available.
- **DynamoDB SDK timeout**: `ApiCallTimeoutException` or
  `ApiCallAttemptTimeoutException`. The portable error MUST retain the original
  cause and `providerDetails.reason=dynamodb_request_timeout`. An HTTP status
  is not required when the SDK did not receive one.

Cosmos HTTP 410 from change-feed operations remains `CURSOR_EXPIRED`; only
`update()` uses the transient mapping above. Other operations retain their
existing provider mappings. `retryable=true` permits caller-controlled replay
of the logical field assignments; it does not promise unchanged provider metadata
or TTL timing and does not add an adapter read, replacement write, or retry loop.
A repeated Cosmos patch advances `_ts` and can restart the TTL countdown.

## Cosmos envelope

Maps above 10 fields fail shared preflight with `INVALID_REQUEST` before any
provider call. The Cosmos planner repeats this check for direct SPI use. Field
names use one RFC 6901 segment and are sorted before native plan construction.
The patch does not assign reserved TTL fields, but it advances `_ts`; Cosmos
therefore does not promise a fixed absolute TTL expiry.

An otherwise-valid update can push an existing Cosmos document above the
2,097,152-byte resulting-item limit. No read/merge preflight is performed. If
the one attempted direct patch reports HTTP 413 during `update()`, it maps to
non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=cosmos_result_item_size_limit`
- `maximumResultBytes=2097152`

The failed write leaves the document unchanged. Direct exceptions preserve
their cause and sanitized native metadata. HTTP 413 from non-update operations
retains the normal provider-error mapping.

## Dynamo envelope

The provider builds one:

```text
SET #f0 = :v0, #f1 = :v1, ...
```

with `attribute_exists(#pk)`. Values preserve null/scalar/map/list shapes.
The expression does not assign `ttlExpiry`, so DynamoDB preserves an existing
absolute expiry and advertises the corresponding capability.

Complete `create()`/`upsert()` documents share the 31-level, 50,000-byte nested
field-name, binary-value, and 390 KiB structural-footprint checks. Their top-level
names use the 128-character case-insensitive portable namespace. This ensures
SDK-created complete documents fit the same native-safe envelope.

After provider mapping, `DefaultMulticloudDbClient` centrally removes top-level
adapter storage names case-insensitively from read and query documents: `id`,
`partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`, and underscore-prefixed
metadata. Nested names are preserved. Spanner row mapping independently matches
`FIELD_DATA` metadata to physical columns case-insensitively so caller field
spelling survives. Requested read metadata remains in `DocumentMetadata`.

The shared 31-level, field-name, binary-value, and structural-footprint checks
reject incoming replacement structures that cannot fit the portable DynamoDB-safe envelope even when their
compact JSON representation is below 390 KiB. They do not inspect omitted
existing attributes or add a read/merge preflight.

The shared 10-field limit keeps generated expressions safely below the DynamoDB
native expression ceiling. The planner retains a defensive local guard for
direct SPI misuse, but it is not part of the portable caller-visible envelope.

An otherwise-valid update can push an existing item above DynamoDB's
provider-native resulting-item limit. No read/merge preflight is performed. If the
single attempted `UpdateItem` returns the size-specific `ValidationException`,
only that variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=dynamodb_result_item_size_limit`
- `maximumResultBytes=409600`

The limit values are byte counts represented as strings in `providerDetails`;
this contract refers to the Cosmos native ceiling as 2 MiB and the separate
390 KiB serialized and structural portable bounds.

The original cause and sanitized native error code, HTTP status, request ID,
and service details are retained where available. Other
`ValidationException` messages remain `INVALID_REQUEST`. The failed native
update leaves the stored item unchanged.

## Diagnostics

Diagnostics may contain operation/address, status/substatus, activity/request
ID, request charge/capacity, duration, and native diagnostics. They must not
contain field values, serialized payloads, credentials, or authorization data.

## Migration

Complete replacement moves to:

```java
client.upsert(address, key, completeDocument);
```

`upsert()` creates a missing document and does not preserve update's
`NOT_FOUND` guard. TTL-bearing updates also move to a complete create/upsert
write. Read-then-upsert is not atomic, and this release has no exact portable
atomic full-document replace-if-present equivalent.

Changing TTL through a complete write is distinct from TTL timing during
partial update. The latter is outside this release's portable contract. Until
behavior is normalized, callers requiring an existing absolute expiry to remain
fixed MUST NOT call `update()` on TTL-bearing items.
