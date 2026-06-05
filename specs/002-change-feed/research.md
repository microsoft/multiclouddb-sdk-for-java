# Change Feed (User Story 8) — Design Plan

## Problem

Spec FR-065..070 / US8 describes a portable change-feed abstraction. Today only the
`Capability.CHANGE_FEED` flag exists; there is no API/SPI surface, no provider impl,
and no tests.

## Design philosophy: majority-rule, not strict LCD

A pure lowest-common-denominator (intersection of all three providers) leaves a
useful feature anaemic: it would force us to drop delete events, drop create/update
distinction, and drop time-based start — three features that 2 of 3 providers
support natively.

The rule used in this design is:

- **3/3 supported** → first-class API surface, no warning.
- **2/3 supported** → first-class API surface; minority provider either (a) works
  with a documented provisioning prerequisite, or (b) raises
  `UNSUPPORTED_CAPABILITY` with an actionable error. **Portability warning
  documented on every public API element where the gap is observable.**
- **1/3 supported** → kept as an explicit capability-gated opt-in; not part of
  the first-class API path.
- **0/3 supported** → out of scope.

This keeps the portable surface ergonomic for the common case while staying honest
about provider gaps.

## Provider native surfaces

| Aspect | Cosmos (azure-cosmos 4.78.0) | Dynamo Streams (sdk v2 2.34.0) | Spanner Change Streams (6.62.0) | Coverage |
|---|---|---|---|---|
| Pull API | `queryChangeFeed(CosmosChangeFeedRequestOptions, FeedRange)` | `GetShardIterator` + `GetRecords` | `SELECT * FROM READ_<stream>(...)` | 3/3 |
| Start: beginning | `createForProcessingFromBeginning()` | `TRIM_HORIZON` (24h) | `start_timestamp = epoch` | 3/3 |
| Start: checkpoint | `createForProcessingFromContinuation(String)` | `AFTER_SEQUENCE_NUMBER` | `partition_token` + watermark | 3/3 |
| Start: point-in-time | ✅ `createForProcessingFromPointInTime(Instant)` | ❌ no timestamp iterator | ✅ `start_timestamp` | **2/3** |
| Distinct CREATE vs UPDATE | needs `AllVersionsAndDeletes` provisioning | ✅ native (`INSERT` vs `MODIFY`) | ✅ native (`mod_type`) | **2/3** |
| Delete events | needs `AllVersionsAndDeletes` provisioning | ✅ native (`REMOVE`) | ✅ native | **2/3** |
| Full new-item image | ✅ default in `LatestVersion` | needs `NEW_IMAGE` / `NEW_AND_OLD_IMAGES` provisioning | needs `NEW_ROW` / `NEW_ROW_AND_OLD_VALUES` provisioning | 3/3 with per-provider provisioning |
| Logical partition-key scope (filter to a single PK value) | ✅ `FeedRange.forLogicalPartition(pk)` | ❌ shards are physical | ❌ partition tokens are physical row ranges | **1/3** |
| Physical partition scope (one shard / range / token) | ✅ `FeedRange` from `getFeedRanges()` / `forFeedRange(...)` | ✅ shard iterator from `DescribeStream` | ✅ `partition_token` from `child_partitions_record` | **3/3** |
| Physical partition lifecycle (split/merge signal) | `splitFeedRange()` / new ranges from `getFeedRanges()` | parent shard closes, child shard ids in `DescribeStream` | `child_partitions_record` event in feed | **3/3** |
| Event timestamp | UTC | `ApproximateCreationDateTime` (UTC) | `commit_timestamp` (UTC) | 3/3 |
| Retention | container-driven | 24 h hard cap | configurable | n/a |
| Push alternative (out of scope v1) | Change Feed Processor + lease container | KCL | Dataflow connector | — |

## Programming model: synchronous pull, with a polling iterator helper

Two layers:

