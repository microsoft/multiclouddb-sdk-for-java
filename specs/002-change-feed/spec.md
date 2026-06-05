# Feature Specification: Portable Change Feed (User Story 8)

**Feature Branch**: `feature/change-feed-design`
**Status**: Implementing
**Input**: Implements User Story 8 / FR-065..070 of `specs/001-clouddb-sdk/spec.md`

> **⚠️ Design Change**: `FeedScope` (including `PhysicalPartition`, `LogicalPartition`,
> and `EntireCollection`), `listPhysicalPartitions`,
> `partitionRetired`, and `childPartitions` have been **removed** from the public
> API. The physical-partition abstraction was not truly portable — Cosmos uses
> stable feed ranges, DynamoDB uses ephemeral shards, and Spanner uses ephemeral
> partition tokens. The SDK now handles partition fan-out internally and always
> reads the entire collection. References to these concepts in the spec below
> reflect the original design and are retained for historical context.

## User Scenarios & Testing

### User Story 1 — Read changes from the start of a collection (Priority: P1)

A developer wants to consume every CRUD change committed to a collection
starting at a known point (beginning, current time, or a specific timestamp),
without writing provider-specific code.

**Why this priority**: This is the core MVP — without it the feature provides
no value. Sits on top of existing CRUD/query primitives.

**Independent Test**: Configure a client against any provider, call
`client.readChanges(ChangeFeedRequest.fromBeginning(address))`,
write a few documents, and observe `ChangeEvent`s arrive with provider-stable
`eventId`s and the right `ChangeType`.

**Acceptance Scenarios**:

1. **Given** an empty collection, **When** I write 3 documents and call
   `readChanges` with `StartPosition.beginning()`, **Then** I receive 3
   events with `ChangeType.CREATE` (or `UPDATE` on Cosmos default mode — see
   warnings) and a `continuationToken` for resumption.
2. **Given** a previously-saved `continuationToken`, **When** I call
   `readChanges` with `StartPosition.fromContinuationToken(token)`, **Then**
   I receive only events committed after the checkpoint.
3. **Given** I pass a token issued for a different provider, **When** I call
   `readChanges`, **Then** the call fails with
   `MulticloudDbErrorCategory.INVALID_REQUEST`.

---

### User Story 2 — Resume after a process restart (Priority: P1)

A developer's worker process crashes; on restart, they need to resume from the
last checkpoint without losing or double-processing events.

**Why this priority**: Real consumers all need durable resumption. Without it,
the feature is not production-usable.

**Independent Test**: Persist `page.continuationToken()` after each page;
restart the process; supply the token back via
`StartPosition.fromContinuationToken(...)`. New events appear with no gap.

**Acceptance Scenarios**:

1. **Given** I save a token at event N, **When** I restart and resume from
   that token, **Then** I receive event N+1 onwards (at-least-once delivery —
   redelivery of N is permitted; consumers dedupe via `eventId`).
2. **Given** a token whose underlying provider cursor has been trimmed
   (e.g., Dynamo 24-hour retention exceeded), **When** I resume, **Then** the
   call fails with `MulticloudDbErrorCategory.CHECKPOINT_EXPIRED` and an
   actionable message.

---

### User Story 3 — Filter the feed to a single partition (Priority: P2)

A developer wants to scale-out by sharding consumption across multiple
workers, each reading only its slice of the collection.

**Why this priority**: P2 — required for horizontal scaling but not blocking
single-worker MVP.

**Independent Test**: Call `listPhysicalPartitions(address)` (3/3 supported),
fan out one worker per partitionId, each calling `readChanges` with
`FeedScope.physicalPartition(id)`. Combined output equals the full feed.

**Acceptance Scenarios**:

1. **Given** N physical partitions reported by `listPhysicalPartitions`,
   **When** I read each one in parallel, **Then** the union of events equals
   the full feed (modulo at-least-once duplicates).
2. **Given** a Cosmos provider, **When** I call `readChanges` with
   `FeedScope.logicalPartition(MulticloudDbKey.of("user-42"))`, **Then** I
   receive only events whose document partition key matches `user-42`.
3. **Given** Dynamo or Spanner, **When** I call with `FeedScope.logicalPartition`,
   **Then** the call fails with `UNSUPPORTED_CAPABILITY`. Gating on
   `Capability.CHANGE_FEED_LOGICAL_PARTITION_SCOPE` lets portable code skip.
4. **Given** a worker reading a single `PhysicalPartition` that splits or is
   retired, **When** the next page arrives, **Then**
   `page.partitionRetired() == true` and `page.childPartitions()` lists the
   replacement partition IDs the caller should fan out to.

---

### User Story 4 — Capability discovery and portable warnings (Priority: P2)

A developer porting an app between providers needs to know which capabilities
behave differently and where they will fail.

**Why this priority**: P2 — addresses the SDK's portability promise and is a
foundational, repeated request from internal customers.

**Independent Test**: Read `client.capabilities()` and assert the change-feed
capability set matches the documented matrix per provider.

**Acceptance Scenarios**:

1. **Given** any provider, **When** I check `caps.isSupported(Capability.CHANGE_FEED)`,
   **Then** it returns `true` only when the provider is correctly provisioned
   (or unconditionally true with the requirement documented in
   `docs/configuration.md`).
2. **Given** Dynamo, **When** I check
   `caps.isSupported(Capability.CHANGE_FEED_POINT_IN_TIME)`, **Then** it
   returns `false` (Dynamo does not support a timestamp-based start position).

