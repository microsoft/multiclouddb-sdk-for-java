# Phase 0 Research: Portable Partial Update

**Branch**: `002-partial-update`
**Reconciled**: 2026-09-04 for the Cosmos/Dynamo release scope and unchanged Spanner boundary

## Decision 1 — Keep the existing Java API

Retain both `update()` overloads and `Map<String,Object>`. Rename only the
parameter from `document` to `fields`.

**Why**: Java parameter names are not binary API, and a new patch type or method
would expand scope unnecessarily.

## Decision 2 — Exclude Spanner from this feature release

Restore every file under `multiclouddb-provider-spanner/` to the PR base and do
not advertise any feature-002 partial-update capability there. The default
client already gates `partial_update`, so a valid Spanner update returns
non-retryable `UNSUPPORTED_CAPABILITY` before provider delegation.

**Why**: Spanner is not being released with this feature. Explicitly gating the
operation avoids both an unplanned provider change and silent invocation of its
legacy, case-insensitive fixed-schema behavior.

**Rejected**:

- retaining the casing guard, because it changes an unreleased provider module;
- bypassing the core gate for Spanner, because that restores silent field-case
  divergence; and
- adding a provider-ID special case in shared code.
## Decision 3 — Define a shallow absolute operation

Present fields are set/replaced; omitted fields survive; map/list values replace
as units; null is a stored null for a supported mapping.

**Why**: Cosmos `set` and Dynamo `SET` share these semantics. Absolute
assignments are replay-idempotent.

Recursive merge, nested paths, remove, increment, and conditional field updates
are out of scope.

## Decision 4 — Centralize preflight

The default client validates:

1. non-null/non-empty map;
2. non-null/non-empty/non-blank names;
3. reserved names and underscore prefix;
4. case-insensitive collisions;
5. update TTL;
6. exact 408,576-byte serialized size; and
7. core capability support.

**Why**: one preflight gives all providers the same category and zero-I/O
behavior.

Accepted names are literal and are not trimmed. Cosmos and Dynamo support
punctuation through escaping and aliases.

## Decision 5 — Reject TTL on update

`OperationOptions.ttlSeconds()` remains create/upsert-only. A non-null value on
`update()` is `INVALID_REQUEST` before provider I/O.

**Why**: provider-specific TTL mutation would break portable behavior and make
replay time-relative.

## Decision 6 — Keep three capability declarations

- `partial_update`: core shallow set/replace behavior, internally gated.
- `partial_update_extended_payload`: no lower provider request or
  resulting-item envelope for field mappings already supported by that
  provider.
- `partial_update_case_sensitive_fields`: case-distinct names retain separate
  literal identities.

Cosmos and Dynamo declare the payload extension unsupported and case-sensitive
identity supported. Spanner declares none of the three capabilities because it
is outside the feature release. Cosmos and Dynamo therefore expose 20 known
names while unchanged Spanner retains 17.

## Decision 7 — Cosmos uses direct patch plus one atomic wide batch

- up to 10 fields: one `patchItem`;
- wider maps: one same-item, same-partition `CosmosBatch` of patch chunks;
- no adapter read, replace, or retry loop.

Field names are encoded as one RFC 6901 segment.

For wide requests, mirror the public SDK JSON shape and reject more than 100
batch operations or more than 2,097,152 UTF-8 bytes before I/O.

**Rejected**:

- read/merge/replace, because it adds RU cost and races;
- independent patch requests, because they are not atomic; and
- private SDK serialization APIs, because they are not stable public contract.

## Decision 8 — Cosmos batch errors skip 424

Select the first usable failed operation status other than 424, then a usable
aggregate status, then return a sanitized no-root `PROVIDER_ERROR`.

HTTP 424 represents rollback dependency, not root cause. HTTP 408 and 410 are
transient/retryable for CRUD/update; 410 substatus is retained.

## Decision 9 — Cosmos write bodies can be disabled

Use `contentResponseOnWriteEnabled(false)`.

**Why**: all portable writes return `void`; existing paths use only response
metadata. Focused tests cover constructor configuration and create/update/
upsert consistency invariants.

## Decision 10 — Dynamo uses one aliased UpdateItem

Generate stable `#fN`/`:vN` aliases, an aliased
`attribute_exists(#pk)` guard, and one `SET` expression.

Map values through the structured item mapper. Measure the complete update
expression in UTF-8; 4,096 bytes passes and 4,097 fails.

Conditional failure maps to `NOT_FOUND`. No read, `PutItem`, or adapter retry
loop is used.

## Decision 11 — Normalize DynamoDB's state-dependent result-item limit

An update can have a small fields map and short expression but still push an
existing item above DynamoDB's 409,600-byte limit. Do not read and merge before
the write. Attempt the one conditional `UpdateItem`, then recognize only the
size-specific `ValidationException` message for `update()`.

That variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=dynamodb_result_item_size_limit` and
`maximumResultBytes=409600`. Other `ValidationException` messages remain
`INVALID_REQUEST`; the native cause and sanitized code/status/request ID/service
details are retained without payload data.

**Why**: a read preflight adds cost and a race. DynamoDB already rejects the
oversized result atomically, so normalizing that one native response preserves
state and portability with one attempted write.

## Decision 12 — Normalize Cosmos DB's state-dependent result-item limit

An update can have a small fields map and valid native request envelope but
still push an existing Cosmos document above 2,097,152 bytes. Do not read and
merge before the write. Attempt the one direct patch or atomic batch, then map
HTTP 413 from `update()` to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=cosmos_result_item_size_limit` and
`maximumResultBytes=2097152`.

Direct exceptions retain their cause and sanitized native metadata; failed
batches retain sanitized aggregate/result diagnostics. HTTP 413 from other
operations keeps the general provider-error mapping.

**Why**: a read preflight adds RU cost and a race. Cosmos rejects the
oversized result atomically, so update-scoped status normalization preserves
state and portability with one attempted native write.

## Decision 13 — Keep shared runtime assertions capability-driven

Shared invalid-map/name, update-TTL, and 408,577-byte assertions run on all
providers because validation precedes the core gate. Supported behavior runs
only where `partial_update` is advertised. A dedicated assertion verifies that
unchanged Spanner fails locally with `UNSUPPORTED_CAPABILITY` and
`capability=partial_update`.

Case-distinct identity runs on Cosmos and Dynamo, which advertise the case
capability. The exact 408,576-byte positive runtime assertion remains gated by
`partial_update_extended_payload`; no participating provider currently
advertises it, while API tests lock the shared boundary.

Concrete Cosmos and Dynamo regressions continue to exercise their native
result-item limits.
## Decision 14 — Preserve migration intent

Callers that require complete replacement move to `upsert()` and must be told
that it creates a missing document. TTL-bearing updates also move to a complete
create/upsert write.

No compatibility flag or new `replace()` method is added.

## Baseline repository gaps

Issues #102 (native client access), #103 (cancellation), and #104 (configurable
safe retries) predate this feature and remain out of scope.