1. **SPI primitive** — `readChanges(request)` returns one page + opaque token.
2. **API helper** — `ChangeFeedIterator` wraps the primitive in a polling loop
   with idle backoff, cancellation, and checkpoint hand-off. Internally it
   only ever calls the SPI primitive — it adds no provider-specific code.

**Why pull as the SPI:**
- Pull is the LCD: every provider exposes it natively and lightly.
- Push semantics differ wildly — Cosmos needs a lease container, Dynamo needs KCL +
  DynamoDB lease tables, Spanner steers users to Dataflow. We would have to ship
  three bespoke runtimes to make a portable push API. Defer.
- Application owns checkpoint persistence (matches CRUD/query model already used
  by `QueryPage.continuationToken`).

**Why add the iterator helper:**
- Spec US8 independent test calls for "subscribe to changes" UX. A bare one-shot
  pull primitive forces every user to reinvent polling/backoff/idle handling and
  often leads to busy loops. The helper closes that gap without adopting a managed
  push runtime.

## Public API (added to `MulticloudDbClient` and SPI)

```java
ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options);
```

### Types (in `multiclouddb-api`)

```java
public final class ChangeFeedRequest {
    ResourceAddress address;                // required
    StartPosition startPosition;            // required
    FeedScope scope;                        // default EntireCollection()
    NewItemStateMode newItemStateMode;      // default INCLUDE_IF_AVAILABLE
    int maxPageSize;                        // default 0 = no cap; provider clamps when set
}

// Unified partitioning model.
public sealed interface FeedScope {
    record EntireCollection() implements FeedScope {}                     // 3/3 default
    record PhysicalPartition(String partitionId) implements FeedScope {}  // 3/3 first-class
    record LogicalPartition(MulticloudDbKey key) implements FeedScope {}  // 1/3 — Cosmos only
}

public enum NewItemStateMode {
    OMIT,                  // never include
    INCLUDE_IF_AVAILABLE   // include when provider/config provides it; null otherwise
}

public sealed interface StartPosition {
    record Beginning() implements StartPosition {}                    // see retention note
    record Now() implements StartPosition {}                          // 3/3
    record AtTime(Instant utc) implements StartPosition {}            // 2/3 — Dynamo throws
    record FromContinuationToken(String token) implements StartPosition {}
}

public enum ChangeType { CREATE, UPDATE, DELETE }

public final class ChangeEvent {
    String eventId;                          // provider sequence/LSN, as string; for dedup
    MulticloudDbKey key;
    ChangeType type;                         // always CREATE/UPDATE/DELETE — see Cosmos note
    Instant timestamp;                       // UTC, RFC3339 on toString
    Map<String, Object> newItemState;        // null on DELETE or when OMIT/unavailable
}

public final class ChangeFeedPage {
    List<ChangeEvent> events;                // possibly empty
    String continuationToken;                // never null; opaque; resume here
    boolean idle;                            // true when provider returned an empty batch
    // Physical-partition lifecycle. Populated only when scope is PhysicalPartition.
    boolean partitionRetired;                // true when this partition has been split/merged
    List<String> childPartitions;            // partition IDs to consume next; non-empty iff partitionRetired
    OperationDiagnostics diagnostics;        // optional: lag estimate, RU/RCU charge, watermark
}
```

### Partition discovery (new SPI/API call)

```java
// 3/3 — returns currently active physical partition IDs for the resource.
// Used as the seed for parallel consumption; subsequent splits are surfaced
// via ChangeFeedPage.partitionRetired + childPartitions.
List<String> listPhysicalPartitions(ResourceAddress address, OperationOptions options);
```

`partitionId` is an opaque string. Internally each provider encodes its native
identifier (Cosmos: serialized `FeedRange`; Dynamo: `shardId`; Spanner:
`partition_token`). The string is meaningful only to the same provider+resource
that produced it.

### Capabilities

