# Phase 0 Research: Portable Partial Update

**Branch**: `002-partial-update`
**Reconciled**: 2026-09-15 for the implemented Cosmos/Dynamo contract and API-default Spanner unsupported boundary

## Decision 1 — Keep the existing Java API

Retain both `update()` overloads and `Map<String,Object>`. Rename only the
parameter from `document` to `fields`.

**Why**: Java parameter names are not binary API, and a new patch type or method
would expand scope unnecessarily.

## Decision 2 — Exclude Spanner partial update from this feature release

Deliberately leave portable Spanner partial update unsupported. The shared default
client performs portable read/query identity cleanup after provider mapping, and
the Spanner write path remains unchanged. The only Spanner production adjustment
matches `FIELD_DATA` metadata to physical columns case-insensitively to preserve
caller field spelling. `CapabilitySet` supplies an unsupported default only for
the core Feature 002 capability when the provider omits it.
The default client gates the operation, so a valid Spanner update returns
non-retryable `UNSUPPORTED_CAPABILITY` before provider delegation.

**Why**: This release intentionally limits the supported native update paths to
Cosmos DB and DynamoDB rather than silently invoking Spanner's legacy behavior
or requiring a coordinated Spanner provider release. The Spanner emulator ran
shared-validation/capability-gate coverage and the provider-direct legacy
regression. No live production Spanner validation was performed or is claimed;
future portable Spanner update support requires a separate release decision.

**Rejected**:

- preserving exact-case result mapping, because Spanner column names are
  case-insensitive and physical schema casing must not leak into portable results;
- bypassing the core gate for Spanner, because that restores silent field-case
  divergence; and
- adding a provider-ID special case in shared code.

## Decision 3 — Define a shallow absolute operation

Present fields are set/replaced; omitted fields survive; map/list values replace
as units; null is a stored null for a supported mapping.

**Why**: Cosmos `set` and Dynamo `SET` share these semantics. Absolute
assignments are replay-idempotent for logical document fields; provider-maintained
metadata and TTL timing are not part of that guarantee.

Recursive merge, nested paths, remove, increment, and conditional field updates
are out of scope.

## Decision 4 — Centralize preflight

The default client validates:

1. non-null/non-empty map;
2. non-null/non-empty/non-blank names no longer than 50,000 UTF-8 bytes;
3. reserved names and underscore prefix;
4. case-distinct non-reserved names remain separate literal fields, including
   both variants in one map;
5. update TTL;
6. reject binary values, including values hidden in POJOs;
7. bounded SDK-owned Jackson serialization, rejecting non-collection iterables
   before iteration;
8. portable 390 KiB serialized-size limit;
9. replacement map/list depth up to 31 containers;
10. every nested field name no longer than 50,000 UTF-8 bytes;
11. portable 390 KiB structural footprint; and
12. core capability support.

**Why**: one preflight gives all providers the same category and zero-I/O
behavior. Top-level update paths can still carry deeply nested replacement values,
and compact JSON such as a dense list of empty containers understates DynamoDB's
native item accounting. The structure-aware pass applies the lowest common
denominator before the capability gate while inspecting only incoming fields.
Complete documents use the same binary-value, depth, nested-name, and structural
limits; a null document, every case-insensitive top-level provider-owned name
(`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`), and every
underscore-prefixed top-level name are rejected before I/O. Remaining top-level
names must be unique ignoring case and contain at most 128 Unicode characters,
the Spanner-compatible portable baseline. Create, upsert, and update snapshot
top-level maps, perform one bounded SDK-owned Jackson serialization, and delegate
its detached normalized representation. The six input/structure enforcement
defaults remain package-private rather than becoming compile-time public API.
The 50,000-byte nested/partial-update name bound
is at or below the AWS SDK's 50,000-character DynamoDB response-parser limit,
including for multibyte UTF-8 names.
Existing-state result size remains native and does not justify a read/merge
preflight.

Accepted names are literal and are not trimmed. Cosmos and Dynamo support
punctuation through escaping and aliases.

## Decision 5 — Reject TTL on update

`OperationOptions.ttlSeconds()` remains create/upsert-only. A non-null value on
`update()` is `INVALID_REQUEST` before provider I/O.

**Why**: provider-specific TTL mutation would break portable behavior and make
replay time-relative.

## Decision 6 — Keep one portable result envelope

`partial_update` covers core shallow set/replace behavior only when serialized
result JSON and portable structural footprint are each at most 390 KiB.
State-dependent results above either bound are outside this release's portable
contract and may succeed or fail under provider-native ceilings.

