---
description: "Tasks for implementing portable Change Feed (User Story 8 / FR-065..070)"
---

# Tasks: Portable Change Feed

**Input**: `specs/002-change-feed/{spec.md, research.md}`
**Branch**: `feature/change-feed-design`

**Tech stack**: Java 17, Maven (multi-module), Jackson `ObjectNode` for payloads,
azure-cosmos 4.78.0, software.amazon.awssdk 2.34.0, google-cloud-spanner 6.62.0,
JUnit 5 + Mockito.

## Format: `- [ ] T### [P?] [US?] Description (file path)`

- **[P]**: Parallel-safe (different files, no dependencies)
- **[US#]**: User-story label

---

## Phase 1: API foundations (multiclouddb-api)

- [x] T001 Add `MulticloudDbErrorCategory.CHECKPOINT_EXPIRED` constant
      (`multiclouddb-api/.../MulticloudDbErrorCategory.java`)
- [x] T002 [P] Add capability constants and singletons:
      `CHANGE_FEED_POINT_IN_TIME`, `CHANGE_FEED_LOGICAL_PARTITION_SCOPE`
      (`multiclouddb-api/.../Capability.java`)
- [x] T003 [P] Create `ChangeType` enum (CREATE / UPDATE / DELETE)
      (`multiclouddb-api/.../changefeed/ChangeType.java`)
- [x] T004 [P] Create `NewItemStateMode` enum
      (OMIT / INCLUDE_IF_AVAILABLE)
      (`multiclouddb-api/.../changefeed/NewItemStateMode.java`)
- [x] T005 [P] Create sealed `FeedScope` interface + `EntireCollection`,
      `PhysicalPartition`, `LogicalPartition` records
      (`multiclouddb-api/.../changefeed/FeedScope.java`)
- [x] T006 [P] Create sealed `StartPosition` interface + `Beginning`, `Now`,
      `AtTime(Instant)`, `FromContinuationToken(String)` records
      (`multiclouddb-api/.../changefeed/StartPosition.java`)
- [x] T007 [P] Create `ChangeEvent` final class
      (provider, eventId, eventType, address, key, data, commitTimestamp)
      (`multiclouddb-api/.../changefeed/ChangeEvent.java`)
- [x] T008 [P] Create `ChangeFeedPage` final class
      (events, continuationToken, partitionRetired, childPartitions,
      diagnostics)
      (`multiclouddb-api/.../changefeed/ChangeFeedPage.java`)
- [x] T009 Create `ChangeFeedRequest` + `Builder`
      (address, scope, startPosition, newItemStateMode, maxPageSize)
      (`multiclouddb-api/.../changefeed/ChangeFeedRequest.java`)
- [x] T010 Create internal `ContinuationTokenCodec` (envelope encode/decode +
      provider/resource validation) — package-private
      (`multiclouddb-api/.../changefeed/internal/ContinuationTokenCodec.java`)

## Phase 2: SPI + Default client

- [x] T011 Add SPI default methods that throw `UNSUPPORTED_CAPABILITY`:
      `readChanges(...)`, `listPhysicalPartitions(...)`
      (`multiclouddb-api/.../spi/MulticloudDbProviderClient.java`)
- [x] T012 Add `readChanges(...)` and `listPhysicalPartitions(...)` to
      `MulticloudDbClient` interface (with default-options overloads)
      (`multiclouddb-api/.../MulticloudDbClient.java`)
- [x] T013 Wire `DefaultMulticloudDbClient.readChanges` and
      `listPhysicalPartitions` (capability fail-fast for `atTime` and
      `LogicalPartition`; diagnostics enrichment)
      (`multiclouddb-api/.../internal/DefaultMulticloudDbClient.java`)

## Phase 3: Capabilities — provider matrices

- [x] T014 [P] Cosmos: declare
      `CHANGE_FEED_POINT_IN_TIME_CAP`,
      `CHANGE_FEED_LOGICAL_PARTITION_SCOPE_CAP`
      (`multiclouddb-provider-cosmos/.../CosmosCapabilities.java`)
- [x] T015 [P] Dynamo: declare
      `CHANGE_FEED_POINT_IN_TIME_UNSUPPORTED`,
      `CHANGE_FEED_LOGICAL_PARTITION_SCOPE_UNSUPPORTED`
      (`multiclouddb-provider-dynamo/.../DynamoCapabilities.java`)
- [x] T016 [P] Spanner: declare
      `CHANGE_FEED_POINT_IN_TIME_CAP`,
      `CHANGE_FEED_LOGICAL_PARTITION_SCOPE_UNSUPPORTED`
      (`multiclouddb-provider-spanner/.../SpannerCapabilities.java`)

## Phase 4: Cosmos provider [US1, US2, US3]

- [x] T017 Implement `CosmosProviderClient.listPhysicalPartitions` via
      `CosmosContainer.getFeedRanges()` (encode `FeedRange.toString()` →
      base64)
- [x] T018 Implement `CosmosProviderClient.readChanges` via
      `CosmosContainer.queryChangeFeed(CosmosChangeFeedRequestOptions, FeedRange)`:
      - validate `AllVersionsAndDeletes` mode at first call (presence of
        `_metadata` operation type)
      - map operationType to `ChangeType`
      - emit `eventId = _lsn` (or `_etag` fallback)
      - support `EntireCollection` (no FeedRange → `forFullRange()`),
        `PhysicalPartition` (decoded), `LogicalPartition`
        (`FeedRange.forLogicalPartition`)
- [x] T019 Cosmos continuation: store native CFR continuation in envelope `c`
- [x] T020 Map Cosmos `CosmosException` 410/Gone to `CHECKPOINT_EXPIRED`
      where applicable, otherwise existing mapping rules

## Phase 5: Dynamo provider [US1, US2, US3]

- [x] T021 Add `software.amazon.awssdk:dynamodb-streams` dependency
      (`multiclouddb-provider-dynamo/pom.xml` + parent
      `dependencyManagement` if needed)
- [x] T022 Lazy-build `DynamoDbStreamsClient` alongside `DynamoDbClient`
      (`DynamoProviderClient`)
- [x] T023 Implement `DynamoProviderClient.listPhysicalPartitions` via
      `DescribeTable` → stream ARN → `DescribeStream` open shards (paginated)
- [x] T024 Implement `DynamoProviderClient.readChanges` for `EntireCollection`
      and `PhysicalPartition`:
      - one or many shards → `GetShardIterator` (TRIM_HORIZON / LATEST /
        AT_SEQUENCE_NUMBER) → `GetRecords`
      - aggregate per-shard cursors in token; surface `partitionRetired` +
        `childPartitions` when shard closes
      - reject `LogicalPartition` and `StartPosition.atTime` with
        `UNSUPPORTED_CAPABILITY`
- [x] T025 Map `TrimmedDataAccessException` → `CHECKPOINT_EXPIRED`;
      `ResourceNotFoundException` on stream → `INVALID_REQUEST`
- [x] T026 Validate stream is enabled with
      `StreamViewType ∈ {NEW_IMAGE, NEW_AND_OLD_IMAGES}` when
      `newItemStateMode != OMIT`; otherwise `UNSUPPORTED_CAPABILITY`

## Phase 6: Spanner provider [US1, US2, US3]

- [x] T027 Add config keys `changeStream.<collection>` (or convention
      `<collection>_changes`) for the change-stream DDL name; document in
      `docs/configuration.md`
- [x] T028 Implement `SpannerProviderClient.listPhysicalPartitions` via TVF
      with `start_timestamp = NOW()`, `partition_token = NULL` collecting
      `child_partitions_record` tokens
- [x] T029 Implement `SpannerProviderClient.readChanges` for `EntireCollection`
      and `PhysicalPartition`:
      - issue `SELECT * FROM READ_<stream>(start, end, partition_token, hb)`
      - parse `data_change_record` (mods → CREATE/UPDATE/DELETE) and
        `child_partitions_record`
      - aggregate active partition cursors into envelope token
- [x] T030 Reject `LogicalPartition` with `UNSUPPORTED_CAPABILITY`; validate
      `value_capture_type` is `NEW_ROW` / `NEW_ROW_AND_OLD_VALUES` for
      `INCLUDE_IF_AVAILABLE` to populate payloads (returns `data=null` otherwise)

## Phase 7: Tests

- [x] T031 [P] Unit: `ContinuationTokenCodec` round-trip; cross-provider /
      cross-resource rejection
      (`multiclouddb-api/src/test/java/.../changefeed/ContinuationTokenCodecTest.java`)
- [x] T032 [P] Unit: `ChangeFeedRequest` builder validation (negative
      maxPageSize, null scope, null startPosition)
      (`multiclouddb-api/src/test/java/.../changefeed/ChangeFeedRequestTest.java`)
- [x] T033 [P] Unit: `FeedScope` and `StartPosition` sealed-type pattern
      matching
      (`multiclouddb-api/src/test/java/.../changefeed/FeedScopeTest.java`,
      `StartPositionTest.java`)
- [x] T034 [P] Unit: SPI default `UNSUPPORTED_CAPABILITY` behaviour
      (`multiclouddb-api/src/test/java/.../spi/ProviderClientDefaultsTest.java`)
- [x] T035 [P] Unit: `DefaultMulticloudDbClient` capability gating + diagnostics
      (`multiclouddb-api/src/test/java/.../internal/DefaultClientChangeFeedTest.java`)
- [x] T036 Conformance abstract `ChangeFeedConformanceTest`
      (`multiclouddb-conformance/src/test/java/.../us8/ChangeFeedConformanceTest.java`)
      covering: basic CRUD propagation, checkpoint roundtrip, point-in-time
      gating, logical-partition gating, physical-partition listing,
      cross-provider token rejection
- [x] T037 [P] Per-provider concrete test classes
      (`Cosmos|Dynamo|Spanner ChangeFeedTest.java`)

## Phase 8: Docs + spec update

- [x] T038 Update `docs/configuration.md` with provisioning prerequisites
      (Cosmos `AllVersionsAndDeletes`, Dynamo stream mode, Spanner change
      stream DDL + `value_capture_type`)
- [x] T039 [P] Per-module `CHANGELOG.md` entries (api, cosmos, dynamo, spanner)
- [x] T040 Tick US8 acceptance checklist in `specs/001-clouddb-sdk/spec.md`
      (lines 641-646); add note distinguishing Logical vs Physical
      partition scope under FR-069