---

### Edge Cases

- **Idle feed**: `readChanges` returns an empty page with a non-null
  continuation token; consumers can poll without spinning.
- **Cosmos provisioning miss**: a Cosmos container without
  `AllVersionsAndDeletes` mode causes `readChanges` to fail at the first
  call with `INVALID_REQUEST` (consumes are not silently degraded to a
  conflated CREATE/UPDATE-only feed).
- **Dynamo TRIM_HORIZON missed**: a stale checkpoint past 24-hour retention
  yields `CHECKPOINT_EXPIRED`; the consumer must restart from
  `StartPosition.beginning()` or `StartPosition.now()`.
- **Spanner partition-token churn**: when a partition retires, the page sets
  `partitionRetired=true`; consumers in `EntireCollection` mode never see
  this — the SDK fans out internally.
- **Cross-provider token reuse**: tokens are scoped by provider+resource
  fingerprint and fail with `INVALID_REQUEST` when used elsewhere.

## Requirements

### Functional Requirements

- **FR-CF-01**: The SDK MUST expose `readChanges(ChangeFeedRequest, OperationOptions)`
  on `MulticloudDbClient` returning a `ChangeFeedPage` with events,
  continuation token, and diagnostics.
- **FR-CF-02**: The SDK MUST expose `listPhysicalPartitions(ResourceAddress, OperationOptions)`
  returning opaque, provider-scoped partition IDs usable with
  `FeedScope.physicalPartition(id)`.
- **FR-CF-03**: `ChangeEvent` MUST carry `eventId`, `eventType` (CREATE / UPDATE /
  DELETE), `key`, optional `data` (full new image when available), and
  `commitTimestamp` when the provider exposes one.
- **FR-CF-04**: `StartPosition` MUST support `beginning()`, `now()`,
  `atTime(Instant)`, and `fromContinuationToken(String)`. Providers without
  point-in-time start MUST throw `UNSUPPORTED_CAPABILITY` for `atTime`.
- **FR-CF-05**: `FeedScope` MUST support `entireCollection()` (default),
  `physicalPartition(String)` (3/3), and `logicalPartition(MulticloudDbKey)`
  (gated by `CHANGE_FEED_LOGICAL_PARTITION_SCOPE`).
- **FR-CF-06**: Continuation tokens MUST be opaque base64-JSON envelopes
  carrying `{schemaVersion, providerId, resourceFingerprint, providerCursor}`.
  Cross-provider or cross-resource use MUST fail with `INVALID_REQUEST`.
- **FR-CF-07**: Delivery semantics MUST be at-least-once. Each `eventId` MUST
  be stable so consumers can dedupe.
- **FR-CF-08**: A new error category `CHECKPOINT_EXPIRED` MUST be added to
  `MulticloudDbErrorCategory` for trimmed-cursor errors.
- **FR-CF-09**: Capability constants `CHANGE_FEED_POINT_IN_TIME` and
  `CHANGE_FEED_LOGICAL_PARTITION_SCOPE` MUST be added to `Capability`.
- **FR-CF-10**: `ChangeFeedPage` MUST surface `partitionRetired: boolean` and
  `childPartitions: List<String>` when consuming a single `PhysicalPartition`
  that splits or is retired.

### Key Entities

- **`ChangeFeedRequest`** — address, scope, startPosition, newItemStateMode,
  maxPageSize.
- **`FeedScope`** — sealed: `EntireCollection`, `PhysicalPartition(id)`,
  `LogicalPartition(key)`.
- **`StartPosition`** — sealed: `Beginning`, `Now`, `AtTime(instant)`,
  `FromContinuationToken(token)`.
- **`ChangeEvent`** — provider, eventId, eventType, address, key, data,
  commitTimestamp.
- **`ChangeFeedPage`** — events, continuationToken, partitionRetired,
  childPartitions, diagnostics.
- **`ChangeType`** — CREATE / UPDATE / DELETE.
- **`NewItemStateMode`** — OMIT / INCLUDE_IF_AVAILABLE.

## Success Criteria

### Measurable Outcomes

- **SC-001**: A consumer using `readChanges` against an empty collection
  receives every subsequent committed change at least once, in
  per-partition commit order, with stable `eventId`s.
- **SC-002**: A consumer can stop, persist its `continuationToken`, restart
  N seconds later, and resume from the next event with no gap (assuming the
  cursor has not been trimmed).
- **SC-003**: A portable consumer that gates `CHANGE_FEED_*` capabilities can
  run unchanged against all three providers (Cosmos / Dynamo / Spanner) when
  the providers are correctly provisioned.
- **SC-004**: All US8 acceptance checklist items in
  `specs/001-clouddb-sdk/spec.md` (lines 641–646) are checked off.
- **SC-005**: Conformance tests under `multiclouddb-conformance/.../us8/` pass
  against all three providers (skipping where capability-gated).

## Out of Scope (v1)

- Push / managed-processor model (lease container, KCL, Dataflow).
- Bulk replay / backfill across long retention windows.
- Old-image / pre-update state delivery.
- Async / reactive variants of `readChanges`.

## References

- `specs/002-change-feed/research.md` — provider surfaces, design rationale,
  portability warnings, token format.
- `specs/001-clouddb-sdk/spec.md` lines 189–204, 408–415, 492, 639–646 —
  US8 + FR-065..070.
