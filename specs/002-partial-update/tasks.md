---
description: "Focused implementation tasks for portable partial update"
branch: "002-partial-update"
status: "in_progress"
---

# Tasks: Portable Partial Update

**Binding design**: `specs/002-partial-update/design.md`
**Scope**: shared API plus Cosmos DB and DynamoDB partial-update implementation.
Spanner keeps the core Feature 002 capability unsupported and its write path
unchanged. Shared read/query identity cleanup occurs in the default client; the
only Spanner production adjustment restores caller field spelling across
case-insensitive metadata/physical-column matches.

Implementation tasks are complete. T061 final post-remediation validation
remains pending because the Cosmos emulator is unavailable in the current
environment. DynamoDB Local and Spanner emulator validation ran; the Spanner
run verifies the deliberate unsupported boundary and is not live production
Spanner validation.

## Phase 1: Setup and baseline

- [X] T001 Verify Java/dependency versions and the `unit`, `emulator-cosmos`, `emulator-dynamo`, and `emulator-spanner` profile group filters in `pom.xml`, `multiclouddb-api/pom.xml`, `multiclouddb-provider-cosmos/pom.xml`, `multiclouddb-provider-dynamo/pom.xml`, `multiclouddb-provider-spanner/pom.xml`, `multiclouddb-conformance/pom.xml`, and `multiclouddb-e2e/pom.xml`; retain pinned versions unless a binding-design API is unavailable
- [X] T002 Verify the pre-change `update()` behavior and test assumptions in the API and all three providers before editing the migration paths
- [X] T003 Run and retain the pre-change targeted unit baseline for API/Cosmos/Dynamo/Spanner modules

## Phase 2: Shared API and preflight

- [X] T004 Add `PartialUpdateValidatorTest` coverage for null/empty/over-10 maps; null, empty, and blank names; non-trimmed names; case-insensitive reserved names; underscore prefixes; same-request case-distinct acceptance; punctuation acceptance; and update TTL rejection
- [X] T005 Add `DefaultMulticloudDbClientPartialUpdateTest` coverage for closed-client precedence, zero delegation, validation order, core capability gating, and the 10-field limit
- [X] T006 Add `DocumentSizeValidatorTest` coverage proving the 390 KiB boundary, typed serialization failures with preserved causes, and non-retryable `INVALID_REQUEST`
- [X] T007 Add `MulticloudDbClientPartialUpdateContractTest` coverage for both existing overloads, `Map<String,Object>`, default options, and TTL rejection
- [X] T008 Rewrite `MulticloudDbClient.update()` Javadocs for shallow set/replace, omitted-field preservation, mapping-aware null semantics, missing-item `NOT_FOUND`, exact validation, capability gating, and replacement migration
- [X] T009 Rewrite `MulticloudDbProviderClient.update()` Javadocs for the validated SPI contract and participating-provider boundary
- [X] T010 Update `OperationOptions` Javadocs so TTL is create/upsert-only and `update()` rejects it
- [X] T011 Implement `PartialUpdateValidator` with `Locale.ROOT` reserved-name checks, literal case-distinct names, underscore rejection, and update TTL rejection
- [X] T012 Add `PARTIAL_UPDATE`, declare the release matrix in Cosmos/Dynamo,
  gate the core operation in `DefaultMulticloudDbClient`, and represent native
  limits with structured reasons. The proposed provider-specific larger-result
  capability portion was superseded and removed during review.
- [X] T013 Enforce the portable 390 KiB common limit with direct shared serialization and zero-I/O typed failure mapping
- [X] T014 Remove stale full-replacement wording from API update/delete documentation
- [X] T015 Run the focused API suite (`PartialUpdateValidatorTest`, `DefaultMulticloudDbClientPartialUpdateTest`, `DocumentSizeValidatorTest`, `MulticloudDbClientPartialUpdateContractTest`, `CapabilityTest`) successfully

## Phase 3: Focused Cosmos and Dynamo production work

### Cosmos DB

- [X] T016 Add `CosmosPartialUpdatePlannerTest` for RFC 6901 escaping, deterministic ordering, the direct plan, and the 10-field boundary
- [X] T017 Implement the package-private `CosmosPartialUpdatePlanner` with sorted literal `set` operations, one direct patch plan, and defensive field-count validation
- [X] T018 Add `CosmosPartialUpdateTest` proving one direct patch, no read/replace/batch path, direct 404 normalization, and zero-I/O field-count rejection
- [X] T019 Update `CosmosConsistencyTest` from `replaceItem` to `patchItem` and verify write response bodies are disabled explicitly
- [X] T020 Extend `CosmosErrorMappingTest` and `CosmosDiagnosticsLogTest` for update-scoped 408/410 behavior, update-only 413 normalization, and direct item diagnostics; remove unused transactional-batch helpers and tests
- [X] T021 Replace the Cosmos update data path with one direct patch and retain metadata-only write responses without an adapter retry loop

