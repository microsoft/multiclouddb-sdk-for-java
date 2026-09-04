# Specification Quality Checklist: Portable Partial Update

**Reviewed**: 2026-09-02
**Scope**: Cosmos DB and DynamoDB implementation; unchanged Spanner excluded by the core capability gate

## Scope and consistency

- [x] Every path under `multiclouddb-provider-spanner/` matches the PR base.
- [x] No artifact requires Spanner capability, data-path, changelog, schema,
  fixture, or E2E helper changes.
- [x] Spanner is described as outside the feature release and rejected by the
  shared `partial_update` capability gate.
- [x] Shared conformance gates supported behavior by capability while retaining
  provider-neutral preflight and unsupported-gate coverage.
- [x] Cosmos/Dynamo replacement-to-partial-update migration remains explicit.

## Shared API

- [x] Both existing `update()` overloads and `Map<String,Object>` are retained.
- [x] Shallow set/replace, omitted-field preservation, atomicity, idempotency,
  and missing-item `NOT_FOUND` are unambiguous.
- [x] Null/empty maps and invalid names are specified as zero-I/O
  `INVALID_REQUEST`.
- [x] Reserved names, underscore prefixes, case-insensitive collisions, and
  no-trimming behavior are explicit.
- [x] Update TTL rejection is explicit and create/upsert migration is clear.
- [x] The common limit is exactly 408,576 bytes with pass/fail boundaries.
- [x] The core `partial_update` gate and future unsupported-provider error are
  explicit.
- [x] `partial_update_extended_payload` covers native request and
  resulting-item envelopes for supported provider mappings.
- [x] Cosmos and Dynamo declare all 20 known capabilities; unchanged Spanner retains 17 and advertises no feature-002 capability.

## Cosmos DB

- [x] Literal RFC 6901 `set` paths are specified.
- [x] Direct patch through 10 fields is specified.
- [x] One same-item transactional batch for wider requests is specified.
- [x] No read, replace, independent patch loop, or adapter retry is allowed.
- [x] The 100-operation and 2,097,152-byte local limits and details are complete.
- [x] Update HTTP 413 is a state-dependent 2,097,152-byte result-item
  capability error after one attempted patch/batch; non-update 413 behavior is
  unchanged.
- [x] Batch root selection skips 424 and has aggregate/no-root fallbacks.
- [x] Exact 408/410 transient mapping is specified.
- [x] Diagnostics exclude payloads and secrets.
- [x] Disabling write response bodies is conditioned on preserving metadata used
  by existing write paths.

## DynamoDB

- [x] One conditional aliased `UpdateItem` is specified.
- [x] Structured null/map/list values are required.
- [x] The complete update expression is measured in UTF-8.
- [x] The 4,096-byte boundary and structured error details are complete.
- [x] The state-dependent 409,600-byte result-item rejection is normalized only
  for the matching update `ValidationException`, preserves the cause/native
  metadata, and does not add a read preflight.
- [x] Conditional failure maps to `NOT_FOUND`.
- [x] No read, `PutItem`, TTL assignment, or adapter retry is allowed.

## Testing and delivery

- [x] T001–T015 remain retained and completed.
- [x] Focused API, Cosmos, and Dynamo unit tests are named.
- [x] Existing replace-to-patch consistency coverage is updated.
- [x] Runnable shared `CrudConformanceTests` cover update TTL,
  invalid/reserved-field atomic failure, 408,577-byte atomic failure, and a
  wide missing-item update without provider branches.
- [x] Concrete Cosmos and Dynamo emulator regressions cover result-item
  overflow and unchanged stored state.
- [x] Shared conformance verifies Spanner core capability rejection without
  entering provider code.
- [x] Shared conformance covers Cosmos/Dynamo case identity and keeps the exact
  limit assertion capability-gated; all three emulator profiles pass.
- [x] `multiclouddb-perf/` is excluded.
- [x] Issues #102–#104 remain out of scope.

## Notes

The feature artifacts describe the completed Cosmos/Dynamo implementation and
the final zero-Spanner-diff release boundary. `tasks.md` records validation.
