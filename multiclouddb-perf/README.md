# Performance tests (live accounts) — fair cross-provider method

> Live accounts only. Do **not** run in CI. Compare providers only when client placement,
> offered load, workload profile, and provisioned capacity are deliberately matched.

The `multiclouddb-perf` harness runs the real portable `MulticloudDbClient` in one JVM,
records one CSV row per measured operation, then renders Markdown + HTML reports.

## Fair-test checklist

Use the same for every provider in a comparison set:

- **Offered load**: set `multiclouddb.perf.targetOpsPerSec` identically in every provider
  property file. `--target-ops-per-sec N` overrides all configs. Use `0` only for
  max-throughput sweeps (`--threads 1,8,32`).
- **Workload profile**: `--workload read|write|mixed|query` selects one profile.
  The three point scenarios vary only document size: `S1` baseline, `S2` 8x baseline (`S6`'s
  item size, so point and query costs can be read at the same document size), `S3` 64x baseline
  for the large documents customers actually store.
  The three query scenarios each vary one dimension: `S4` partition scope, `S5` page size
  (quarter of baseline), `S6` item size (8x baseline, page shrunk 8x to hold bytes per page
  near baseline). `--doc-size` / `--page-size` set the baseline the scenarios derive from.
  Since `S2`/`S3`/`S6` multiply `--doc-size`, the harness refuses a baseline whose effective
  size would pass DynamoDB's 400 KB item limit, and fails before writing anything.
  `--workload all` runs the read, write, and query profiles in one batch and one report.
- **Client placement**: same host/JDK, plus matching `comparison_region` labels. A single client
  cannot be colocated with two clouds at once, so the harness probes each endpoint's TCP RTT at
  run start and the report also presents **service time** (`latency − RTT`). Compare service time
  when the client sits outside both clouds; compare raw latency only from a colocated client.
- **Transport profile**: each provider runs its recommended data path — Cosmos Gateway over
  HTTP/2 with automatic Gateway V2 probing/fallback, and Dynamo's Apache HTTP/1.1 client.
  Protocol parity is deliberately not a goal, since Cosmos is optimized for HTTP/2 and the AWS
  synchronous client offers no HTTP/2 transport. Forced and disabled Gateway V2 routing are
  separate diagnostic profiles.
- **Deterministic capacity**: configure `multiclouddb.perf.cosmosRu` or the paired
  `multiclouddb.perf.dynamoRcu` / `multiclouddb.perf.dynamoWcu` properties. The harness applies
  them before warmup, waits where required, and probes the actual resulting capacity.
- **Separate metrics**: compare latency, throughput, and provider-native cost separately.
  **Do not compare Cosmos RU directly with Dynamo RCU/WCU.**

Validity rules: a row is reported **invalid** when throttled operations exceed **0.1%**
(override with `--invalid-throttle-rate-pct`) **or** when it sustained less than **95%** of its
target throughput. The second rule is the provider-neutral one — throttled counts only include
operations that *fail*, so an SDK that retries a rejection internally reports `0.000%` throttled
while still running at a capacity ceiling. The `Valid` column names which rule failed. The
migration-parity table applies both rules to both sides of each comparison and reports `⛔`
(`INVALID` in HTML) when either side is invalid — a collapsed baseline would otherwise hand out
passing verdicts.

## Configure live accounts (never committed)

Copy the templates to `*.live.properties` (gitignored) and fill in real values.
You can optionally set `multiclouddb.comparisonRegion` to declare cross-cloud regions colocated
(e.g. `westus2` + `us-west-2` → `comparisonRegion=west-us-2-colo`).

The templates also contain the reproducible fairness controls:

```properties
# Same value in every provider config
multiclouddb.perf.targetOpsPerSec=100

# Cosmos config
multiclouddb.perf.cosmosRu=1000

# Dynamo config
multiclouddb.perf.dynamoRcu=100
multiclouddb.perf.dynamoWcu=100

# Transport: each provider's recommended data path
# Cosmos config — fixed Gateway HTTP/2 with Gateway V2 auto-probe/fallback
multiclouddb.connection.gatewayHttp2MaxConnectionPoolSize=64
multiclouddb.connection.gatewayHttp2MinConnectionPoolSize=8
multiclouddb.connection.gatewayHttp2MaxConcurrentStreams=32
multiclouddb.connection.contentResponseOnWriteEnabled=false

# Dynamo config
multiclouddb.connection.maxConnections=64
```

Cosmos DB is optimized for HTTP/2, while Dynamo's synchronous client is HTTP/1.1-only, so
protocol parity is deliberately not a goal — each provider runs the path its service recommends.
To isolate Gateway V2 routing while retaining fixed Gateway HTTP/2, set
`thinClientEnabled=false` for a diagnostic run. The `transport_profile` column records auto,
forced, and disabled Gateway V2 profiles separately, and aggregation refuses to mix them, so a
diagnostic run needs its own `--title`.

`contentResponseOnWriteEnabled=false` suppresses the document body Cosmos otherwise returns on
every write. DynamoDB's `PutItem` returns no item, so leaving it enabled charges Cosmos for
bandwidth its counterpart never pays.

CLI capacity and offered-load options override these properties for one-off experiments.

## Run examples

### Fair offered-load comparison

