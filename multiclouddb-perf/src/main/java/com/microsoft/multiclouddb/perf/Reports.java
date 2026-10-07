// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared helpers for the Markdown and HTML report renderers. */
final class Reports {

    /** Canonical provider display order; any extra providers are appended in first-seen order. */
    static final List<String> CANONICAL = List.of("cosmos", "dynamo", "spanner");

    private Reports() {
    }

    /** Providers present in the stats, canonical ones first. */
    static List<String> providerOrder(List<StatRow> stats) {
        Set<String> present = new LinkedHashSet<>();
        for (StatRow s : stats) {
            present.add(s.provider());
        }
        List<String> order = new ArrayList<>();
        for (String p : CANONICAL) {
            if (present.remove(p)) {
                order.add(p);
            }
        }
        order.addAll(present);
        return order;
    }

    /** Compact number formatting: whole numbers for large values, more precision for small. */
    static String num(double x) {
        if (x >= 100) {
            return String.format(Locale.ROOT, "%.0f", x);
        }
        if (x >= 10) {
            return String.format(Locale.ROOT, "%.1f", x);
        }
        return String.format(Locale.ROOT, "%.2f", x);
    }

    static String numOrDash(Double x) {
        return x == null ? "—" : num(x);
    }

    static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0);
    }

    static String pctOrDash(Double ratio) {
        return ratio == null ? "—" : String.format(Locale.ROOT, "%.2f%%", ratio);
    }

    /**
     * Fraction of the target throughput a row must actually sustain to be a usable measurement.
     * <p>
     * Rows that hold their offered load land within a fraction of a percent of target; rows that
     * hit a capacity ceiling fall far below it. Anything in between is noise, so the bar sits
     * where no healthy row has ever landed.
     */
    static final double MIN_ATTAINED_TARGET_RATIO = 0.95;

    static String validity(StatRow row, double invalidThrottleRate) {
        String reason = invalidReason(row, invalidThrottleRate);
        return reason == null ? "valid" : "invalid (" + reason + ")";
    }

    /**
     * True when a row is a usable basis for comparison.
     * <p>
     * Every consumer of validity goes through here. The per-provider tables and the migration
     * parity verdict must agree about which rows are usable, or the report contradicts itself:
     * a row printed as {@code invalid} in one section could otherwise carry a passing verdict
     * in another.
     */
    static boolean valid(StatRow row, double invalidThrottleRate) {
        return invalidReason(row, invalidThrottleRate) == null;
    }

    /**
     * Why a row cannot be compared, or {@code null} when it can.
     * <p>
     * Two independent things can invalidate a measurement, and the report has to name which one
     * so a reader is not left staring at a row marked invalid with 0.000% throttling.
     * <ul>
     *   <li><b>throttled</b> — the provider rejected operations outright. Only providers that
     *       surface rejections to the caller ever trip this.</li>
     *   <li><b>under target</b> — the run never sustained the load it claims to measure. This is
     *       the provider-neutral check, and it exists because the throttled count is <em>not</em>
     *       comparable across providers: a client SDK that retries a rejection internally and
     *       eventually succeeds reports zero throttled operations while silently running at a
     *       capacity ceiling, whereas one that surfaces the rejection is marked invalid for the
     *       same underlying condition. Judging both by whether they held the offered load asks
     *       the same question of every provider regardless of where its retries happen.</li>
     * </ul>
     */
    static String invalidReason(StatRow row, double invalidThrottleRate) {
        if (row.throttledRate() > invalidThrottleRate) {
            return "throttled";
        }
        if (belowTarget(row)) {
            return "under target";
        }
        return null;
    }

    /**
     * True when a paced run failed to reach its target throughput. Max-throughput sweeps set no
     * target — there is nothing to fall short of — so they are never judged by this rule.
     */
    private static boolean belowTarget(StatRow row) {
        Double target = row.targetOpsPerSec();
        if (target == null || target <= 0.0) {
            return false;
        }
        return row.throughputOpsSec() < target * MIN_ATTAINED_TARGET_RATIO;
    }

    /** Short human explanation of what a given operation row measures. */
    static String opMeasures(String op) {
        if (op == null) {
            return "";
        }
        switch (op) {
            case "create": return "Insert one new item by key";
            case "read":   return "Point-read one item by its key";
            case "update": return "Replace one existing item by key";
            case "upsert": return "Insert-or-replace one item by key";
            case "delete": return "Delete one item by key";
            case "query":  return "Run a filtered query, fetch one page";
            case "readChanges": return "Read one change-feed page";
            default:       return op;
        }
    }

    /** HTML entity-escaping for text nodes / attribute values. */
    static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