| Capability | Tier | Gates |
|---|---|---|
| `CHANGE_FEED` (existing) | 3/3 first-class | basic feed: CREATE/UPDATE/DELETE events, beginning + checkpoint start, full image when provisioned, `EntireCollection` and `PhysicalPartition` scopes |
| `CHANGE_FEED_POINT_IN_TIME` | 2/3 first-class | `StartPosition.AtTimestamp` |
| `CHANGE_FEED_LOGICAL_PARTITION_SCOPE` | 1/3 minority opt-in | `FeedScope.LogicalPartition(...)` |

`CREATE`/`UPDATE`/`DELETE` event labelling, delete events, full-image emission,
and physical-partition scope are **not separate capabilities** — they are part
of the basic `CHANGE_FEED` contract. Providers satisfy that contract via the
provisioning prerequisites below. A provider that cannot satisfy the contract
(e.g. Cosmos in `LatestVersion` mode, which conflates create vs update) fails
fast at first `readChanges()` with `INVALID_REQUEST` and an actionable message
pointing at the missing provisioning step.

Capability matrix:

| Capability | Cosmos | Dynamo | Spanner |
|---|---|---|---|
| `CHANGE_FEED` | ✅ (requires `AllVersionsAndDeletes` mode) | ✅ (requires `NEW_AND_OLD_IMAGES` for full image) | ✅ (requires `NEW_ROW_AND_OLD_VALUES` for full image) |
| `CHANGE_FEED_POINT_IN_TIME` | ✅ | ❌ — `UNSUPPORTED_CAPABILITY` | ✅ |
| `CHANGE_FEED_LOGICAL_PARTITION_SCOPE` | ✅ | ❌ — `UNSUPPORTED_CAPABILITY` | ❌ — `UNSUPPORTED_CAPABILITY` |

## Portability warnings (must appear in javadoc + docs/configuration.md)

These are the gaps a portable application will observe; surfacing them as docs
warnings — not as quiet capability flags — is the cost of the majority-rule
shortcut.

