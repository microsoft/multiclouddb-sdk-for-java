# Multicloud DB SDK — fair cross-provider performance test plan

## Goal

Measure the portable SDK under **fair, repeatable** conditions across Cosmos DB,
DynamoDB, and Spanner:

1. Same **offered load**.
2. Same **workload profile**.
3. Same **client placement / comparison region**.
4. Deterministic, reported **capacity / billing mode**.
5. Separate comparison of **latency**, **throughput**, and **provider-native cost**.

## Required fairness method

- Use `--target-ops-per-sec` whenever the objective is a matched-load comparison.
  The harness paces **actual operation starts** across worker threads and records
  both target and achieved/offered results.
- Use `--threads` sweeps without `--target-ops-per-sec` only for max-throughput / saturation studies.
- Use `--workload read|write|mixed|query` for one profile, or `--workload all` to run the
  read, write, and query profiles in one batch and generate one report.
- Check `comparison_region` in the environment table. `--region-policy fail` should be used for
  final fairness-sensitive runs.
- Use opt-in provisioning flags first when capacity must be pinned:
  - Cosmos: `--cosmos-ru`
  - Dynamo: `--dynamo-rcu` + `--dynamo-wcu`
- Never compare Cosmos RU numerically against Dynamo RCU/WCU; compare each provider’s cost only in
  its own unit system.

## Workload profiles

- `mixed` — existing lifecycle-style point workload (create/read/update/delete) for backward compatibility.
- `read` — seeded point reads; seeding occurs outside the measured interval.
- `write` — point writes only (create/update/upsert/delete) with independent seeded keysets and cleanup.
  Point workloads run the `S1/S2/S3` scenarios, an item-size ladder over one profile in which
  only document size varies:
  - `S1` — baseline document size.
  - `S2` — 8x the baseline. This is also `S6`'s item size, so a point cost and a query cost can
    be read at the same document size.
  - `S3` — 64x the baseline: the large documents customers store, rather than the small items
    synthetic benchmarks favour.

  Because these multiply `--doc-size`, the harness rejects a baseline whose effective size would
  exceed DynamoDB's 400 KB item limit, and fails before any live write rather than part-way
  through a comparison.
- `query` — query-only scenarios (`S4/S5/S6`), each varying exactly one dimension:
  - `S4` — partition scope: the same query with the partition key supplied (single-partition)
    and withheld (cross-partition fan-out), at the baseline document and page size.
  - `S5` — page size: cross-partition at a quarter of the baseline page size, isolating
    per-request overhead from per-item cost.
  - `S6` — item size: cross-partition over documents 8x the baseline size, with the page
    shrunk 8x so bytes per page stay near the baseline. Only item size varies, and the
    scenario does not consume several times the provisioned read capacity.

  `--doc-size` and `--page-size` set the **baseline**; each scenario derives its effective
  sizes from it, and the report records the effective values per row.
- `changefeed` — capability-gated `S7` reporting path.

## Deterministic provisioning and metadata

The harness probes and reports:

- Cosmos actual manual/autoscale throughput when visible.
- Dynamo actual billing mode plus provisioned RCU/WCU, and on-demand max read/write throughput when the AWS SDK exposes it.
- Comparison-region label (`multiclouddb.comparisonRegion` when configured, else normalized probed/config region).

After any opt-in provisioning update, metadata is re-probed before measurements continue.

Capacity and offered load are normally declared in each provider's live property file:

- `multiclouddb.perf.targetOpsPerSec` (identical across all compared providers)
- `multiclouddb.perf.cosmosRu`
- `multiclouddb.perf.dynamoRcu` and `multiclouddb.perf.dynamoWcu` (required together)

Equivalent CLI options override the property values for one run. The harness applies capacity
before cache priming and warmup, then records the probed capacity rather than trusting configured
display text.

### Transport profile

The transport protocol is **deliberately not held constant** across providers. Cosmos DB is
optimized for HTTP/2 and Gateway V2 (thin client) requires it, while DynamoDB's synchronous
client is HTTP/1.1-only. Forcing a shared protocol would run Cosmos on a path the service no
longer optimizes for, measuring a configuration nobody deploys. Each provider therefore runs its
own recommended data path:

- Cosmos Gateway over HTTP/2 with automatic Gateway V2 probing/fallback: HTTP/2 pool
  64 / min 8 / 32 streams, `contentResponseOnWriteEnabled=false`
- Dynamo synchronous Apache client: `maxConnections=64`

