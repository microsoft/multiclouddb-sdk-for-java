#!/usr/bin/env bash
# Thin launcher for the Java perf CLI (com.microsoft.multiclouddb.perf.PerfMain).
# All arguments are forwarded to the CLI. The measurement, aggregation, and report
# rendering are ALL Java now — this script only builds once and invokes the JVM.
#
#   multiclouddb-perf/perf.sh run     --scenarios S1 --threads 8 --iterations 500
#   multiclouddb-perf/perf.sh report  --raw multiclouddb-perf/results/raw --reports multiclouddb-perf/results/reports --title myrun
#   multiclouddb-perf/perf.sh cleanup --config multiclouddb-perf/config/cosmos.live.properties --dry-run
#
# Pass --skip-build to reuse an already-built harness.
set -euo pipefail

SKIP_BUILD=0
ARGS=()
for a in "$@"; do
  if [[ "$a" == "--skip-build" ]]; then SKIP_BUILD=1; else ARGS+=("$a"); fi
done

if [[ "$SKIP_BUILD" != "1" ]]; then
  echo ">> building harness + SDK deps (mvn install -DskipTests)..."
  mvn -q -pl multiclouddb-perf -am install -DskipTests
fi

# Exec in a perf-ONLY reactor so exec:java binds to the perf module, not the aggregator root.
mvn -q -pl multiclouddb-perf process-resources exec:java -Dexec.args="${ARGS[*]}"