### DynamoDB

- [X] T022 Add `DynamoPartialUpdatePlannerTest` and mapper coverage for stable aliases, null/map/list values, UTF-8 measurement, and the generated expression boundary
- [X] T023 Implement the package-private `DynamoPartialUpdatePlanner` and structured single-value mapper
- [X] T024 Add `DynamoPartialUpdateTest` proving one conditional `UpdateItem`, no read/`PutItem`, consumed-capacity diagnostics, `NOT_FOUND`, timeout normalization, zero-I/O expression rejection, and result-item-size normalization after one attempted update
- [X] T025 Replace the Dynamo update data path with one conditional aliased `UpdateItem` and add the AWS module reads required for a clean Java 17 module-path build

### Cross-provider declarations and focused validation

- [X] T026 Default omitted `PARTIAL_UPDATE` declarations to unsupported in
  `CapabilitySet`, verify every built-in provider exposes 18 effective rows,
  and prove unrelated omissions are not synthesized. Earlier optional-default
  work was superseded and removed during review.
- [X] T027 Verify compatibility with a legacy-style capability set that omits `PARTIAL_UPDATE` and confirm valid portable Spanner updates stop before provider delegation
- [X] T028 Run the initial named API/Cosmos/Dynamo focused suites with positive discovery and zero failures/errors
- [X] T029 Reconcile `spec.md`, binding `design.md`, `plan.md`, `research.md`, `data-model.md`, contracts, `quickstart.md`, requirements checklist, and `tasks.md` to the focused scope

**Checkpoint**: Shared/API and Cosmos/Dynamo partial-update implementation is
complete. Spanner is excluded through the API-default capability gate; targeted
baseline write/result fixes do not enable portable Spanner update.

## Phase 4: Shared baseline conformance

- [X] T030 Put update-TTL rejection in `CrudConformanceTests` so all three concrete providers inherit the zero-I/O `INVALID_REQUEST` assertion, and remove the unreachable duplicate from `TtlAndMetadataConformanceTest`
- [X] T031 Add capability-gated partial-update behavior assertions for Cosmos/Dynamo plus provider-neutral update-TTL, invalid/reserved-field, same-request case-distinct, and over-limit preflight assertions that also run before the Spanner gate
- [X] T032 Verify Cosmos/Dynamo run supported behavior while the API defaults Spanner to unsupported and runs the core rejection assertion
- [X] T033 Run the named Cosmos emulator/conformance tests and verify positive Surefire discovery for the earlier baseline (not the current T061 rerun)
- [X] T034 Run the Dynamo emulator/conformance profile with positive discovery, including the concrete result-item-size regression
- [X] T035 Run Spanner conformance and verify shared validation plus core capability rejection before provider delegation

## Phase 5: Documentation and migration

- [X] T036 Update user docs for Cosmos/Dynamo shallow update, explicit Spanner
  capability rejection, native envelopes, TTL rejection, the non-portable TTL
  timing boundary, and `upsert()` migration
- [X] T037 Update API, Cosmos, Dynamo, and Spanner `[Unreleased]` entries to
  describe the core Feature 002 capability and final provider boundary
  accurately. Proposed provider-specific size/TTL capability wording was
  removed during review.
- [X] T038 Update E2E to exercise partial update on Cosmos/Dynamo and skip the scenario when `PARTIAL_UPDATE` is unsupported
- [X] T039 Update E2E and root README text to document API-default unsupported behavior for Spanner

## Phase 6: Final validation

- [X] T040 Run the canonical targeted API/Cosmos/Dynamo suites with positive discovery and zero failures/errors
- [X] T041 Run the applicable clean unit and emulator/conformance suites with positive discovery
- [X] T042 Run the earlier E2E baseline against all three emulators; Cosmos/Dynamo execute partial update and Spanner skips it by capability (current post-remediation Cosmos rerun remains T061)
- [X] T043 Build Javadocs and validate Markdown, JSON, provider details, 20 API-normalized capabilities on every provider, and requirement traceability
- [X] T044 Run the pre-review `git diff --check` and scope/status audit; confirm no credentials and no touched/staged `multiclouddb-perf/`