This answers *how the two services perform as they would actually be deployed*. It does not
isolate how much of any gap is protocol versus service, so report transport as part of the
result rather than correcting for it. Every other axis in this section — offered load, capacity,
payload, client host, region labels — stays fixed; transport is the one axis intentionally free.

**Diagnostic profile — Gateway V2 disabled.** Not the baseline. Use it only to isolate Gateway
V2 routing from the rest of the transport by setting `thinClientEnabled=false`. Gateway mode and
HTTP/2 remain fixed. Set `thinClientEnabled=true` only when a run must force Gateway V2 and the
account/region path has already been verified.

Reports record the configured `transport_profile`, and aggregation refuses to mix profiles for the
same provider, so a diagnostic run needs its own `--title` and can never be silently combined
with the default profile.

Cosmos returns the stored document on every write by default while DynamoDB's `PutItem` returns
no item, so `contentResponseOnWriteEnabled=false` removes a payload asymmetry the portable API
never exposes to callers.

### Network-distance fairness

A single client host cannot be colocated with two clouds simultaneously, so raw latency carries
the client-to-endpoint round trip for each provider. The harness probes each provider endpoint's
TCP handshake time once per run (`endpoint_rtt_ms`) and reports **service time**
(`latency − RTT`) alongside raw latency. Raw latency answers "what does this client see"; service
time answers "how fast is the service itself" and is the comparable metric from a non-colocated
client. Both are reported; neither replaces the other.

## Recorded raw data

See [`templates/RESULT_SCHEMA.md`](templates/RESULT_SCHEMA.md). Raw rows include timing offsets,
consumed units, retries (when exposed), target offered load, applicable capacity dimension/limit,
and environment metadata needed to derive:

- offered ops/s
- achieved ops/s
- achieved/offered ratio
- provider-native consumed units/sec
- capacity utilization percentage when a numeric applicable limit is known
- throttled count/rate
- retry totals
- validity

## Validity rule

A result row is **invalid** when either of these holds:

1. **Throttled** — throttled operations exceed **0.1%**. The reporting CLI can override this
   threshold with `--invalid-throttle-rate-pct`.
2. **Under target** — the row sustained less than **95%** of its target throughput. Rows that
   hold their offered load land within a fraction of a percent of target, so nothing healthy
   sits near this bar. Max-throughput sweeps set no target and are never judged by this rule.

Rule 2 exists because **throttled counts are not comparable across providers**. Only operations
that *fail* with a throttling error are counted, so a client SDK that retries a rejection
internally and eventually succeeds reports `0.000%` throttled while sitting on a capacity
ceiling, whereas one that surfaces the rejection is marked invalid for the identical underlying
condition. This was observed directly: at 64 KB items, DynamoDB writes surfaced 1.6–2.8%
throttling, while Cosmos writes reported no throttling at all yet reached only 61–71 of the
80 ops/s they set out to measure at 80–93% of provisioned RU/s. Asking every provider whether it
held the offered load asks the same question regardless of where its retries happen.

The `Valid` column names which rule failed — `invalid (throttled)` or `invalid (under target)` —
so a row marked invalid with `0.000%` throttling is not mistaken for a reporting bug.

The same rule gates the migration-parity verdict. Parity compares a target row against the
baseline row, so it needs both to be valid: a throttled baseline collapses to a low throughput
and a high p99, which every target beats trivially, and the table would otherwise report that
as a pass. When either side is invalid the verdict is `⛔` (`INVALID` in HTML) instead of
`✅`/`⚠️` — the comparison is unmeasured, not passing and not regressing. Raise the provisioned
capacity for that item size and re-run.

## Example commands

```bash
# Matched offered-load read comparison
multiclouddb-perf/perf.sh run --providers cosmos,dynamo,spanner --workload read \
  --threads 8 --target-ops-per-sec 1500 --iterations 500 --region-policy fail

# Max-throughput saturation sweep
multiclouddb-perf/perf.sh run --providers cosmos,dynamo --workload mixed \
  --scenarios S1,S3 --threads 1,8,32 --iterations 500

# Deterministic Dynamo capacity
multiclouddb-perf/perf.sh run --providers dynamo --workload write \
  --dynamo-rcu 2000 --dynamo-wcu 2000 --threads 8 --iterations 500
```

## Safety

- Live accounts only; cost-incurring operations are opt-in.
- No CI execution.
- No automatic branch/commit/push behavior.
- No live cloud tests should be run from automation without explicit operator intent.
