// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human-readable descriptions of what each scenario exercises, shared by the Markdown and
 * HTML renderers so the two reports can never describe the same run differently.
 *
 * <p>Descriptions here must track {@link ScenarioRunner}. A report that misdescribes its own
 * method is worse than one that omits the description, because it invites conclusions the
 * measurements do not support.
 */
final class Scenarios {

    static final String SCOPED = "scoped";
    static final String UNSCOPED = "unscoped";

    private Scenarios() {
    }

    /**
     * Extracts the query scope recorded in a raw row's {@code notes} column, or {@code null}
     * when the row is not a query. {@code notes} also carries failure text, so only the two
     * known scope tokens are accepted.
     */
    static String variant(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.trim();
        return SCOPED.equals(trimmed) || UNSCOPED.equals(trimmed) ? trimmed : null;
    }

    /** Short label for the partition scope a measured group addressed. */
    static String scopeLabel(String variant, String operation) {
        if (SCOPED.equals(variant)) {
            return "single-partition";
        }
        if (UNSCOPED.equals(variant)) {
            return "cross-partition";
        }
        if ("readChanges".equals(operation)) {
            return "all partitions";
        }
        if ("create".equals(operation)) {
            return "single item, unique partition key";
        }
        return "single item, shared partition key";
    }

    /** Compact query scope for the per-row tables; a dash for non-query rows. */
    static String scopeColumn(String variant) {
        if (SCOPED.equals(variant)) {
            return "single-partition";
        }
        if (UNSCOPED.equals(variant)) {
            return "cross-partition";
        }
        return "\u2014";
    }

    /**
     * Effective page size for a scenario, derived from the {@code --page-size} baseline.
     * <p>
     * Each query scenario varies exactly one dimension so a difference between them has a
     * single cause. S6 shrinks the page in proportion to its larger documents, keeping bytes
     * per page close to the baseline: that isolates per-item cost from per-byte cost, and
     * stops the scenario from consuming several times the provisioned read capacity.
     */
    static int pageSizeFor(String scenario, int basePageSize) {
        return switch (scenario) {
            case "S5" -> Math.max(1, basePageSize / 4);
            case "S6" -> Math.max(1, basePageSize / 8);
            default -> basePageSize;
        };
    }

    /**
     * Effective document size for a scenario, derived from the {@code --doc-size} baseline.
     * <p>
     * The point scenarios form an item-size ladder over one profile, so a difference between
     * them has a single cause: S1 measures the baseline, S2 the same operations at eight times
     * the baseline — S6's item size, so a point cost and a query cost can be read at the same
     * document size — and S3 at sixty-four times, the large-document case customers actually
     * store. Only document size varies across the three.
     */
    static int docSizeFor(String scenario, int baseDocSize) {
        return switch (scenario) {
            case "S2", "S6" -> baseDocSize * 8;
            case "S3" -> baseDocSize * 64;
            default -> baseDocSize;
        };
    }

    /** What the scenario is for, in one sentence. */
    static String purpose(String scenario) {
        return switch (scenario) {
            case "S2" -> "Item-size sensitivity for point operations: the S1 profile over "
                    + "documents eight times the baseline size, which is also S6's item size, so "
                    + "point and query costs can be read at the same document size.";
            case "S3" -> "Large-document point operations: the S1 profile over documents "
                    + "sixty-four times the baseline size, covering the payloads customers store "
                    + "rather than the small items synthetic benchmarks favour.";
            case "S4" -> "Query latency with the partition key supplied and withheld, so "
                    + "single-partition and cross-partition (fan-out) costs can be told apart.";
            case "S5" -> "Page-size sensitivity: the same cross-partition query at a quarter of "
                    + "the baseline page size, isolating per-request overhead from per-item cost.";
            case "S6" -> "Item-size sensitivity: the same cross-partition query over documents "
                    + "eight times the baseline size, with the page shrunk to match so bytes per "
                    + "page stay close to the baseline and only item size varies.";
            case "S7" -> "Change-feed read throughput from the tip of each physical partition.";
            default -> "Point-operation latency and throughput on individually addressed keys.";
        };
    }

    /** How the scenario is exercised, in one sentence. */
    static String method(String scenario, String workload) {
        if (isQuery(scenario)) {
            return "Seeds max(2 x page size, 200) documents under one partition key, then repeats "
                    + "`category = @cat` at this scenario's page size, reading the first page only. "
                    + "The effective document and page sizes are in the table above.";
        }
        if ("S7".equals(scenario)) {
            return "Seeds documents, opens one cursor per physical partition at the tip, then "
                    + "reads changes round-robin across cursors.";
        }
        return switch (workload == null ? "" : workload) {
            case "read" -> "Pre-seeds keys sharing one partition key, then reads them back "
                    + "individually. Seeding and cleanup are not measured.";
            case "write" -> "`create` writes a fresh item per iteration under its own unique "
                    + "partition key; `update`, `upsert` and `delete` act on pre-seeded keys "
                    + "sharing one partition key.";
            case "mixed" -> "Runs create, read, update and delete phases in sequence over the "
                    + "same key set.";
            default -> "Runs the point-operation phases for the selected workload.";
        };
    }

    static boolean isQuery(String scenario) {
        return "S4".equals(scenario) || "S5".equals(scenario) || "S6".equals(scenario);
    }