1. **Cosmos: change-feed mode must be `AllVersionsAndDeletes`.**
   Cosmos's default `LatestVersion` mode emits only post-images and cannot
   distinguish create from update or surface deletes. The SDK validates the
   container's change-feed mode at first `readChanges()` and fails with
   `INVALID_REQUEST` ("Cosmos change feed must be configured with
   `AllVersionsAndDeletes`") if mis-provisioned. This is a one-time
   provisioning step.

2. **Dynamo: point-in-time start is unsupported.**
   `StartPosition.AtTimestamp` raises `UNSUPPORTED_CAPABILITY` on Dynamo.
   Dynamo Streams only expose `TRIM_HORIZON` (24-hour window) and `LATEST`.
   Portable applications should either gate via
   `capabilities.isSupported(CHANGE_FEED_POINT_IN_TIME)` or fall back to a
   stored checkpoint.

3. **Dynamo: 24-hour record retention.**
   `BeginningOfAvailableChanges` on Dynamo means "24 hours ago," not
   "table creation." A persisted checkpoint older than ~24 h fails with
   `CHECKPOINT_EXPIRED`.

4. **Dynamo full image: requires `StreamSpecification.StreamViewType =
   NEW_IMAGE` (or `NEW_AND_OLD_IMAGES`)** at table provisioning. With
   `KEYS_ONLY`, `INCLUDE_IF_AVAILABLE` returns `data=null`.

5. **Spanner full image: requires `value_capture_type = 'NEW_ROW'` (or
   `NEW_ROW_AND_OLD_VALUES`)** on the change stream definition. Other
   modes emit only changed columns; `INCLUDE_IF_AVAILABLE` returns
   `data=null`.

6. **Logical partition-key scope is Cosmos-only.**
   Dynamo and Spanner partition feeds at the physical shard / row-range
   level, with no relationship to logical partition keys. Setting
   `FeedScope.LogicalPartition(...)` on those providers raises
   `UNSUPPORTED_CAPABILITY`. Portable code that needs to filter to a single
   PK must gate on `CHANGE_FEED_LOGICAL_PARTITION_SCOPE` — or use
   `FeedScope.PhysicalPartition` and filter client-side, which works on all
   three providers but reads the full physical range.

7. **Physical-partition IDs are not portable across providers.**
   The opaque `partitionId` strings returned by `listPhysicalPartitions()`
   encode provider-native identifiers (Cosmos `FeedRange`, Dynamo `shardId`,
   Spanner `partition_token`). A `partitionId` is meaningful only against
   the same provider+resource that produced it. Continuation tokens scoped
   to a `PhysicalPartition` carry the same constraint.

When an unsupported capability is requested, the SPI raises a
`MulticloudDbException` of category `UNSUPPORTED_CAPABILITY` with an actionable message
(matching how other capability gates already work). No silent degradation.

## Continuation token strategy

Token is opaque to the application; SDK chooses the encoding. Envelope is
base64 of a versioned JSON document with the same shape across providers:

```
{
  "v": 1,                       // schema version
  "p": "cosmos|dynamo|spanner", // provider id
  "r": "<resource fingerprint>",// e.g. Dynamo stream ARN, Spanner change stream
                                // FQN, Cosmos container RID/feed mode
  "c": <provider cursor>        // Cosmos: native string; Dynamo: array of
                                // {shardId, sequenceNumber, parent?, closed?};
                                // Spanner: array of {partitionToken,
                                // watermarkUtc, child?, retired?}
}
```

`readChanges()` validates `v` (known versions), `p` (matches current provider),
and `r` (matches addressed resource fingerprint). Mismatches fail with
`INVALID_REQUEST`. Future provider-cursor evolutions bump `v`.

**Token bloat reality:** both Dynamo and Spanner accumulate per-shard /
per-partition cursors after splits, not just Spanner. Document as: tokens are
opaque blobs, possibly several KB after long-lived streams cross many splits;
persist as `TEXT` / blob, not `varchar(255)` / HTTP header.

## Ordering contract

Documented as: **events within a single page are ordered by the provider's
native commit/ingest order within the cursor's scope. No global ordering
across the collection is guaranteed.**

Cursor scope by `FeedScope`:
- `EntireCollection`: SDK fans out across all physical partitions internally;
  per-partition order preserved, no cross-partition ordering. Token hides
  fan-out; partition splits handled internally and may grow the token.
- `PhysicalPartition`: ordered within that one provider partition; on split
  the page returns `partitionRetired=true` + `childPartitions` and the caller
  fans out.
- `LogicalPartition` (Cosmos only): ordered within that single PK.

**Spec impact:** FR-069's wording ("MUST support partition-scoped consumption")
is honoured — `PhysicalPartition` scope satisfies it on all 3 providers. The
phrase "specific partition key" in acceptance scenario 3 is now mapped to
either `LogicalPartition` (where supported) or `PhysicalPartition` (everywhere
else). The spec needs a clarifying note distinguishing the two scopes;
unconditional MUST is no longer in conflict with the design.

## Delivery semantics

**At-least-once.** Providers may redeliver events around checkpoint boundaries,
shard/partition transitions, and retries. Each `ChangeEvent.eventId` is a
stable, provider-supplied identifier (Cosmos: `_lsn`/`_etag`; Dynamo:
`SequenceNumber`; Spanner: `commit_timestamp + record_sequence`) that
applications can use for downstream deduplication.

## Stream lifecycle / expired checkpoints

Add a new error category `CHECKPOINT_EXPIRED` to `MulticloudDbErrorCategory`
(matches existing `expandable string enum` pattern in
`MulticloudDbErrorCategory.java:46`). Surfaced when a provider rejects a
checkpoint as trimmed/expired (Dynamo 24-h retention, Cosmos retention policy,
Spanner change-stream retention window).

If a stream's underlying resource is recreated under the same logical name,
the resource fingerprint in the token will mismatch → `INVALID_REQUEST`.

## Provisioning prerequisites (documentation only)

These are folded into the **Portability warnings** section above. Summary
table (the SDK validates these at first `readChanges()`):

| Provider | Required provisioning | If missing |
|---|---|---|
| Cosmos | change feed mode = `AllVersionsAndDeletes` | `INVALID_REQUEST` at first call |
| Dynamo | `StreamSpecification.StreamViewType` ∈ {`NEW_IMAGE`, `NEW_AND_OLD_IMAGES`} (needed for `INCLUDE_IF_AVAILABLE` to populate payloads) | `data=null` if not configured |
| Spanner | `value_capture_type` ∈ {`NEW_ROW`, `NEW_ROW_AND_OLD_VALUES`} (needed for `INCLUDE_IF_AVAILABLE` to populate payloads); change stream DDL must exist | `data=null` / `NOT_FOUND` |

## Out of scope (v1)

- Push / managed processor model (lease container, KCL, Dataflow). Tracked as
  follow-up.
- Replaying a stream from a sequence number/checkpoint that has been trimmed
  — surface as `MulticloudDbErrorCategory.PROVIDER_ERROR` with a message
  matching FR-066's edge case in spec.
- Bulk replay / backfill (separate concern).
- Old-image / pre-update state (asymmetric across providers).

## Conformance test plan

- `multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/us8/ChangeFeedConformanceTest.java`
  - basic create/update propagation
  - checkpoint roundtrip resumes from after the last event
  - capability-gated tests skip when capability not supported (matches `us2`
    pattern for capability tests)
  - point-in-time start (Cosmos + Spanner only — Dynamo skipped via capability)
  - delete-event gating (Cosmos default mode → expect UNSUPPORTED)

## Tasks (rough)

1. API types: `ChangeEvent`, `ChangeFeedRequest`, `ChangeFeedPage`,
   `StartPosition`, `FeedScope`, `ChangeType`, `NewItemStateMode` in
   `multiclouddb-api`.
2. New capability constants in `Capability.java`:
   `CHANGE_FEED_POINT_IN_TIME`, `CHANGE_FEED_LOGICAL_PARTITION_SCOPE`.
3. SPI: add `readChanges(...)` and `listPhysicalPartitions(...)` to
   `MulticloudDbProviderClient`; default throws `UNSUPPORTED_CAPABILITY`.
4. Default client wiring in `DefaultMulticloudDbClient`.
5. Cosmos provider: implement `readChanges` via `queryChangeFeed` + `FeedRange`;
   `listPhysicalPartitions` via `getFeedRanges()`. Validate
   `AllVersionsAndDeletes` mode at first call.
6. Dynamo provider: implement `readChanges` via `GetShardIterator` +
   `GetRecords`; `listPhysicalPartitions` via `DescribeStream().shards()`.
   Token encodes per-shard sequence + closed/parent/child shard state.
   Surface `partitionRetired` when a shard closes.
7. Spanner provider: implement `readChanges` via TVF read;
   `listPhysicalPartitions` returns the initial root partition tokens (and
   `EntireCollection` mode discovers via `child_partitions_record` internally).
   Surface `partitionRetired` + `childPartitions` when consuming a single
   `PhysicalPartition` and a `child_partitions_record` arrives.
8. Conformance tests under `us8/`:
   - basic CRUD propagation (`EntireCollection`)
   - checkpoint roundtrip
   - point-in-time start (gated; Dynamo skipped)
   - logical-partition scope (gated; Dynamo + Spanner skipped)
   - physical-partition scope + lifecycle (3/3)
9. Docs: `docs/configuration.md` (provisioning prerequisites per provider,
   portability warnings), per-module CHANGELOG entries.
10. Spec: tick the now-implemented checkboxes in `spec.md` US8 checklist;
    add a clarifying note distinguishing `LogicalPartition` from
    `PhysicalPartition` scope under FR-069 (no MUST amendment needed —
    `PhysicalPartition` satisfies the requirement on all three providers);
    record FR-067/068 implementation status notes inline (matches the
    convention used for FR-077).
