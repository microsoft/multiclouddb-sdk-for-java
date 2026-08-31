// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders a self-contained HTML perf report. */
final class HtmlReport {

    private HtmlReport() {
    }

    static Path write(List<StatRow> stats, List<EnvRow> env, ReportMeta meta, Path outDir) {
        List<String> providers = Reports.providerOrder(stats);
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        b.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        b.append("<title>Perf Report — ").append(Reports.esc(meta.title())).append("</title>");
        b.append(css()).append("</head><body>");
        b.append("<h1>Performance Report</h1>");
        b.append("<p><strong>").append(Reports.esc(meta.title())).append("</strong><br>")
                .append("Generated ").append(Reports.esc(meta.generatedUtc())).append("<br>")
                .append("Source <code>").append(Reports.esc(meta.sourceLabel())).append("</code><br>")
                .append("A row is invalid when its throttled-op rate exceeds ")
                .append(String.format(Locale.ROOT, "%.3f%%", meta.invalidThrottleRate() * 100.0))
                .append(", or it sustained less than ")
                .append(String.format(Locale.ROOT, "%.0f%%", Reports.MIN_ATTAINED_TARGET_RATIO * 100.0))
                .append(" of its target throughput. The second rule is the provider-neutral one: an SDK "
                        + "that retries a rejection internally reports no throttling while still running "
                        + "at a capacity ceiling.")
                .append("</p>");
        b.append("<p class=\"note\">Fair comparisons require the same offered load, workload profile, client placement, and deterministic capacity. Provider capacity units are not equivalent.</p>");

        envTable(b, env, providers);
        whatWasTested(b, stats);
        charts(b, stats, providers);
        perProviderTables(b, stats, providers, meta.invalidThrottleRate());
        comparisonTables(b, stats, providers);
        parityAndScaling(b, stats, providers, meta);

        b.append("</body></html>");
        Path dest = outDir.resolve(MarkdownReport.sanitize(meta.title()) + "-REPORT.html");
        try {
            Files.createDirectories(outDir);
            Files.writeString(dest, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing HTML report", e);
        }
        return dest;
    }

    private static void envTable(StringBuilder b, List<EnvRow> env, List<String> providers) {
        b.append("<h2>1. Environment</h2><table><thead><tr><th>Provider</th><th>Region</th><th>Comparison region</th><th>Transport</th><th>Endpoint RTT</th><th>Billing mode</th><th>Provisioned capacity</th><th>Client host</th><th>JDK</th><th>SDK version</th></tr></thead><tbody>");
        Map<String, EnvRow> byProvider = new LinkedHashMap<>();
        for (EnvRow row : env) {
            byProvider.put(row.provider(), row);
        }
        for (String provider : providers) {
            EnvRow row = byProvider.get(provider);
            if (row == null) {
                continue;
            }
            b.append("<tr><td>").append(Reports.esc(row.provider()))
                    .append("</td><td>").append(Reports.esc(orDash(row.region())))
                    .append("</td><td>").append(Reports.esc(orDash(row.comparisonRegion())))
                    .append("</td><td>").append(Reports.esc(orDash(row.transportProfile())))
                    .append("</td><td>").append(Reports.esc(rtt(row.endpointRttMs())))
                    .append("</td><td>").append(Reports.esc(orDash(row.billingMode())))
                    .append("</td><td>").append(Reports.esc(orDash(row.provisionedCapacity())))
                    .append("</td><td>").append(Reports.esc(orDash(row.hostLabel())))
                    .append("</td><td>").append(Reports.esc(orDash(row.jdk())))
                    .append("</td><td>").append(Reports.esc(orDash(row.sdkVersion())))
                    .append("</td></tr>");
        }
        b.append("</tbody></table>");
    }

    private static void charts(StringBuilder b, List<StatRow> stats, List<String> providers) {
        b.append("<h2>3. At a glance</h2>");
        b.append("<p class=\"note\">Provider colors are consistent across charts. Compare bars only within the same metric; provider cost units are intentionally not charted together.</p>");
        Set<String> panels = new LinkedHashSet<>();
        for (StatRow row : stats) {
            panels.add(row.workload() + "\u0001" + row.scenario());
        }
        for (String panel : panels) {
            String[] parts = panel.split("\u0001", -1);
            String workload = parts[0];
            String scenario = parts[1];
            List<StatRow> rows = stats.stream()
                    .filter(row -> workload.equals(row.workload()) && scenario.equals(row.scenario()))
                    .toList();
            b.append("<section class=\"chart-panel\"><h3>")
                    .append(Reports.esc(workload)).append(" / ").append(Reports.esc(scenario))
                    .append("</h3><div class=\"chart-grid\">");
            b.append(svgChart(rows, providers, "p99 latency", "ms", true, StatRow::p99));
            if (rows.stream().anyMatch(row -> row.serviceP99() != null)) {
                b.append(svgChart(rows, providers, "p99 service time (RTT-normalised)", "ms", true,
                        row -> row.serviceP99() == null ? -1.0 : row.serviceP99()));
            }
            b.append(svgChart(rows, providers, "Achieved throughput", "ops/s", false,
                    StatRow::throughputOpsSec));
            b.append(svgChart(rows, providers, "Achieved / offered", "%", false,
                    row -> row.achievedOfferedRatio() * 100.0));
            if (rows.stream().anyMatch(row -> row.capacityUtilizationPct() != null)) {
                b.append(svgChart(rows, providers, "Capacity utilization", "%", true,
                        row -> row.capacityUtilizationPct() == null ? -1.0 : row.capacityUtilizationPct()));
            }
            if (rows.stream().anyMatch(row -> row.throttledRate() > 0.0)) {
                b.append(svgChart(rows, providers, "Throttled operations", "%", true,
                        row -> row.throttledRate() * 100.0));
            }
            b.append("</div></section>");
        }
    }

    private static String svgChart(List<StatRow> stats, List<String> providers, String title,
                                   String unit, boolean lowerBetter, Metric metric) {
        Map<String, Map<String, Double>> groups = new LinkedHashMap<>();
        Set<Integer> threadLevels = new LinkedHashSet<>();
        for (StatRow row : stats) {
            threadLevels.add(row.threads());
        }
        double maximum = 0.0;
        for (StatRow row : stats) {
            double value = metric.apply(row);
            if (value < 0.0) {
                continue;
            }
            String label = row.operation();
            if (threadLevels.size() > 1) {
                label += " @" + row.threads() + "t";
            }
            groups.computeIfAbsent(label, ignored -> new LinkedHashMap<>())
                    .put(row.provider(), value);
            maximum = Math.max(maximum, value);
        }
        if (groups.isEmpty()) {
            return "";
        }
        maximum = Math.max(maximum, 1.0);
        int left = 112;
        int chartWidth = 360;
        int rowHeight = 24;
        int groupGap = 15;
        int top = 66;
        int height = top;
        for (Map<String, Double> group : groups.values()) {
            long bars = providers.stream().filter(group::containsKey).count();
            height += (int) bars * rowHeight + groupGap;
        }
        int width = 610;
        StringBuilder svg = new StringBuilder();
        svg.append("<figure class=\"metric-chart\"><svg viewBox=\"0 0 ")
                .append(width).append(' ').append(height)
                .append("\" role=\"img\" aria-label=\"").append(Reports.esc(title)).append("\">");
        svg.append("<text x=\"12\" y=\"24\" class=\"chart-title\">").append(Reports.esc(title))
                .append("</text><text x=\"12\" y=\"44\" class=\"chart-subtitle\">")
                .append(lowerBetter ? "lower is better" : "higher is better")
                .append(" · ").append(Reports.esc(unit)).append("</text>");
        int legendX = 300;
        for (String provider : providers) {
            svg.append("<rect x=\"").append(legendX).append("\" y=\"15\" width=\"12\" height=\"12\" rx=\"2\" fill=\"")
                    .append(color(provider)).append("\"/><text x=\"").append(legendX + 17)
                    .append("\" y=\"26\" class=\"chart-legend\">").append(Reports.esc(provider)).append("</text>");
            legendX += 92;
        }
        int y = top;
        for (Map.Entry<String, Map<String, Double>> group : groups.entrySet()) {
            svg.append("<text x=\"12\" y=\"").append(y + 14).append("\" class=\"chart-label\">")
                    .append(Reports.esc(group.getKey())).append("</text>");
            for (String provider : providers) {
                Double value = group.getValue().get(provider);
                if (value == null) {
                    continue;
                }
                double barWidth = Math.max(value > 0.0 ? 2.0 : 0.0, value / maximum * chartWidth);
                svg.append(String.format(Locale.ROOT,
                        "<rect x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"17\" rx=\"3\" fill=\"%s\"/>",
                        left, y, barWidth, color(provider)));
                svg.append("<text x=\"").append(String.format(Locale.ROOT, "%.1f", left + barWidth + 7))
                        .append("\" y=\"").append(y + 13).append("\" class=\"chart-value\">")
                        .append(Reports.esc(provider)).append(": ").append(Reports.num(value)).append(' ')
                        .append(Reports.esc(unit)).append("</text>");
                y += rowHeight;
            }
            y += groupGap;
        }
        svg.append("</svg></figure>");
        return svg.toString();
    }

    private static String color(String provider) {
        return switch (provider) {
            case "cosmos" -> "#0078d4";
            case "dynamo" -> "#ff9900";
            case "spanner" -> "#34a853";
            default -> "#6e7781";
        };
    }

    private static void whatWasTested(StringBuilder b, List<StatRow> stats) {
        b.append("<h2>2. What was tested</h2>");
        b.append("<p class=\"note\">Scenario identifiers are labels for a measurement profile, not "
                + "for distinct code paths: two scenarios given the same parameters exercise the "
                + "same work.</p>");
        b.append("<table><thead><tr><th>Scenario</th><th>Workload</th><th>Operation</th>"
                + "<th>Partition scope</th><th>Doc size</th><th>Page size</th><th>Threads</th>"
                + "<th>Measured ops</th></tr></thead><tbody>");
        Map<String, Scenarios.Profile> profiles = Scenarios.profiles(stats);
        for (Scenarios.Profile profile : profiles.values()) {
            b.append("<tr><td>").append(Reports.esc(profile.scenario()))
                    .append("</td><td>").append(Reports.esc(profile.workload()))
                    .append("</td><td>").append(Reports.esc(profile.operation()))
                    .append("</td><td>").append(Reports.esc(profile.scope()))
                    .append("</td><td>").append(Reports.esc(Scenarios.docSizeLabel(profile.docSize())))
                    .append("</td><td>").append(profile.pageSize() == null ? "&mdash;" : profile.pageSize())
                    .append("</td><td>").append(profile.threads())
                    .append("</td><td>").append(profile.count())
                    .append("</td></tr>");
        }
        b.append("</tbody></table>");
        b.append("<p class=\"note\">").append(Reports.esc(Scenarios.columnNote())).append("</p>");

        b.append("<ul>");
        for (Map.Entry<String, String> entry : Scenarios.purposes(profiles).entrySet()) {
            b.append("<li><strong>").append(Reports.esc(entry.getKey())).append("</strong> &mdash; ")
                    .append(Reports.esc(entry.getValue())).append("</li>");
        }
        b.append("</ul>");
        String duplicates = Scenarios.duplicateScenarioNote(profiles);
        if (duplicates != null) {
            b.append("<p class=\"note\">").append(Reports.esc(duplicates)).append("</p>");
        }

        b.append("<h3>How each measurement is taken</h3><ul>");
        for (String note : Scenarios.methodology()) {
            b.append("<li>").append(Reports.esc(note)).append("</li>");
        }
        b.append("</ul>");
        if (profiles.values().stream().anyMatch(pr -> Scenarios.isQuery(pr.scenario()))) {
            b.append("<p class=\"note\">").append(Reports.esc(Scenarios.queryOrderingNote()))
                    .append("</p>");
        }
    }

    private static void perProviderTables(StringBuilder b, List<StatRow> stats, List<String> providers,
                                          double invalidThrottleRate) {
        b.append("<h2>4. Per-provider detail</h2>");
        for (String provider : providers) {
            b.append("<h3>").append(Reports.esc(provider)).append("</h3>");
            b.append("<table><thead><tr><th>Workload</th><th>Operation</th><th>Scenario</th><th>Scope</th><th>Threads</th><th>Target ops/s</th><th>Offered ops/s</th><th>Achieved ops/s</th><th>Achieved/Offered</th><th>p50 ms</th><th>p90 ms</th><th>p99 ms</th><th>svc p50 ms</th><th>svc p99 ms</th><th>Cost</th><th>Consumed units/s</th><th>Capacity util</th><th>Throttled</th><th>Retries</th><th>Valid</th></tr></thead><tbody>");
            for (StatRow row : stats) {
                if (!provider.equals(row.provider())) {
                    continue;
                }
                b.append("<tr><td>").append(Reports.esc(row.workload()))
                        .append("</td><td>").append(Reports.esc(row.operation()))
                        .append("</td><td>").append(Reports.esc(row.scenario()))
                        .append("</td><td>").append(Reports.esc(Scenarios.scopeColumn(row.variant())))
                        .append("</td><td>").append(row.threads())
                        .append("</td><td>").append(Reports.esc(Reports.numOrDash(row.targetOpsPerSec())))
                        .append("</td><td>").append(Reports.num(row.offeredOpsSec()))
                        .append("</td><td>").append(Reports.num(row.throughputOpsSec()))
                        .append("</td><td>").append(Reports.num(row.achievedOfferedRatio())).append("x")
                        .append("</td><td>").append(Reports.num(row.p50()))
                        .append("</td><td>").append(Reports.num(row.p90()))
                        .append("</td><td>").append(Reports.num(row.p99()))
                        .append("</td><td>").append(Reports.esc(Reports.numOrDash(row.serviceP50())))
                        .append("</td><td>").append(Reports.esc(Reports.numOrDash(row.serviceP99())))
                        .append("</td><td>").append(Reports.esc(costSummary(row)))
                        .append("</td><td>").append(Reports.esc(Reports.numOrDash(row.consumedUnitsPerSec())))
                        .append("</td><td>").append(Reports.esc(utilSummary(row)))
                        .append("</td><td>").append(String.format(Locale.ROOT, "%.3f%%", row.throttledRate() * 100.0))
                        .append("</td><td>").append(row.retryCountTotal() == null ? "&mdash;" : row.retryCountTotal())
                        .append("</td><td>").append(Reports.esc(Reports.validity(row, invalidThrottleRate)))
                        .append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
    }

    private static void comparisonTables(StringBuilder b, List<StatRow> stats, List<String> providers) {
        b.append("<h2>5. Cross-provider comparison</h2>");
        b.append("<p class=\"note\">p99 is raw wall-clock latency from this client. Service-time p99 subtracts the measured endpoint TCP RTT, so a provider is not penalised purely for being further from the test host. Only a colocated client per cloud removes network distance entirely.</p>");
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
        b.append("<h3>").append(Reports.esc(title)).append("</h3>");
        b.append("<table><thead><tr><th>Workload</th><th>Operation</th><th>Scenario</th><th>Scope</th><th>Threads</th>");
        for (String provider : providers) {
            b.append("<th>").append(Reports.esc(provider)).append("</th>");
        }
        b.append("<th>Best</th></tr></thead><tbody>");

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
            b.append("<tr><td>").append(Reports.esc(row.workload()))
                    .append("</td><td>").append(Reports.esc(row.operation()))
                    .append("</td><td>").append(Reports.esc(row.scenario()))
                    .append("</td><td>").append(Reports.esc(Scenarios.scopeColumn(row.variant())))
                    .append("</td><td>").append(row.threads()).append("</td>");
            for (String provider : providers) {
                Double value = entry.getValue().get(provider);
                if (value == null) {
                    b.append("<td>&mdash;</td>");
                } else if (provider.equals(best)) {
                    b.append("<td class=\"best\">").append(Reports.num(value)).append("</td>");
                } else {
                    b.append("<td>").append(Reports.num(value)).append("</td>");
                }
            }
            b.append("<td>").append(Reports.esc(best == null ? "—" : best)).append("</td></tr>");
        }
        b.append("</tbody></table>");
    }

    /**
     * Three-state parity verdict. An invalid measurement is never rendered as a pass or as a
     * regression, because neither claim is supported by the data behind it.
     */
    private static String parityVerdict(ThreadAnalysis.ParityRow row) {
        if (!row.measurementValid()) {
            return "INVALID";
        }
        return row.pass() ? "PASS" : "CHECK";
    }

    private static void parityAndScaling(StringBuilder b, List<StatRow> stats,
                                         List<String> providers, ReportMeta meta) {
        b.append("<h2>6. Thread-scaling &amp; migration parity</h2>");
        List<ThreadAnalysis.ParityRow> parity = ThreadAnalysis.parity(stats, providers, meta.baseline(),
                meta.invalidThrottleRate());
        if (!parity.isEmpty()) {
            b.append("<h3>Migration parity vs baseline <code>").append(Reports.esc(meta.baseline())).append("</code></h3>");
            b.append("<table><thead><tr><th>Workload</th><th>Operation</th><th>Scenario</th><th>Scope</th><th>Threads</th><th>Baseline ops/s</th><th>Baseline p99</th><th>Verdict</th></tr></thead><tbody>");
            boolean anyUnmeasured = false;
            for (ThreadAnalysis.ParityRow row : parity) {
                anyUnmeasured = anyUnmeasured || !row.measurementValid();
                b.append("<tr><td>").append(Reports.esc(row.workload()))
                        .append("</td><td>").append(Reports.esc(row.operation()))
                        .append("</td><td>").append(Reports.esc(row.scenario()))
                        .append("</td><td>").append(Reports.esc(Scenarios.scopeColumn(row.variant())))
                        .append("</td><td>").append(row.threads())
                        .append("</td><td>").append(Reports.num(row.baseTput()))
                        .append("</td><td>").append(Reports.num(row.baseP99()))
                        .append("</td><td>").append(parityVerdict(row))
                        .append("</td></tr>");
            }
            b.append("</tbody></table>");
            if (anyUnmeasured) {
                b.append("<p class=\"note\">INVALID marks a comparison in which the baseline or a "
                        + "target row was itself an invalid measurement — see the Valid column above "
                        + "for which rule it failed. A provider cannot be shown to keep up with a "
                        + "measurement that is not usable — a baseline that collapsed hands out "
                        + "passing verdicts to everything — so no verdict is given. Raise the "
                        + "provisioned capacity for that item size and re-run.</p>");
            }
        }
        List<ThreadAnalysis.ScalingRow> scaling = ThreadAnalysis.scaling(stats, providers);
        if (scaling.isEmpty()) {
            b.append("<p class=\"note\">Need at least two thread levels for scaling analysis.</p>");
            return;
        }
        b.append("<h3>Thread scaling</h3>");
        b.append("<table><thead><tr><th>Provider</th><th>Workload</th><th>Operation</th><th>Scenario</th><th>Scope</th><th>Peak threads</th><th>Scale</th></tr></thead><tbody>");
        for (ThreadAnalysis.ScalingRow row : scaling) {
            b.append("<tr><td>").append(Reports.esc(row.provider()))
                    .append("</td><td>").append(Reports.esc(row.workload()))
                    .append("</td><td>").append(Reports.esc(row.operation()))
                    .append("</td><td>").append(Reports.esc(row.scenario()))
                    .append("</td><td>").append(Reports.esc(Scenarios.scopeColumn(row.variant())))
                    .append("</td><td>").append(row.peakThreads())
                    .append("</td><td>").append(String.format(Locale.ROOT, "%.2fx", row.scalingFactor()))
                    .append("</td></tr>");
        }
        b.append("</tbody></table>");
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
        return value == null ? "\u2014" : Reports.num(value) + " ms";
    }

    private static String costSummary(StatRow row) {
        if (row.costMean() == null || row.costUnit() == null || row.costUnit().isBlank()) {
            return "—";
        }
        return Reports.num(row.costMean()) + ' ' + row.costUnit();
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

    private static String css() {
        return "<style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;margin:24px;line-height:1.4;color:#1f2328;background:#fff}"
                + "table{border-collapse:collapse;width:100%;margin:16px 0}th,td{border:1px solid #d0d7de;padding:6px 8px;text-align:left}th{background:#f6f8fa}"
                + ".best{font-weight:700;background:#ddf4ff}.note{color:#57606a}"
                + ".chart-panel{margin:22px 0 30px;padding:16px 18px;border:1px solid #d0d7de;border-radius:10px;background:#f6f8fa}"
                + ".chart-panel h3{margin:0 0 12px}.chart-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(440px,1fr));gap:16px}"
                + ".metric-chart{margin:0;padding:10px;background:#fff;border:1px solid #d8dee4;border-radius:8px;box-shadow:0 1px 2px rgba(31,35,40,.08)}"
                + ".metric-chart svg{display:block;width:100%;height:auto;min-height:180px}"
                + ".chart-title{font-size:18px;font-weight:700;fill:#1f2328}.chart-subtitle{font-size:12px;fill:#57606a}"
                + ".chart-label{font-size:13px;font-weight:600;fill:#24292f}.chart-value,.chart-legend{font-size:12px;fill:#24292f}"
                + "@media(max-width:700px){body{margin:12px}.chart-grid{grid-template-columns:1fr}.chart-panel{padding:10px}.metric-chart{overflow-x:auto}.metric-chart svg{min-width:560px}}"
                + "@media print{body{margin:8mm}.chart-panel{break-inside:avoid}.metric-chart{box-shadow:none}}"
                + "</style>";
    }
}
