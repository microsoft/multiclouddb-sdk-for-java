// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a Markdown perf report. */
final class MarkdownReport {

    private MarkdownReport() {
    }

    static Path write(List<StatRow> stats, List<EnvRow> env, ReportMeta meta, Path outDir) {
        StringBuilder b = new StringBuilder();
        b.append("# Performance Report\n\n");
        b.append("- **Run:** ").append(meta.title()).append('\n');
        b.append("- **Generated:** ").append(meta.generatedUtc()).append('\n');
        b.append("- **Source:** `").append(meta.sourceLabel()).append("`\n");
        b.append("- **A row is invalid when:** its throttled-op rate exceeds ")
                .append(String.format(Locale.ROOT, "%.3f%%", meta.invalidThrottleRate() * 100.0))
                .append(", **or** it sustained less than ")
                .append(String.format(Locale.ROOT, "%.0f%%", Reports.MIN_ATTAINED_TARGET_RATIO * 100.0))
                .append(" of its target throughput. The second rule is the provider-neutral one: an SDK that")
                .append(" retries a rejection internally reports no throttling while still running at a")
                .append(" capacity ceiling.\n\n");
        b.append("> Fair comparisons require the same offered load, workload profile, client placement, and deterministic capacity. "
                + "Provider capacity units are **not equivalent** (Cosmos RU vs Dynamo RCU/WCU).\n\n");

        List<String> providers = Reports.providerOrder(stats);
        environment(b, env, providers);
        whatWasTested(b, stats);
        perProvider(b, stats, providers, meta.invalidThrottleRate());
        crossProvider(b, stats, providers);
        parityAndScaling(b, stats, providers, meta);

        Path dest = outDir.resolve(sanitize(meta.title()) + "-REPORT.md");
        try {
            Files.createDirectories(outDir);
            Files.writeString(dest, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing Markdown report", e);
        }
        return dest;
    }

    private static void environment(StringBuilder b, List<EnvRow> env, List<String> providers) {
        b.append("## 1. Environment\n\n");
        b.append("| Provider | Region | Comparison region | Transport | Endpoint RTT | Billing mode | Provisioned capacity | Client host | JDK | SDK version |\n");
        b.append("|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, EnvRow> byProvider = new LinkedHashMap<>();
        for (EnvRow row : env) {
            byProvider.put(row.provider(), row);
        }
        for (String provider : providers) {
            EnvRow row = byProvider.get(provider);
            if (row == null) {
                continue;
            }
            b.append("| ").append(provider)
                    .append(" | ").append(orDash(row.region()))
                    .append(" | ").append(orDash(row.comparisonRegion()))
                    .append(" | ").append(orDash(row.transportProfile()))
                    .append(" | ").append(rtt(row.endpointRttMs()))
                    .append(" | ").append(orDash(row.billingMode()))
                    .append(" | ").append(orDash(row.provisionedCapacity()))
                    .append(" | ").append(orDash(row.hostLabel()))
                    .append(" | ").append(orDash(row.jdk()))
                    .append(" | ").append(orDash(row.sdkVersion())).append(" |\n");
        }
        b.append('\n');
    }

    private static void whatWasTested(StringBuilder b, List<StatRow> stats) {
        b.append("## 2. What was tested\n\n");
        b.append("Scenario identifiers are labels for a measurement profile, not for distinct "
                + "code paths: two scenarios given the same parameters exercise the same work.\n\n");

        b.append("| Scenario | Workload | Operation | Partition scope | Doc size | Page size | Threads | Measured ops |\n");
        b.append("|---|---|---|---|---|---|---|---|\n");
        Map<String, Scenarios.Profile> profiles = Scenarios.profiles(stats);
        for (Scenarios.Profile profile : profiles.values()) {
            b.append("| ").append(profile.scenario())
                    .append(" | ").append(profile.workload())
                    .append(" | ").append(profile.operation())
                    .append(" | ").append(profile.scope())
                    .append(" | ").append(Scenarios.docSizeLabel(profile.docSize()))
                    .append(" | ").append(profile.pageSize() == null ? "—" : profile.pageSize())
                    .append(" | ").append(profile.threads())
                    .append(" | ").append(profile.count()).append(" |\n");
        }
        b.append('\n').append(Scenarios.columnNote()).append("\n\n");

        for (Map.Entry<String, String> entry : Scenarios.purposes(profiles).entrySet()) {
            b.append("- **").append(entry.getKey()).append("** — ").append(entry.getValue()).append('\n');
        }
        String duplicates = Scenarios.duplicateScenarioNote(profiles);
        if (duplicates != null) {
            b.append('\n').append("> ").append(duplicates).append('\n');
        }
        b.append('\n');

        b.append("### How each measurement is taken\n\n");
        for (String note : Scenarios.methodology()) {
            b.append("- ").append(note).append('\n');
        }
        if (profiles.values().stream().anyMatch(pr -> Scenarios.isQuery(pr.scenario()))) {
            b.append('\n').append("> ").append(Scenarios.queryOrderingNote()).append('\n');
        }
        b.append('\n');
    }

    private static void perProvider(StringBuilder b, List<StatRow> stats, List<String> providers,
                                    double invalidThrottleRate) {
        b.append("## 3. Per-provider detail\n\n");
        for (String provider : providers) {
            b.append("### ").append(provider).append("\n\n");
            b.append("| Workload | Operation | Scenario | Scope | Threads | Target ops/s | Offered ops/s | Achieved ops/s | Achieved/Offered | p50 ms | p90 ms | p99 ms | svc p50 ms | svc p99 ms | Cost | Consumed units/s | Capacity util | Throttled | Retries | Valid |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (StatRow row : stats) {
                if (!provider.equals(row.provider())) {
                    continue;
                }
                b.append("| ").append(row.workload())
                        .append(" | ").append(row.operation())
                        .append(" | ").append(row.scenario())
                        .append(" | ").append(Scenarios.scopeColumn(row.variant()))
                        .append(" | ").append(row.threads())
                        .append(" | ").append(Reports.numOrDash(row.targetOpsPerSec()))
                        .append(" | ").append(Reports.num(row.offeredOpsSec()))
                        .append(" | ").append(Reports.num(row.throughputOpsSec()))
                        .append(" | ").append(Reports.num(row.achievedOfferedRatio())).append("x")
                        .append(" | ").append(Reports.num(row.p50()))
                        .append(" | ").append(Reports.num(row.p90()))
                        .append(" | ").append(Reports.num(row.p99()))
                        .append(" | ").append(Reports.numOrDash(row.serviceP50()))
                        .append(" | ").append(Reports.numOrDash(row.serviceP99()))
                        .append(" | ").append(costSummary(row))
                        .append(" | ").append(Reports.numOrDash(row.consumedUnitsPerSec()))
                        .append(" | ").append(utilSummary(row))
                        .append(" | ").append(String.format(Locale.ROOT, "%.3f%%", row.throttledRate() * 100.0))
                        .append(" | ").append(row.retryCountTotal() == null ? "—" : row.retryCountTotal())
                        .append(" | ").append(Reports.validity(row, invalidThrottleRate)).append(" |\n");
            }
            b.append('\n');
        }
    }

    private static void crossProvider(StringBuilder b, List<StatRow> stats, List<String> providers) {
        b.append("## 4. Cross-provider comparison\n\n");
        b.append("> `p99` is raw wall-clock latency from this client. `service-time p99` subtracts the measured "
                + "endpoint TCP RTT, so a provider is not penalised purely for being further from the test host. "
                + "Only a colocated client per cloud removes network distance entirely.\n\n");
        comparisonTable(b, "p99 latency (lower is better)", stats, providers, true,
                row -> row.p99(), false);
        comparisonTable(b, "service-time p99, RTT-normalised (lower is better)", stats, providers, true,
                row -> row.serviceP99() == null ? -1.0 : row.serviceP99(), true);
        comparisonTable(b, "throughput (higher is better)", stats, providers, false,
                StatRow::throughputOpsSec, false);
        comparisonTable(b, "cost mean (lower is better within provider units)", stats, providers, true,
                row -> row.costMean() == null ? -1.0 : row.costMean(), true);
    }

    private static void comparisonTable(StringBuilder b, String title, List<StatRow> stats,
                                        List<String> providers, boolean lowerBetter,
                                        Metric metric, boolean skipMissingMetric) {
        b.append("### ").append(title).append("\n\n");
        b.append("| Workload | Operation | Scenario | Scope | Threads | ");
        for (String provider : providers) {
            b.append(provider).append(" | ");
        }
        b.append("Best |\n");
        b.append("|---|---|---|---|---|");
        for (int i = 0; i < providers.size(); i++) {
            b.append("---|");
        }
        b.append("---|\n");

        Map<String, Map<String, Double>> grouped = new LinkedHashMap<>();
        Map<String, StatRow> sample = new LinkedHashMap<>();
        for (StatRow row : stats) {
            double value = metric.apply(row);
            if (skipMissingMetric && value < 0.0) {
                continue;
            }
            String key = row.workload() + "\u0001" + row.operation() + "\u0001" + row.scenario()
                    + "\u0001" + row.variant() + "\u0001" + row.threads();
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(row.provider(), value);
            sample.putIfAbsent(key, row);
        }
        for (Map.Entry<String, Map<String, Double>> entry : grouped.entrySet()) {
            StatRow row = sample.get(entry.getKey());
            String best = bestProvider(entry.getValue(), lowerBetter);
            b.append("| ").append(row.workload())
                    .append(" | ").append(row.operation())
                    .append(" | ").append(row.scenario())
                    .append(" | ").append(Scenarios.scopeColumn(row.variant()))
                    .append(" | ").append(row.threads()).append(" | ");
            for (String provider : providers) {
                Double value = entry.getValue().get(provider);
                if (value == null) {
                    b.append("— | ");
                } else if (provider.equals(best)) {
                    b.append("**").append(Reports.num(value)).append("** | ");
                } else {
                    b.append(Reports.num(value)).append(" | ");
                }
            }
            b.append(best == null ? "—" : best).append(" |\n");
        }
        b.append('\n');
    }

    /**
     * Three-state parity verdict. An invalid measurement is never rendered as a pass or as a
     * regression, because neither claim is supported by the data behind it.
     */
    private static String parityVerdict(ThreadAnalysis.ParityRow row) {
        if (!row.measurementValid()) {
            return "⛔";
        }
        return row.pass() ? "✅" : "⚠️";
    }

    private static void parityAndScaling(StringBuilder b, List<StatRow> stats,
                                         List<String> providers, ReportMeta meta) {
        b.append("## 5. Thread-scaling & migration parity\n\n");
        List<ThreadAnalysis.ParityRow> parity = ThreadAnalysis.parity(stats, providers, meta.baseline(),
                meta.invalidThrottleRate());
        if (!parity.isEmpty()) {
            b.append("### Migration parity vs baseline `").append(meta.baseline()).append("`\n\n");
            b.append("| Workload | Operation | Scenario | Scope | Threads | Baseline ops/s | Baseline p99 | Verdict |\n");
            b.append("|---|---|---|---|---|---|---|---|\n");
            boolean anyUnmeasured = false;
            for (ThreadAnalysis.ParityRow row : parity) {
                anyUnmeasured = anyUnmeasured || !row.measurementValid();
                b.append("| ").append(row.workload())
                        .append(" | ").append(row.operation())
                        .append(" | ").append(row.scenario())
                        .append(" | ").append(Scenarios.scopeColumn(row.variant()))
                        .append(" | ").append(row.threads())
                        .append(" | ").append(Reports.num(row.baseTput()))
                        .append(" | ").append(Reports.num(row.baseP99()))
                        .append(" | ").append(parityVerdict(row)).append(" |\n");
            }
            b.append('\n');
            if (anyUnmeasured) {
                b.append("⛔ marks a comparison in which the baseline or a target row was itself an ")
                        .append("invalid measurement — see the `Valid` column above for which rule it ")
                        .append("failed. A provider cannot be shown to keep up with a measurement that ")
                        .append("is not usable — a baseline that collapsed hands out passing verdicts ")
                        .append("to everything — so no verdict is given. Raise the provisioned capacity ")
                        .append("for that item size and re-run.\n\n");
            }
        }
        List<ThreadAnalysis.ScalingRow> scaling = ThreadAnalysis.scaling(stats, providers);
        if (scaling.isEmpty()) {
            b.append("_Need at least two thread levels for scaling analysis._\n\n");
            return;
        }
        b.append("### Thread scaling\n\n");
        b.append("| Provider | Workload | Operation | Scenario | Scope | Peak threads | Scale |\n");
        b.append("|---|---|---|---|---|---|---|\n");
        for (ThreadAnalysis.ScalingRow row : scaling) {
            b.append("| ").append(row.provider())
                    .append(" | ").append(row.workload())
                    .append(" | ").append(row.operation())
                    .append(" | ").append(row.scenario())
                    .append(" | ").append(Scenarios.scopeColumn(row.variant()))
                    .append(" | ").append(row.peakThreads())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2fx", row.scalingFactor()))
                    .append(" |\n");
        }
        b.append('\n');
    }

    @FunctionalInterface
    private interface Metric {
        double apply(StatRow row);
    }

    private static String bestProvider(Map<String, Double> values, boolean lowerBetter) {
        String best = null;
        double bestValue = 0.0;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double value = entry.getValue();
            if (value < 0.0) {
                continue;
            }
            if (best == null || (lowerBetter ? value < bestValue : value > bestValue)) {
                best = entry.getKey();
                bestValue = value;
            }
        }
        return best;
    }

    private static String rtt(Double value) {
        return value == null ? "—" : Reports.num(value) + " ms";
    }

    private static String costSummary(StatRow row) {
        if (row.costMean() == null || row.costUnit() == null || row.costUnit().isBlank()) {
            return "—";
        }
        return Reports.num(row.costMean()) + " " + row.costUnit();
    }

    private static String utilSummary(StatRow row) {
        if (row.capacityUtilizationPct() == null) {
            return "—";
        }
        return Reports.num(row.capacityUtilizationPct()) + "%";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