The proposed provider-specific larger-result capability was removed during
review because Cosmos-only support did not establish a portable contract.
Result size depends on stored state, and a read/merge preflight would add cost
and a race. Native failures therefore retain stable, structured reasons and
limits after at most one attempted native update. Portable resulting-size
normalization continues in [#114](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/114).

## Decision 7 — Keep TTL timing outside the portable contract

The proposed provider-specific TTL-preservation capability was removed during
review because DynamoDB-only behavior did not establish a portable contract.

- DynamoDB `UpdateItem` does not assign `ttlExpiry`, so the existing absolute
  expiry happens to remain unchanged.
- Cosmos DB `patchItem` advances `_ts`, which restarts the countdown for a
  TTL-bearing item.

Until behavior is normalized, callers requiring fixed absolute expiry must not
call `update()` on TTL-bearing items. Shared result cleanup and the Spanner
mapper casing fix remain independent of partial update. Portable TTL-expiry
normalization continues in [#113](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/113).

## Decision 8 — Keep every accepted update to one native write

The portable contract accepts at most 10 fields per call. Shared preflight
rejects wider maps before provider delegation. Cosmos uses one `patchItem`;
DynamoDB uses one `UpdateItem`. The Cosmos planner sorts literal field names
before constructing patch operations so caller map iteration order cannot change
the native plan.

**Rejected**:

- Cosmos transactional batches, because patch-chunk count creates unbounded RU
  asymmetry and intermediate item states can depend on caller map order;
- read/merge/replace, because it adds RU cost and races; and
- independent patch requests, because they are not atomic.

## Decision 9 — Cosmos write bodies can be disabled

Use `contentResponseOnWriteEnabled(false)`.

**Why**: all portable writes return `void`; existing paths use only response
metadata. Focused tests cover constructor configuration and create/update/
upsert consistency invariants.

## Decision 10 — Dynamo uses one aliased UpdateItem

Generate stable `#fN`/`:vN` aliases, an aliased
`attribute_exists(#pk)` guard, and one `SET` expression.

Map values through the structured item mapper. The shared 10-field limit keeps
the generated expression safely below the DynamoDB native expression ceiling.
The planner retains a defensive size check for direct SPI misuse, but that path
is not part of the portable public contract.

Conditional failure maps to `NOT_FOUND`. No read, `PutItem`, or adapter retry
loop is used.

## Decision 11 — Normalize DynamoDB's state-dependent result-item limit

An update can have a small fields map and short expression but still push an
existing item above the DynamoDB native limit. Do not read and merge before
the write. Attempt the one conditional `UpdateItem`, then recognize only the
size-specific `ValidationException` message for `update()`.

That variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=dynamodb_result_item_size_limit` and
`maximumResultBytes`. Other `ValidationException` messages remain
`INVALID_REQUEST`; the native cause and sanitized code/status/request ID/service
details are retained without payload data.

**Why**: a read preflight adds cost and a race. DynamoDB already rejects the
oversized result atomically, so normalizing that one native response preserves
state and portability with one attempted write.

## Decision 12 — Normalize Cosmos DB's state-dependent result-item limit

An update can have a small fields map and valid shared preflight but
still push an existing Cosmos document above 2,097,152 bytes. Do not read and
merge before the write. Attempt the one direct patch, then map
HTTP 413 from `update()` to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=cosmos_result_item_size_limit` and
`maximumResultBytes`.

Direct exceptions retain their cause and sanitized native metadata. HTTP 413 from other
operations keeps the general provider-error mapping.

**Why**: a read preflight adds RU cost and a race. Cosmos rejects the
oversized result atomically, so update-scoped status normalization preserves
state and portability with one attempted native write.

## Decision 13 — Keep shared runtime assertions capability-driven

Shared invalid-map/name, update-TTL, and over-limit assertions run on all
providers because validation precedes the core gate. Supported behavior runs
only where `partial_update` is advertised. A dedicated assertion verifies that
the API defaults Spanner's omitted capability to unsupported and fails locally
with `UNSUPPORTED_CAPABILITY` and `capability=partial_update`.

Case-distinct identity, including `foo` and `Foo` in one atomic request, runs
directly on Cosmos and DynamoDB as part of the core partial-update contract. API
recording-provider tests prove the exact 390 KiB boundary reaches
delegation while one byte over does not. When each emulator is available, shared conformance verifies exact-limit
create/upsert on every provider and partial update on Cosmos/DynamoDB.
Concrete Cosmos and DynamoDB regressions continue to exercise their native
result-item limits.
Capability conformance asserts 18 effective rows and the core partial-update
matrix for each built-in provider. Provider-specific native result limits and
TTL timing remain implementation evidence rather than capability matrices. An
older Spanner provider's 17 declarations receive exactly the one core Feature
002 unsupported default, while unrelated omissions remain absent.

## Decision 14 — Preserve migration intent

Callers that require complete replacement move to `upsert()` and must be told
that it creates a missing document. TTL-bearing updates also move to a complete
create/upsert write.

That migration changes TTL through a complete write. TTL timing during partial
update is outside this release's portable contract. Until behavior is
normalized, a caller requiring an already TTL-bearing item's absolute expiry to
stay fixed must not call `update()`.

No compatibility flag or new `replace()` method is added. Read-then-upsert is
not atomic, and this release has no exact portable atomic full-document
replace-if-present equivalent.

## Current validation record

- DynamoDB Local validation ran for the supported path.
- The Spanner emulator ran the shared unsupported-gate coverage and
  provider-direct legacy regression; this is not live production Spanner
  validation and does not change the unsupported capability state.
- The Cosmos emulator is unavailable in the current environment, so T061
  remains pending.

## Baseline repository gaps

Issues #102 (native client access), #103 (cancellation), and #104 (configurable
safe retries) predate this feature and remain out of scope.
