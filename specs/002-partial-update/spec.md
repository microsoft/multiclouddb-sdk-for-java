# Feature Specification: Portable Partial Update

**Branch**: `002-partial-update`
**Status**: Complete; Cosmos DB and DynamoDB implementation validated, Spanner provider unchanged

## Scope decision

`MulticloudDbClient.update()` becomes a capability-gated shallow set/replace
operation. Cosmos DB and DynamoDB move from full replacement to native partial
update and advertise `PARTIAL_UPDATE`.

The Spanner provider is not part of this feature release. Every file under
`multiclouddb-provider-spanner/` remains identical to the PR base, and Spanner
continues to expose its existing capability set without the three feature-002
partial-update capabilities. After shared validation, a valid Spanner
`update()` call fails at the default client's core capability gate with
non-retryable `UNSUPPORTED_CAPABILITY`, `capability=partial_update`, and zero
Spanner I/O.
## User scenarios

### US1 — Update selected fields without losing omitted data

Given an existing document with `title`, `status`, and `owner`, updating only
`status` changes `status` and preserves `title` and `owner`.

**Acceptance**:

1. Present fields are set/replaced.
2. Omitted fields remain unchanged.
3. A missing document returns `NOT_FOUND` and is not created.
4. The assignments commit atomically.

### US2 — Use predictable shallow value semantics

Scalar values replace scalars. A map or list replaces the complete top-level
value. Java null stores provider-native null for a supported mapping and never
means remove.

Shared conformance runs partial-update behavior on providers advertising the
core capability. Provider-neutral validation still runs before the gate for all
providers.

### US3 — Receive deterministic native-envelope failures

Cosmos DB and DynamoDB normalize lower native partial-update envelopes to
non-retryable `UNSUPPORTED_CAPABILITY` tied to
`partial_update_extended_payload`, with structured size/count details.
Prospective Cosmos batches and oversized Dynamo update expressions fail before
provider I/O. Cosmos DB's state-dependent 2,097,152-byte resulting-document
limit is reported after one attempted patch or batch request, and DynamoDB's
state-dependent 409,600-byte result-item limit is reported after one attempted
`UpdateItem`; no read/merge preflight is added.

### US4 — Migrate callers that relied on replacement

Cosmos DB and DynamoDB callers that used `update()` as complete replacement
move to `upsert(address, key, completeDocument)`. Documentation must warn that
`upsert()` creates a missing document and is not an atomic guarded replacement.

### US5 — Respect the provider release boundary

Cosmos DB and DynamoDB preserve distinct `title` and `TITLE` fields across an
update. Spanner remains unchanged and does not advertise the core operation, so
a valid update is rejected before provider delegation.
## Functional requirements

- **FR-001**: `update()` MUST treat its map as literal top-level fields to set
  or replace.
- **FR-002**: Omitted top-level fields MUST be preserved.
- **FR-003**: Map and list values MUST replace the complete named top-level
  value; recursive merge is out of scope.
- **FR-004**: Java null MUST store null for participating provider mappings that
  support the value.
- **FR-005**: A missing document MUST return `NOT_FOUND` and MUST NOT be
  created.
- **FR-006**: All assignments in one call MUST commit atomically and replaying
  the same absolute assignments MUST be idempotent.
- **FR-007**: Shared preflight MUST reject a null/empty map and null, empty, or
  blank field names as non-retryable `INVALID_REQUEST`.