    /** Method notes that apply to every run, rendered as a bullet list. */
    static List<String> methodology() {
        return List.of(
                "Each operation is timed individually. Warm-up iterations run first and are "
                        + "excluded from every statistic.",
                "Offered load is paced per thread. **Offered ops/s** counts attempts, "
                        + "**achieved ops/s** counts successes, so the ratio exposes a provider "
                        + "that accepted work it could not complete.",
                "Seeding and cleanup writes are never measured; only the named operation is.",
                "Cost is read from provider diagnostics (Cosmos RU, DynamoDB consumed RCU/WCU) "
                        + "and is never converted between providers.",
                "Endpoint RTT is probed once per provider at run start (median of 7 TCP "
                        + "handshakes). **Service time** is latency minus that RTT, floored at "
                        + "zero, so a provider is not penalised for network distance. Because the "
                        + "probe is a single sample, service times of a few milliseconds are "
                        + "within its error bar.",
                "A row is invalid when its throttled-operation rate exceeds the threshold in the "
                        + "header.");
    }

    /** Renders the document body size, distinguishing "no body" from a real size. */
    static String docSizeLabel(int docSizeBytes) {
        return docSizeBytes <= 0 ? "no body" : docSizeBytes + " B";
    }

    /** Explains what the size and scope columns mean, so a zero is not read as a defect. */
    static String columnNote() {
        return "Doc size is the document body the operation writes: `read` and `delete` send no "
                + "body, and query rows show the size of the documents seeded for them to match. "
                + "Partition scope describes how many partitions the operation addresses.";
    }

    /**
     * Provider-specific ordering behaviour that materially affects query timings, so query
     * results are not read as a like-for-like engine comparison.
     */
    static String queryOrderingNote() {
        return "Query rows are affected by portable-ordering guarantees: the Cosmos adapter "
                + "appends `ORDER BY c.id ASC` when a query has no explicit ordering, so ordering "
                + "is performed server-side, while the DynamoDB adapter sorts the returned page "
                + "client-side. Both are required to keep result order identical across "
                + "providers, but they place the cost in different places.";
    }

    /**
     * One measurement profile: a scenario/workload/operation/query-scope combination that
     * actually ran. Operation is part of the identity because partition scope varies by
     * operation — {@code create} writes a unique partition key per item while the other point
     * operations share one — so a profile spanning operations could not describe its own scope.
     */
    static final class Profile {
        private final String scenario;
        private final String workload;
        private final String operation;
        private final String variant;
        private final int threads;
        private final int docSize;
        private final Integer pageSize;
        private int count;

        private Profile(StatRow row) {
            this.scenario = row.scenario();
            this.workload = row.workload();
            this.operation = row.operation();
            this.variant = row.variant();
            this.threads = row.threads();
            this.docSize = row.docSizeBytes();
            this.pageSize = row.pageSize();
        }

        String scenario() {
            return scenario;
        }

        String workload() {
            return workload;
        }

        String operation() {
            return operation;
        }

        String variant() {
            return variant;
        }

        int threads() {
            return threads;
        }

        /** Document body written by the operation; {@code 0} when it writes none. */
        int docSize() {
            return docSize;
        }

        Integer pageSize() {
            return pageSize;
        }

        int count() {
            return count;
        }

        String scope() {
            return scopeLabel(variant, operation);
        }
    }

    /** Collapses measured rows into the distinct profiles the run actually exercised. */
    static Map<String, Profile> profiles(List<StatRow> stats) {
        Map<String, Profile> profiles = new LinkedHashMap<>();
        for (StatRow row : stats) {
            String key = String.join("\u0001", row.scenario(), row.workload(), row.operation(),
                    String.valueOf(row.variant()), Integer.toString(row.threads()));
            profiles.computeIfAbsent(key, ignored -> new Profile(row)).count += row.count();
        }
        return profiles;
    }

    /**
     * One purpose-and-method sentence per scenario <em>and workload</em>. A scenario can run
     * more than one workload, and the method differs between them, so keying on the scenario
     * alone would describe only whichever workload happened to be measured first.
     */
    static Map<String, String> purposes(Map<String, Profile> profiles) {
        Map<String, String> described = new LinkedHashMap<>();
        for (Profile profile : profiles.values()) {
            String label = profile.scenario() + " \u00b7 " + profile.workload();
            described.computeIfAbsent(label, ignored ->
                    purpose(profile.scenario()) + " " + method(profile.scenario(), profile.workload()));
        }
        return described;
    }

    /**
     * Reports scenarios that ran byte-identical profiles, so equal numbers are read as a
     * repeated measurement rather than as agreement between different tests.
     */
    static String duplicateScenarioNote(Map<String, Profile> profiles) {
        Map<String, List<String>> bySignature = new LinkedHashMap<>();
        Map<String, StringBuilder> signatures = new LinkedHashMap<>();
        for (Profile profile : profiles.values()) {
            signatures.computeIfAbsent(profile.scenario(), ignored -> new StringBuilder())
                    .append(profile.workload()).append('|').append(profile.operation()).append('|')
                    .append(profile.variant()).append('|').append(profile.docSize()).append('|')
                    .append(profile.pageSize()).append('|').append(profile.threads()).append(';');
        }
        for (Map.Entry<String, StringBuilder> entry : signatures.entrySet()) {
            bySignature.computeIfAbsent(entry.getValue().toString(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        List<String> duplicates = new ArrayList<>();
        for (List<String> scenarios : bySignature.values()) {
            if (scenarios.size() > 1) {
                duplicates.add(String.join(", ", scenarios));
            }
        }
        if (duplicates.isEmpty()) {
            return null;
        }
        return "Identical profiles in this run: " + String.join("; ", duplicates)
                + ". These scenarios differ only by label here, so any spread between them is "
                + "run-to-run variance, not a difference in what was tested.";
    }
}
