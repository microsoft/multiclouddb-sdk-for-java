// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pooled aggregation of raw {@link ResultRow}s into {@link StatRow}s.
 */
final class Statistics {

    private Statistics() {
    }

    static List<ResultRow> readRawCsv(Path rawDir) {
        List<ResultRow> rows = new ArrayList<>();
        for (List<ResultRow> batch : readRawByBatch(rawDir).values()) {
            rows.addAll(batch);
        }
        return rows;
    }

    static Map<String, List<ResultRow>> readRawByBatch(Path rawDir) {
        Map<String, List<ResultRow>> byBatch = new LinkedHashMap<>();
        if (!Files.isDirectory(rawDir)) {
            return byBatch;
        }
        try (Stream<Path> files = Files.list(rawDir)) {
            List<Path> csvs = files.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
            for (Path csv : csvs) {
                List<ResultRow> rows = parseCsv(csv);
                if (!rows.isEmpty()) {
                    byBatch.computeIfAbsent(batchIdOf(csv), k -> new ArrayList<>()).addAll(rows);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading raw CSVs under " + rawDir, e);
        }
        return byBatch;
    }

    private static String batchIdOf(Path csv) {
        String name = csv.getFileName().toString();
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    private static List<ResultRow> parseCsv(Path csv) throws IOException {
        List<ResultRow> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return rows;
        }
        Map<String, Integer> col = headerIndex(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> f = parseCsvLine(lines.get(i));
            String operation = get(f, col, "operation");
            rows.add(new ResultRow(
                    get(f, col, "run_id"),
                    get(f, col, "timestamp_utc"),
                    get(f, col, "provider"),
                    get(f, col, "region"),
                    get(f, col, "comparison_region"),
                    get(f, col, "transport_profile"),
                    doubleOrNull(get(f, col, "endpoint_rtt_ms")),
                    get(f, col, "host_label"),
                    get(f, col, "jdk"),
                    operation,
                    workloadOrDefault(get(f, col, "workload"), operation),
                    get(f, col, "scenario"),
                    intOr(get(f, col, "doc_size_bytes"), 0),
                    intOrNull(get(f, col, "page_size")),
                    intOr(get(f, col, "threads"), 1),
                    intOr(get(f, col, "iteration"), 0),
                    doubleOr(get(f, col, "start_offset_ms"), 0.0),
                    doubleOr(get(f, col, "end_offset_ms"), 0.0),
                    doubleOr(get(f, col, "latency_ms"), 0.0),
                    "true".equalsIgnoreCase(get(f, col, "success")),
                    get(f, col, "error_category"),
                    get(f, col, "cost_unit"),
                    doubleOrNull(get(f, col, "cost_value")),
                    intOrNull(get(f, col, "retry_count")),
                    get(f, col, "capacity_limit_unit"),
                    doubleOrNull(get(f, col, "capacity_limit_value")),
                    get(f, col, "billing_mode"),
                    get(f, col, "provisioned_capacity"),
                    get(f, col, "sdk_version"),
                    doubleOrNull(get(f, col, "target_ops_per_sec")),
                    get(f, col, "notes")));
        }
        return rows;
    }

    static List<StatRow> aggregate(List<ResultRow> rows) {
        validateSingleTransportProfile(rows);
        Map<String, Group> groups = new LinkedHashMap<>();
        for (ResultRow r : rows) {
            // Query scope is part of the identity of a measurement: a single-partition read and a
            // cross-partition fan-out are different operations, and averaging them together
            // produces a blended number that describes neither.
            String key = String.join("\u0001", r.provider(), r.operation(), workloadOrDefault(r.workload(), r.operation()),
                    r.scenario(), String.valueOf(Scenarios.variant(r.notes())),
                    Integer.toString(r.threads()), Integer.toString(r.docSizeBytes()),
                    r.pageSize() == null ? "" : Integer.toString(r.pageSize()),
                    r.targetOpsPerSec() == null ? "unbounded" : Double.toString(r.targetOpsPerSec()));
            groups.computeIfAbsent(key, k -> new Group(r)).add(r);
        }
        List<StatRow> out = new ArrayList<>(groups.size());
        for (Group g : groups.values()) {
            out.add(g.toStatRow());
        }
        return out;
    }

    private static void validateSingleTransportProfile(List<ResultRow> rows) {
        Map<String, String> profiles = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            String profile = row.transportProfile() == null ? "" : row.transportProfile().trim();
            String existing = profiles.putIfAbsent(row.provider(), profile);
            if (existing != null && !existing.equals(profile)) {
                throw new IllegalArgumentException(
                        "Cannot aggregate multiple transport profiles for provider " + row.provider()
                                + ": '" + existing + "' and '" + profile
                                + "'. Render the profiles as separate reports.");
            }
        }
    }

    static List<EnvRow> environment(List<ResultRow> rows) {
        Map<String, EnvRow> env = new LinkedHashMap<>();
        for (ResultRow r : rows) {
            env.computeIfAbsent(r.provider(), p -> new EnvRow(
                    r.provider(), r.region(), r.comparisonRegion(), r.transportProfile(),
                    r.endpointRttMs(), r.hostLabel(), r.jdk(),
                    r.billingMode(), r.provisionedCapacity(), r.sdkVersion()));
        }
        return new ArrayList<>(env.values());
    }

    private static final class Group {
        final String provider;
        final String operation;
        final String workload;
        final String scenario;
        final String variant;
        final int threads;
        final int docSize;
        final Integer pageSize;
        final List<Double> latencies = new ArrayList<>();
        final List<Double> costs = new ArrayList<>();
        final java.util.Set<String> runIds = new java.util.HashSet<>();
        final Map<String, RunWindow> runWindows = new LinkedHashMap<>();
        int count;
        int success;
        int errors;
        int throttled;
        int retryCountTotal;
        boolean hasRetryData;
        double latencySum;
        double costSum;
        String costUnit;
        String capacityLimitUnit;
        Double capacityLimitValue;
        double targetOpsPerSecSum;
        int targetOpsPerSecCount;
        Double endpointRttMs;

        Group(ResultRow r) {
            this.provider = r.provider();
            this.operation = r.operation();
            this.workload = workloadOrDefault(r.workload(), r.operation());
            this.scenario = r.scenario();
            this.variant = Scenarios.variant(r.notes());
            this.threads = r.threads();
            this.docSize = r.docSizeBytes();
            this.pageSize = r.pageSize();
            this.costUnit = r.costUnit();
            this.capacityLimitUnit = r.capacityLimitUnit();
            this.capacityLimitValue = r.capacityLimitValue();
            this.endpointRttMs = r.endpointRttMs();
        }

        void add(ResultRow r) {
            runIds.add(r.runId());
            count++;
            if (r.success()) {
                success++;
            } else {
                errors++;
            }
            if ("THROTTLED".equalsIgnoreCase(r.errorCategory())) {
                throttled++;
            }
            latencies.add(r.latencyMs());
            latencySum += r.latencyMs();
            if (r.costValue() != null) {
                costs.add(r.costValue());
                costSum += r.costValue();
            }
            if (r.retryCount() != null) {
                hasRetryData = true;
                retryCountTotal += r.retryCount();
            }
            if ((costUnit == null || costUnit.isBlank()) && r.costUnit() != null && !r.costUnit().isBlank()) {
                costUnit = r.costUnit();
            }
            if ((capacityLimitUnit == null || capacityLimitUnit.isBlank())
                    && r.capacityLimitUnit() != null && !r.capacityLimitUnit().isBlank()) {
                capacityLimitUnit = r.capacityLimitUnit();
            }
            if (capacityLimitValue == null && r.capacityLimitValue() != null) {
                capacityLimitValue = r.capacityLimitValue();
            }
            if (endpointRttMs == null && r.endpointRttMs() != null) {
                endpointRttMs = r.endpointRttMs();
            }
            if (r.targetOpsPerSec() != null) {
                targetOpsPerSecSum += r.targetOpsPerSec();
                targetOpsPerSecCount++;
            }
            runWindows.computeIfAbsent(r.runId(), ignored -> new RunWindow()).add(r);
        }

        StatRow toStatRow() {
            List<Double> lat = new ArrayList<>(latencies);
            Collections.sort(lat);
            double mean = lat.isEmpty() ? 0.0 : latencySum / lat.size();
            double sd = 0.0;
            if (lat.size() > 1) {
                double ss = 0.0;
                for (double v : lat) {
                    double d = v - mean;
                    ss += d * d;
                }
                sd = Math.sqrt(ss / (lat.size() - 1));
            }

            double totalOfferedWindowSec = 0.0;
            double totalAchievedWindowSec = 0.0;
            boolean hasTimelineData = false;
            for (RunWindow window : runWindows.values()) {
                totalOfferedWindowSec += window.offeredWindowSec();
                totalAchievedWindowSec += window.achievedWindowSec();
                hasTimelineData = hasTimelineData || window.hasTimelineData();
            }
            double throughput;
            if (hasTimelineData && totalAchievedWindowSec > 0.0) {
                throughput = success / totalAchievedWindowSec;
            } else {
                int th = Math.max(1, threads);
                double wallSec = (latencySum / 1000.0) / th;
                throughput = wallSec > 0 ? success / wallSec : 0.0;
            }
            double offeredOpsSec = 0.0;
            if (hasTimelineData && totalOfferedWindowSec > 0.0) {
                offeredOpsSec = count / totalOfferedWindowSec;
            } else {
                offeredOpsSec = throughput;
            }
            Double targetOpsPerSec = targetOpsPerSecCount == 0
                    ? null : targetOpsPerSecSum / targetOpsPerSecCount;
            Double costMean = costs.isEmpty() ? null : costs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            Double costP99 = costs.isEmpty() ? null : percentile(sorted(costs), 99);
            Double consumedUnitsPerSec = costSum > 0.0 && totalAchievedWindowSec > 0.0
                    ? costSum / totalAchievedWindowSec : null;
            Double capacityUtilizationPct = comparableUnits(costUnit, capacityLimitUnit)
                    && consumedUnitsPerSec != null && capacityLimitValue != null && capacityLimitValue > 0.0
                    ? (consumedUnitsPerSec / capacityLimitValue) * 100.0
                    : null;
            double throttledRate = count > 0 ? (double) throttled / count : 0.0;
            Double retryMean = hasRetryData && count > 0 ? (double) retryCountTotal / count : null;
            double errorRate = count > 0 ? (double) errors / count : 0.0;
            double achievedOfferedRatio = offeredOpsSec > 0.0 ? throughput / offeredOpsSec : 0.0;
            Double serviceP50 = serviceTime(percentile(lat, 50), endpointRttMs);
            Double serviceP99 = serviceTime(percentile(lat, 99), endpointRttMs);
            return new StatRow(provider, operation, workload, scenario, variant, threads, docSize, pageSize,
                    runIds.size(), count, success,
                    percentile(lat, 50), percentile(lat, 90), percentile(lat, 99),
                    lat.isEmpty() ? 0.0 : lat.get(lat.size() - 1), mean, sd,
                    endpointRttMs, serviceP50, serviceP99,
                    throughput, targetOpsPerSec, offeredOpsSec, achievedOfferedRatio,
                    costUnit == null ? "" : costUnit, costMean, costP99, consumedUnitsPerSec,
                    capacityLimitUnit == null ? "" : capacityLimitUnit, capacityLimitValue, capacityUtilizationPct,
                    throttled, throttledRate,
                    hasRetryData ? retryCountTotal : null, retryMean,
                    errorRate);
        }
    }

    private static final class RunWindow {
        private double maxStartOffsetMs;
        private double maxEndOffsetMs;
        private boolean hasTimelineData;

        void add(ResultRow row) {
            maxStartOffsetMs = Math.max(maxStartOffsetMs, row.startOffsetMs());
            maxEndOffsetMs = Math.max(maxEndOffsetMs, row.endOffsetMs());
            hasTimelineData = hasTimelineData || row.startOffsetMs() > 0.0 || row.endOffsetMs() > 0.0;
        }

        double offeredWindowSec() {
            if (!hasTimelineData) {
                return 0.0;
            }
            if (maxStartOffsetMs > 0.0) {
                return maxStartOffsetMs / 1000.0;
            }
            return maxEndOffsetMs / 1000.0;
        }

        double achievedWindowSec() {
            if (!hasTimelineData) {
                return 0.0;
            }
            return maxEndOffsetMs / 1000.0;
        }

        boolean hasTimelineData() {
            return hasTimelineData;
        }
    }

    /**
     * Latency minus the measured endpoint round-trip time: the portion of the
     * wall-clock latency the service itself is responsible for. Returns
     * {@code null} when no RTT baseline was captured, and clamps at zero so a
     * sub-RTT sample never reports negative service time.
     */
    static Double serviceTime(double latencyMs, Double endpointRttMs) {
        if (endpointRttMs == null) {
            return null;
        }
        return Math.max(0.0, latencyMs - endpointRttMs);
    }

    static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) {
            return 0.0;
        }
        if (n == 1) {
            return sorted.get(0);
        }
        double k = (n - 1) * (p / 100.0);
        int lo = (int) Math.floor(k);
        int hi = (k > lo) ? lo + 1 : lo;
        if (lo == hi) {
            return sorted.get(lo);
        }
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (k - lo);
    }

    private static List<Double> sorted(List<Double> values) {
        List<Double> out = new ArrayList<>(values);
        Collections.sort(out);
        return out;
    }

    private static boolean comparableUnits(String costUnit, String capacityLimitUnit) {
        if (costUnit == null || costUnit.isBlank() || capacityLimitUnit == null || capacityLimitUnit.isBlank()) {
            return false;
        }
        return capacityLimitUnit.startsWith(costUnit);
    }

    private static Map<String, Integer> headerIndex(String header) {
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> names = parseCsvLine(header);
        for (int i = 0; i < names.size(); i++) {
            col.put(names.get(i).trim(), i);
        }
        return col;
    }

    private static String get(List<String> fields, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= fields.size()) {
            return "";
        }
        return fields.get(idx);
    }

    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else if (c != '\r') {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static String workloadOrDefault(String workload, String operation) {
        if (workload != null && !workload.isBlank()) {
            return workload;
        }
        if ("query".equals(operation)) {
            return "query";
        }
        if ("readChanges".equals(operation)) {
            return "changefeed";
        }
        return "mixed";
    }

    private static int intOr(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double doubleOr(String s, double def) {
        try {
            return s == null || s.isBlank() ? def : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Double doubleOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