```bash
multiclouddb-perf/perf.sh run \
  --providers cosmos,dynamo \
  --workload all \
  --scenarios S1,S4,S5,S6 \
  --threads 8 \
  --target-ops-per-sec 80 \
  --iterations 500 \
  --region-policy warn
```

### Deterministic Dynamo provisioned-capacity run

```bash
multiclouddb-perf/perf.sh run \
  --providers dynamo \
  --workload write \
  --threads 8 \
  --dynamo-rcu 2000 --dynamo-wcu 2000 \
  --iterations 500
```

### Deterministic Cosmos manual-throughput run

```bash
multiclouddb-perf/perf.sh run \
  --providers cosmos \
  --threads 8 \
  --target-ops-per-sec 1500 \
  --cosmos-ru 20000 \
  --split-wait-seconds 480
```

### Large-document point operations

```bash
multiclouddb-perf/perf.sh run \
  --providers cosmos,dynamo \
  --workload all \
  --scenarios S1,S2,S3 \
  --threads 8 \
  --target-ops-per-sec 80 \
  --iterations 500
```

Reads and writes 1 KB, 8 KB, and 64 KB documents over the same profile, so the per-byte cost of
each provider can be separated from its per-operation cost.

**Provision for the largest item, not the smallest.** Write cost scales with item size, so S3
needs roughly 64x the write capacity of S1 at the same offered load. A run at
`--target-ops-per-sec 80` measured 64.6 WCU per S3 write on DynamoDB, i.e. ~5,170 WCU sustained —
a table provisioned at 200 WCU throttles hard (observed: p99 ~50 s, throughput collapsing to
2.5 ops/s). Cosmos shows the same scaling in its own units: 10.6 RU per update at S1 rising to
52.1 RU at S3. Size the table or container for the S3 row before the run, or the large-document
rows measure the capacity ceiling rather than the provider. Rows that throttle past the validity
threshold are reported `invalid`, and the migration-parity table marks the comparison `⛔` rather
than issuing a verdict.

### Query-only comparison

```bash
multiclouddb-perf/perf.sh run --workload query --threads 8 --target-ops-per-sec 400
```

## Key CLI options

- `--target-ops-per-sec N` — pace actual starts across worker threads; `0`/unset keeps legacy unbounded mode.
- `--threads 1,8,32` — concurrency sweep for saturation/max-throughput analysis.
- `--workload read|write|mixed|query|all` — explicit workload profile, or all read/write/query
  profiles in one batch and report.
- `--dynamo-rcu N --dynamo-wcu N` — switch/update Dynamo to `PROVISIONED` and wait for `ACTIVE`.
- `--cosmos-ru N` — set Cosmos manual throughput before the run.
- `--enable-dynamo-streams` — opt-in Dynamo Streams for change-feed scenarios.
- `--region-policy warn|fail|ignore` — handle config/probed region or comparison-label mismatches before measurement.
- `--invalid-throttle-rate-pct PCT` — report validity threshold (default `0.1`).

## Property-file controls

- `multiclouddb.perf.targetOpsPerSec` — paced offered load; must match across provider configs.
- `multiclouddb.perf.cosmosRu` — Cosmos manual RU/s applied before warmup.
- `multiclouddb.perf.dynamoRcu` and `multiclouddb.perf.dynamoWcu` — paired Dynamo provisioned
  capacity applied before warmup.
- `multiclouddb.connection.gatewayMaxConnectionPoolSize` — Cosmos Gateway V1 fallback pool.
- `multiclouddb.connection.thinClientEnabled` — unset for Gateway V2 auto-probe/fallback,
  `false` to disable it, or `true` to force it.
- `multiclouddb.connection.gatewayHttp2MinConnectionPoolSize`,
  `gatewayHttp2MaxConnectionPoolSize`, and `gatewayHttp2MaxConcurrentStreams` — Cosmos HTTP/2
  pool and multiplexing controls.
- `multiclouddb.connection.maxConnections` — Dynamo synchronous Apache HTTP/1.1 pool.

The CLI forms have precedence. The older `multiclouddb.provisionedCapacity` property is only
fallback report text and does not control provisioning.

## What the report now shows

- Target offered load, actual offered ops/s, achieved throughput, and achieved/offered ratio.
- Provider-native consumed units/sec and capacity-utilization percentage when a numeric relevant limit is known.
- Probed billing mode (`manual`, `autoscale`, `PROVISIONED`, `PAY_PER_REQUEST`, ...).
- Throttled count/rate, retry totals when surfaced by diagnostics, and row validity.
- Environment metadata including `comparison_region`.
- Configured transport profile and connection pool.
- Measured endpoint RTT per provider, plus RTT-normalised `svc p50` / `svc p99` service time.
- A **What was tested** section: every scenario/workload/operation profile that ran, with its
  partition scope, document size, page size, thread count, and measured operation count, plus
  what each scenario is for and how the measurement is taken.
- A **Scope** column separating single-partition from cross-partition queries. These are
  aggregated as distinct measurements, so a partition-scoped query is never averaged together
  with a cross-partition fan-out.

## Offline re-rendering

```bash
multiclouddb-perf/perf.sh report --run 2026-08-13T12-00-00Z-batch
multiclouddb-perf/perf.sh report --combined --invalid-throttle-rate-pct 0.05
```

## Cleanup

```bash
multiclouddb-perf/perf.sh cleanup
```

The harness cleans up its own seeded items, but interrupted runs may leave rows behind.
Provisioning changes are **not** reverted automatically.
