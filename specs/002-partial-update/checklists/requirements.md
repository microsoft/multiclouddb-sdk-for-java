# Specification Quality Checklist: Portable Partial Update

**Reviewed**: 2026-09-15
**Scope**: Cosmos DB and DynamoDB implementation; Spanner unsupported through the API capability default

## Scope and consistency

- [x] Spanner omits the core Feature 002 `partial_update` capability and keeps
  its write path unchanged; shared read/query identity cleanup occurs in the
  default client, and the only provider production adjustment restores caller
  field casing during row mapping.
- [x] No artifact requires a portable Spanner partial-update data path, schema,
  capability declaration, or E2E update helper; changelog and tests describe the boundary.
- [x] Spanner partial update is described as deliberately unsupported in this
  feature release and rejected by the shared `partial_update` capability gate;
  emulator evidence is not described as live production validation.
- [x] Shared conformance gates supported behavior by capability while retaining
  provider-neutral preflight and unsupported-gate coverage.
- [x] Cosmos/Dynamo replacement-to-partial-update migration remains explicit.

## Shared API

- [x] Both existing `update()` overloads and `Map<String,Object>` are retained.
- [x] Shallow set/replace, omitted-field preservation, atomicity, idempotency,
  and missing-item `NOT_FOUND` are unambiguous.
- [x] Null/empty maps, binary values, unsafe graphs, and invalid or overlong names are specified
  as zero-I/O `INVALID_REQUEST`.
- [x] Create/upsert/update top-level maps are snapshotted once through bounded
  SDK-owned Jackson; the detached normalized result is the exact provider input,
  including POJO cycle/depth/serializer-reentry handling and binary detection.
- [x] Null create/upsert documents, all case-insensitive provider-owned
  top-level names (`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`),
  and underscore-prefixed top-level names are specified as zero-I/O
  `INVALID_REQUEST`; remaining complete-write top-level names are unique ignoring
  case and at most 128 Unicode characters.
- [x] Reserved-name matching, underscore prefixes, no-trimming behavior, and
  same-request acceptance of case-distinct non-reserved names are explicit.
- [x] Update TTL rejection is explicit and create/upsert migration is clear.
- [x] The common limits are 10 update fields plus 390 KiB serialized and structural
  write-input bounds, with pass/fail boundaries.
- [x] Replacement values are limited to 31 nested map/list containers, with the top-level replacement container counted as level 1.
- [x] Complete documents and incoming update fields have a 390 KiB structural-footprint
  limit covering UTF-8 names and native map/list overhead, including compact-JSON
  boundary coverage and the 50,000-byte per-name bound.
- [x] Structural failures are zero-I/O, reason-coded `INVALID_REQUEST` with actual/maximum details; existing-state result overflow remains native without a read/merge preflight.
- [x] The core `partial_update` gate and future unsupported-provider error are
  explicit.
- [x] The base result envelope requires both serialized JSON and structural
  footprint at or below 390 KiB. State-dependent larger results are explicitly
  outside this release's portable contract and may succeed or fail under native
  provider limits.
- [x] TTL timing is explicitly outside the portable contract: DynamoDB happens
  to leave `ttlExpiry` unchanged, while Cosmos patch advances `_ts` and restarts
  relative TTL. Callers requiring fixed absolute expiry must not call
  `update()` on TTL-bearing items until behavior is normalized.
- [x] The proposed provider-specific size and TTL capabilities were removed
  during review because a single-provider capability does not establish a
  portable contract.
- [x] Every built-in provider exposes 18 effective capability rows; only an
  omitted `PARTIAL_UPDATE` declaration defaults to unsupported, while unrelated
  omissions in arbitrary partial sets remain absent.
- [x] Shared preflight enforces six input/structure limits: both 399,360-byte
  bounds, 50,000-byte nested/partial-update names, 128-character complete-write
  top-level names, 31 nested containers, and 10 update fields. Implementation
  constants remain internal rather than becoming compile-time public API.

## Cosmos DB

- [x] Literal RFC 6901 `set` paths are specified.
- [x] Every accepted update uses one direct patch.
- [x] Maps above 10 fields fail shared validation before Cosmos I/O.
- [x] No read, replace, independent patch loop, or adapter retry is allowed.
- [x] The planner defensively enforces the 10-field limit for direct SPI calls.
- [x] Update HTTP 413 is a state-dependent 2,097,152-byte result-item
  `UNSUPPORTED_CAPABILITY` error after one attempted patch; non-update 413
  behavior is unchanged.
- [x] Field names are sorted so map iteration order cannot change the native plan.
- [x] Exact 408/410 transient mapping is specified.
- [x] Diagnostics exclude payloads and secrets.
- [x] Disabling write response bodies is conditioned on preserving metadata used
  by existing write paths.
- [x] Cosmos records the non-portable TTL behavior: `patchItem` advances `_ts`
  and restarts the relative TTL countdown.

## DynamoDB

- [x] One conditional aliased `UpdateItem` is specified.
- [x] Structured null/map/list values are required.
- [x] The shared 31-level and structural-footprint limits prevent compact replacement values from exceeding DynamoDB's native structure envelope before I/O.
- [x] The shared 10-field limit keeps generated expressions safely below the
  native ceiling; the defensive planner guard is not a public envelope.
- [x] The state-dependent native result-item rejection is normalized only
  for the matching update `ValidationException`, preserves the cause/native
  metadata, and does not add a read preflight.
- [x] Conditional failure maps to `NOT_FOUND`.
- [x] No read, `PutItem`, TTL assignment, or adapter retry is allowed.
- [x] DynamoDB records the non-portable implementation detail that `UpdateItem`
  leaves the absolute `ttlExpiry` attribute unchanged.

## Testing and delivery

- [x] T001–T015 remain retained and completed.
- [x] Focused API, Cosmos, and Dynamo unit tests are named.
- [x] Existing replace-to-patch consistency coverage is updated.
- [x] Runnable shared `CrudConformanceTests` cover exact serialized/structural create/upsert boundaries and
  supported update read-back, update TTL, invalid/reserved-field atomic failure,
  over-limit atomic failure, and a wide missing-item update without provider branches.
- [x] Concrete Cosmos and Dynamo emulator regressions cover result-item
  overflow and unchanged stored state.
- [x] Shared conformance verifies Spanner core capability rejection without
  entering provider code.
- [x] Shared conformance covers Cosmos/Dynamo case identity and keeps the exact
  limit assertion capability-gated, including `foo` and `Foo` in one request.
- [x] API/shared coverage rejects every provider-owned and underscore-prefixed
  complete-write top-level name.
- [x] Shared-client and conformance coverage strips adapter-owned fields from
  read/query results and proves an in-envelope read result can be reused by `upsert()`.
- [x] Capability conformance covers all 18 effective rows and the core Feature
  002 capability matrix. Earlier optional-capability matrix work is retained as
  superseded history and was removed from the final contract during review.
- [x] Current DynamoDB Local and Spanner emulator validation ran; the Spanner
  evidence covers the deliberate unsupported boundary and provider-direct
  legacy regression, not live production validation.
- [ ] T061 final post-remediation unit/emulator/E2E rerun and scope audit remains
  pending because the Cosmos emulator is unavailable.
- [x] `multiclouddb-perf/` is excluded.
- [x] Issues #102–#104 remain out of scope.

## Notes

The feature artifacts describe the completed Cosmos/Dynamo implementation and
the intended zero-Spanner-production-diff release boundary. Changelog and
provider-direct test alignment are allowed; `tasks.md` keeps T061 pending until
the Cosmos emulator can run the post-remediation profile.