- **FR-008**: Shared preflight MUST reject, case-insensitively, `id`,
  `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, and `data`; names beginning
  with `_`; and case-insensitive duplicates.
- **FR-009**: Accepted field names MUST NOT be trimmed or rewritten.
- **FR-010**: A non-null `OperationOptions.ttlSeconds()` on `update()` MUST be
  rejected before provider I/O. TTL remains create/upsert-only.
- **FR-011**: The serialized field map limit MUST be exactly 408,576 bytes.
  408,576 bytes passes shared preflight and 408,577 bytes fails.
- **FR-012**: After validation and before delegation, the default client MUST
  gate `Capability.PARTIAL_UPDATE`; an unsupported provider receives
  non-retryable `UNSUPPORTED_CAPABILITY` with
  `providerDetails.capability=partial_update`.
- **FR-013**: `PARTIAL_UPDATE_EXTENDED_PAYLOAD` MUST describe whether supported
  provider field mappings can reach the common size limit without a lower
  provider request or resulting-item envelope. It MUST NOT disable ordinary
  updates.
- **FR-014**: Cosmos DB and DynamoDB MUST declare all 20 known capabilities.
  Unchanged Spanner MUST retain its existing 17 declarations and MUST NOT
  advertise any feature-002 partial-update capability.
- **FR-015**: A valid `update()` against a provider that does not advertise
  `PARTIAL_UPDATE` MUST fail at the shared gate with non-retryable
  `UNSUPPORTED_CAPABILITY`, `capability=partial_update`, and zero provider I/O.
- **FR-016**: Cosmos DB MUST encode each raw field name as one RFC 6901 segment
  (`~` → `~0`, `/` → `~1`) and use `set`.
- **FR-017**: Cosmos DB MUST issue one direct `patchItem` for up to 10 fields.
- **FR-018**: Cosmos DB MUST issue one same-item, same-partition transactional
  batch of at-most-10-operation patch chunks for wider requests.
- **FR-019**: Cosmos DB MUST reject a prospective batch over 100 batch
  operations or 2,097,152 serialized UTF-8 bytes before I/O.
- **FR-020**: Cosmos DB MUST NOT add an adapter read, replace, or retry loop.
- **FR-021**: A failed Cosmos batch MUST surface the first non-424 operation
  failure, otherwise a usable non-424 aggregate failure, otherwise a sanitized
  `PROVIDER_ERROR`. HTTP 424 MUST NOT be presented as the root cause.
- **FR-022**: Cosmos CRUD/update HTTP 408 and 410 failures MUST be transient and
  retryable; 410 substatus MUST be preserved.
- **FR-023**: Cosmos write response bodies MAY be disabled only while status,
  activity ID, request charge, duration, and diagnostics used by existing
  write paths remain available.
- **FR-024**: DynamoDB MUST issue one conditional `UpdateItem` with stable name
  and value aliases, one `SET` assignment per field, and an aliased
  `attribute_exists(partitionKey)` guard.
- **FR-025**: DynamoDB values MUST preserve null, scalar, map, and list shapes.
- **FR-026**: DynamoDB MUST measure the complete update expression as UTF-8;
  4,096 bytes passes and 4,097 bytes fails before I/O.
- **FR-027**: DynamoDB conditional failure on the existence guard MUST map to
  `NOT_FOUND`; no read, `PutItem`, or adapter retry loop may be added.
- **FR-028**: Provider diagnostics MUST be concise and MUST NOT log field
  values, serialized request bodies, credentials, or authorization data.
- **FR-029**: Shared conformance MUST run supported partial-update behavior only
  when `PARTIAL_UPDATE` is advertised, while retaining provider-neutral
  preflight and unsupported-gate assertions for unchanged Spanner.
- **FR-030**: Migration documentation MUST direct replacement callers to
  `upsert()` and explain its create-on-missing behavior.
- **FR-031**: On `update()` only, the DynamoDB `ValidationException` message
  variant indicating that the resulting item exceeds the maximum item size MUST
  map to non-retryable `UNSUPPORTED_CAPABILITY` with
  `capability=partial_update_extended_payload`,
  `reason=dynamodb_result_item_size_limit`, and
  `maximumResultBytes=409600`. Other `ValidationException` failures MUST remain
  `INVALID_REQUEST`. The original cause and sanitized native error code, status,
  request ID, and service details MUST be preserved where available, without
  payload data.
- **FR-032**: On `update()` only, Cosmos DB HTTP 413 MUST map to
  non-retryable `UNSUPPORTED_CAPABILITY` with
  `capability=partial_update_extended_payload`,
  `reason=cosmos_result_item_size_limit`, and
  `maximumResultBytes=2097152`. The direct exception cause and sanitized
  native status, substatus, activity ID, and request charge MUST be preserved
  where available. The failed native patch or batch MUST leave the item
  unchanged. HTTP 413 from other operations MUST retain the normal Cosmos
  provider-error mapping.
- **FR-033**: Providers advertising `PARTIAL_UPDATE` MUST also declare
  `PARTIAL_UPDATE_CASE_SENSITIVE_FIELDS`. Cosmos DB and DynamoDB MUST declare it
  supported and preserve case-distinct field identities.

## Provider behavior matrix

| Concern | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Core partial update | Native patch | Native `UpdateItem` | Not advertised; shared gate rejects |
| Missing item | 404 → `NOT_FOUND` | failed existence condition → `NOT_FOUND` | Not reached |
| Wide request | same-item transactional batch | one larger expression | Not reached |
| Lower native envelope | 100 batch ops / 2 MiB request; 2 MiB resulting item | 4,096-byte expression; 409,600-byte resulting item | Not declared |
| Case-distinct names | preserved | preserved | Not part of this release |
| Adapter read/retry | no read/retry; result-size rejection follows one attempted patch/batch | no read/retry; result-size rejection follows one attempted `UpdateItem` | zero provider I/O |
## Edge cases

- Empty maps, blank names, reserved names, underscore-prefixed names,
  case-insensitive collisions, update TTL, and 408,577-byte maps fail before
  provider delegation.
- Names containing `.`, `/`, `~`, or surrounding spaces remain literal. Cosmos
  escapes them and Dynamo aliases them.
- Across calls, Cosmos and Dynamo preserve case-distinct names.
- A valid Spanner update stops at the shared core capability gate and performs
  no provider I/O.
- More than 10 Cosmos fields use one atomic batch, never independent patch
  calls.
- All-424 or empty failed Cosmos batch result lists use the aggregate fallback
  or the sanitized no-root error.
- A small Cosmos update can pass shared and batch preflight but fail with HTTP
  413 when the existing document plus assignments would exceed 2,097,152
  bytes. The failure becomes the extended-payload capability error and leaves
  the document unchanged.
- Dynamo reserved words and punctuation never appear directly in the update
  expression.
- A small Dynamo update can pass shared and expression preflight but fail when
  the existing item plus assignments would exceed 409,600 bytes. Only the
  item-size `ValidationException` variant becomes the extended-payload
  capability error; the failed native update leaves the item unchanged.

## Non-functional requirements

- **NFR-001**: Keep planners package-private, deterministic, and small.
- **NFR-002**: Use one adapter SDK invocation for each accepted Cosmos or
  Dynamo update. Vendor-managed retries are outside this count.
- **NFR-003**: Local validation failures perform zero provider I/O.
- **NFR-004**: No unsafe casts, swallowed failures, private vendor SDK imports,
  or read/replace emulation may be introduced.
- **NFR-005**: The PR MUST contain no changes under `multiclouddb-provider-spanner/`.

## Success criteria

- **SC-001**: Focused API tests pass for validation order, capability gating,
  TTL rejection, and the exact common-size boundary.
- **SC-002**: Focused Cosmos tests prove direct patch, wide batch, RFC 6901
  escaping, local limits, batch failure fallback, exact 408/410 mapping,
  update-only 413 result-size normalization, diagnostics, and the updated
  consistency test.
- **SC-003**: Focused Dynamo tests prove one aliased conditional `UpdateItem`,
  structured values, exact expression measurement, `NOT_FOUND`, zero-I/O
  expression rejection, narrow result-item-size error normalization, cause
  preservation, and unchanged state after the failed native update.
- **SC-004**: Shared conformance passes supported behavior on Cosmos DB and
  DynamoDB, and verifies Spanner's shared-validation ordering plus core
  capability rejection without provider I/O.
- **SC-005**: `git diff --check` passes; the Spanner provider has zero PR diff,
  and `multiclouddb-perf/` is untouched.

## Out of scope

- any Spanner provider code, capability, changelog, schema, or fixture change
- remove/increment/nested-path patch operations
- a new `replace()` API or compatibility mode
- native-client escape hatch, cancellation, or retry-policy work tracked by
  issues #102, #103, and #104
- changes under `multiclouddb-perf/`