## Phase 7: Portability-review blocker remediation

- [X] T045 Normalize only DynamoDB update result-item-size `ValidationException` failures to non-retryable `UNSUPPORTED_CAPABILITY` with `dynamodb_result_item_size_limit`, `maximumResultBytes`, native metadata, and cause preservation
- [X] T046 Add focused matching/non-matching Dynamo error-mapper tests and provider update-path coverage
- [X] T047 Add the runnable DynamoDB Local result-item overflow regression and keep it out of the shared abstract suite
- [X] T048 Move update-TTL coverage into `CrudConformanceTests`, remove the unreachable duplicate, and add shared unchanged-state/field-count and literal-name coverage without provider branches
- [X] T049 Reconcile capability notes, feature artifacts, contracts/schema, docs, and changelogs for both Dynamo envelopes and the pre-I/O versus attempted-I/O distinction
- [X] T050 Run focused API/Dynamo/Cosmos tests, compile conformance test sources, parse the contract JSON, run `git diff --check`, and complete the protected-path/scope audit

## Phase 8: Final Cosmos result-envelope remediation

- [X] T051 Normalize update-only Cosmos HTTP 413 from direct patch to non-retryable `UNSUPPORTED_CAPABILITY` with `cosmos_result_item_size_limit`, `maximumResultBytes`, sanitized native metadata, and direct-exception cause preservation
- [X] T052 Add focused direct-patch mapper coverage and a concrete Cosmos emulator regression that seeds below the Cosmos native ceiling, attempts a small overflowing update, and verifies unchanged stored state
- [X] T053 Reconcile the binding spec/design, plan, research, data model, contracts/schema, checklist, user docs, capability notes, and changelogs for the Cosmos state-dependent result-item envelope
- [X] T054 Run the earlier Cosmos emulator profile with positive discovery, including the concrete result-item-size regression and expected emulator limitations (not the current T061 rerun)

## Phase 9: Final portability-review blocker remediation

