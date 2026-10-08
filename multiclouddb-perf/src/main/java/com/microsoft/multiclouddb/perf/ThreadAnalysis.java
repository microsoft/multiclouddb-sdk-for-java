// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Concurrency analysis for perf goal 3 — <em>"when applications are migrated, they do not need to
 * increase the number of threads they run."</em>
 *
 * <p>Two views are produced from the pooled {@link StatRow}s:
 * <ul>
 *   <li><b>Migration parity</b> — at each matched thread count, does every target provider keep up
 *       with the {@code baseline} (the system being migrated <em>from</em>)? A target passes when it
 *       reaches at least the baseline throughput and no worse than the baseline p99 latency at the
 *       <em>same</em> thread count. If it needs more threads to catch up, that is a migration
 *       regression and the row is flagged.</li>
 *   <li><b>Thread-scaling</b> — how each provider's throughput grows as threads increase, so you can
 *       see whether a workload has already saturated (adding threads would not help) at the thread
 *       count an application currently runs.</li>
 * </ul>
 */
final class ThreadAnalysis {

    /** Parity tolerance: a target within 10% of baseline throughput/latency is treated as parity. */
    static final double TOLERANCE = 0.10;

    private ThreadAnalysis() {
    }

    /** One matched-concurrency comparison of every target provider against the baseline. */
    record ParityRow(String operation, String workload, String scenario, String variant, int threads,
                     String baseline, double baseTput, double baseP99,
                     Map<String, Double> targetTput, Map<String, Double> targetP99,
                     boolean pass, boolean measurementValid) {
    }

    /** Throughput of one provider/operation across the swept thread levels. */
    record ScalingRow(String provider, String operation, String workload, String scenario,
                      String variant,
                      Map<Integer, Double> tputByThreads, double scalingFactor,
                      int peakThreads) {
    }

    /** Distinct thread levels present in the data, ascending. */
    static List<Integer> threadLevels(List<StatRow> stats) {
        TreeSet<Integer> levels = new TreeSet<>();
        for (StatRow s : stats) {
            levels.add(s.threads());
        }
        return new ArrayList<>(levels);
    }

    /** True when the sweep covers more than one thread level (so scaling is meaningful). */
    static boolean multiThread(List<StatRow> stats) {
        return threadLevels(stats).size() > 1;
    }

    /**
     * Resolves the migration-source baseline. Honours an explicit request when that provider is
     * present; otherwise defaults to the first non-{@code cosmos} provider (the usual migration
     * source in these goals), falling back to the first provider overall.
     */
    static String resolveBaseline(String requested, List<String> providers) {
        if (requested != null && !requested.isBlank() && providers.contains(requested)) {
            return requested;
        }
        for (String p : providers) {
            if (!p.equals("cosmos")) {
                return p;
            }
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    /**
     * Matched-concurrency parity of every target provider against the baseline.
     * <p>
     * A comparison is only reported as a verdict when both sides are valid measurements.
     * A throttled baseline collapses to a throughput any target beats trivially — a provider
     * held to 2.5 ops/s by throttling would hand every target a passing verdict — so rows
     * whose baseline or target exceeds {@code invalidThrottleRate} are marked
     * {@code measurementValid == false} and rendered as unmeasured rather than as a pass.
     */
    static List<ParityRow> parity(List<StatRow> stats, List<String> providers, String baseline,
                                  double invalidThrottleRate) {
        if (baseline == null || providers.size() < 2) {
            return List.of();
        }
        // key = operation \u0001 scenario \u0001 query scope \u0001 threads -> provider -> row.
        // Query scope belongs in the key: comparing a single-partition baseline against a
        // cross-partition target would report a migration regression that does not exist.
        Map<String, Map<String, StatRow>> grouped = new LinkedHashMap<>();
        for (StatRow s : stats) {
            String key = s.operation() + "\u0001" + s.workload() + "\u0001" + s.scenario()
                    + "\u0001" + s.variant() + "\u0001" + s.threads();
            grouped.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(s.provider(), s);
        }
        List<ParityRow> out = new ArrayList<>();
        for (Map<String, StatRow> byProvider : grouped.values()) {
            StatRow base = byProvider.get(baseline);
            if (base == null || base.throughputOpsSec() <= 0) {
                continue;
            }
            Map<String, Double> tgtTput = new LinkedHashMap<>();
            Map<String, Double> tgtP99 = new LinkedHashMap<>();
            boolean pass = true;
            boolean anyTarget = false;
            boolean measurementValid = Reports.valid(base, invalidThrottleRate);
            for (String p : providers) {
                if (p.equals(baseline)) {
                    continue;
                }
                StatRow t = byProvider.get(p);
                if (t == null) {
                    continue;
                }
                anyTarget = true;
                tgtTput.put(p, t.throughputOpsSec());
                tgtP99.put(p, t.p99());
                boolean tputOk = t.throughputOpsSec() >= base.throughputOpsSec() * (1 - TOLERANCE);
                boolean latOk = base.p99() <= 0 || t.p99() <= base.p99() * (1 + TOLERANCE);
                pass = pass && tputOk && latOk;
                measurementValid = measurementValid && Reports.valid(t, invalidThrottleRate);
            }
            if (!anyTarget) {
                continue;
            }
            out.add(new ParityRow(base.operation(), base.workload(), base.scenario(), base.variant(),
                    base.threads(),
                    baseline, base.throughputOpsSec(), base.p99(), tgtTput, tgtP99,
                    pass, measurementValid));
        }
        return out;
    }

    static List<ScalingRow> scaling(List<StatRow> stats, List<String> providers) {
        // key = provider \u0001 operation \u0001 scenario -> threads -> throughput
        Map<String, Map<Integer, Double>> grouped = new LinkedHashMap<>();
        Map<String, StatRow> any = new LinkedHashMap<>();
        for (StatRow s : stats) {
            String key = s.provider() + "\u0001" + s.operation() + "\u0001" + s.workload()
                    + "\u0001" + s.scenario() + "\u0001" + s.variant();
            grouped.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(s.threads(), s.throughputOpsSec());
            any.putIfAbsent(key, s);
        }
        List<ScalingRow> out = new ArrayList<>();
        for (String p : providers) {
            for (Map.Entry<String, StatRow> e : any.entrySet()) {
                StatRow s = e.getValue();
                if (!s.provider().equals(p)) {
                    continue;
                }
                Map<Integer, Double> byThreads = grouped.get(e.getKey());
                if (byThreads == null || byThreads.size() < 2) {
                    continue;
                }
                TreeSet<Integer> levels = new TreeSet<>(byThreads.keySet());
                double lowTput = byThreads.get(levels.first());
                double peakTput = 0;
                int peakThreads = levels.first();
                for (Map.Entry<Integer, Double> te : byThreads.entrySet()) {
                    if (te.getValue() > peakTput) {
                        peakTput = te.getValue();
                        peakThreads = te.getKey();
                    }
                }
                double factor = lowTput > 0 ? peakTput / lowTput : 0;
                out.add(new ScalingRow(p, s.operation(), s.workload(), s.scenario(), s.variant(),
                        new LinkedHashMap<>(byThreads), factor, peakThreads));
            }
        }
        return out;
    }
}