- [X] T055 Make exact literal field identity, including case-distinct variants in one request, part of the base `PARTIAL_UPDATE` contract for Cosmos/Dynamo instead of case-folding non-reserved names
- [X] T056 Keep Spanner partial update unadvertised and verify the API fallback with a legacy-style capability set
- [X] T057 Expand shared invalid-map/name conformance, gate supported behavior by `PARTIAL_UPDATE`, and assert Spanner core rejection
- [X] T058 Prove the portable 390 KiB pass/fail boundary with recording-provider delegation tests and shared live create/upsert/update read-back assertions; remove the Dynamo-only false-positive test
- [X] T059 Document supported-path Cosmos 408/410 and DynamoDB service/SDK timeout normalization plus direct-write result limits in compatibility docs and changelogs
- [X] T060 Reconcile all feature artifacts and user docs with the Cosmos/Dynamo release scope and API-default unsupported behavior for Spanner
- [ ] T061 Run the final canonical targeted/full validation after all remediation. DynamoDB Local and Spanner emulator validation have run; retain this task as pending until the currently unavailable Cosmos emulator can run its applicable profile. Reconfirm Spanner capability gating, shared result normalization, and mapper casing coverage; complete documentation/traceability audits; and do not characterize the Spanner emulator evidence as live production validation.
- [X] T062 Define the dual 390 KiB serialized/structural base result envelope
  and evaluate a provider-specific larger-result capability. The capability
  portion was superseded and removed during review because Cosmos-only support
  did not establish a portable contract; native-limit conformance and
  documentation remain. Portable resulting-size normalization continues in
  [#114](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/114).
- [X] T064 Add deterministic API-boundary timeout conformance for Cosmos update HTTP 408/410 and DynamoDB `RequestTimeout`/`RequestTimeoutException`, with shared retryability, operation, provider, attempt-count, and diagnostics assertions.
- [X] T065 Convert the legacy Spanner row-update emulator regression to an explicit provider-direct test, remove its Surefire exclusion, and keep portable Spanner `update()` capability-gated.
- [X] T066 Add shared 31-level replacement/document depth, 50,000-byte field-name,
  non-binary portable-value, and 390 KiB structural-footprint preflight with
  stable `INVALID_REQUEST` details, zero-I/O API/conformance boundaries, and
  aligned user-facing/normative documentation; include bounded custom-map inspection,
  null create/upsert document rejection, all case-insensitive provider-owned and
  underscore-prefixed top-level reservations. Public compile-time limits were
  superseded during review by internal enforcement coverage later extended by T072.

## Phase 10: PR 105 post-review contract remediation

- [X] T067 Update shared/API/conformance coverage to accept `foo` plus `Foo` as separate valid literal fields in the same atomic request while reserved-name matching remains case-insensitive
- [X] T068 Extend complete create/upsert preflight and coverage to reject `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`, and underscore-prefixed top-level names before provider I/O
- [X] T069 Evaluate a provider-specific TTL-preservation capability and execute
  a Dynamo create/read/update/read lifecycle showing that `ttlExpiry` is
  unchanged. The capability work was superseded and removed during review
  because DynamoDB-only behavior does not establish a portable contract; the
  lifecycle remains implementation evidence. Portable TTL-expiry normalization
  continues in [#113](https://github.com/microsoft/multiclouddb-sdk-for-java/issues/113).
- [X] T070 Reconcile every binding Feature 002 artifact with same-request case
  identity, complete-write reservations, the core capability default, the
  non-portable TTL timing record, the actual implementation record, and
  reserved-field-safe E2E/quick examples
- [X] T071 Strip adapter-injected identity, TTL, and system fields centrally in `DefaultMulticloudDbClient`, preserve requested metadata separately, and prove read-to-upsert reuse
- [X] T072 Make bounded normalized serialization authoritative for provider delegation, map POJO cycle/depth/serializer re-entry failures to stable shared errors, enforce the 128-character case-insensitive complete-write top-level namespace, restore logical casing from Spanner metadata without changing its write path, and align all binding docs

## Dependencies

```text
T001-T015
  -> T016-T029
  -> T030-T035
  -> T036-T039
  -> T040-T044
  -> T045-T050
  -> T051-T054
  -> T055-T060,T062,T064-T066
  -> T067-T072
  -> T061
```

Cosmos and Dynamo emulator work can proceed independently after shared
conformance compiles. Spanner runs shared preflight and unsupported-gate coverage plus targeted
baseline write/result contract coverage without a portable partial-update path.

## Requirement traceability

| Requirements | Tasks |
|---|---|
| FR-001–FR-014 shared contract, validation, capabilities | T004–T015, T026, T030–T032, T055, T062, T067–T069 |
| FR-015 unchanged-provider core capability rejection | T005, T008–T009, T026–T027, T032, T035, T056–T057 |
| FR-016–FR-023 Cosmos mechanics/errors/diagnostics | T016–T021, T033, T059, T064 |
| FR-024–FR-027 Dynamo mechanics/errors | T022–T025, T034 |
| FR-031 Dynamo result-item envelope | T024, T034, T045–T047 |
| FR-032 Cosmos result-item envelope | T020–T021, T033, T051–T054 |
| FR-033 explicit field-case identity | T055–T057, T060–T061, T067 |
| FR-034–FR-040 native-safe write envelope and internal limit enforcement | T058, T061, T066, T068, T072 |
| FR-034–FR-035 structural preflight and diagnostics | T066 |
| FR-041 non-portable TTL timing boundary | T026, T036–T037, T043, T069–T070 |
| FR-028 diagnostics safety | T020–T025, T043–T044 |
| FR-029 shared baseline-only conformance | T030–T035, T064 |
| FR-030 migration | T036–T039 |
| NFR-001–NFR-005 | T016–T029, T040–T044, T055–T061, T065, T072 |
| SC-001–SC-003 focused unit success | T015, T028 |
| SC-004 shared conformance | T030–T035, T041, T057–T058, T061, T064, T067–T069 |
| SC-005 final scope/diff | T027, T044, T061, T065, T072 |

## Counts

- Total tasks: **71**
- Completed: **70**
- Remaining: **1**

Earlier PR validation established provider-profile baselines. In the current
environment, DynamoDB Local and Spanner emulator validation ran, while the
Cosmos emulator is unavailable. T061 records the required Cosmos-inclusive
post-remediation rerun; exact totals must come from that run's Surefire reports
and are not retained as mutable task metadata.

## Scope rules

- Keep all three Spanner Feature 002 capabilities unsupported and prevent portable update delegation; keep its write path unchanged, centralize portable result cleanup in the default client, and limit provider code to metadata/physical-column casing restoration.
- Do not add a portable Spanner partial-update data path or advertise its capability.

- Do not add a public patch model, `replace()` method, cancellation, retry
  configuration, or native-client escape hatch.
- Do not touch or stage `multiclouddb-perf/`.
