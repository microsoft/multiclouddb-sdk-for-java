// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.microsoft.multiclouddb.e2e.ConfigLoader;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Single-JVM CLI entry point for the MANUAL, live-account performance harness.
 */
public final class PerfMain {

    private PerfMain() {
    }

    private record ProviderRunPlan(Path configPath, ConfigLoader.AppConfig cfg,
                                   String providerId, String database, String collection,
                                   String sdkVersion, String configuredRegion,
                                   String configuredComparisonRegion,
                                   MetadataProbe.Meta metadata) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return;
        }
        String command = args[0];
        Map<String, String> opt = parseOpts(Arrays.copyOfRange(args, 1, args.length));
        switch (command) {
            case "run" -> run(opt);
            case "report" -> report(opt);
            case "cleanup" -> cleanup(opt);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(2);
            }
        }
    }

    // ── run ──────────────────────────────────────────────────────────────────

    private static void run(Map<String, String> opt) throws Exception {
        assertNotCi();
        Path configDir = Path.of(opt.getOrDefault("config-dir", "multiclouddb-perf/config"));
        Path outDir = Path.of(opt.getOrDefault("out", "multiclouddb-perf/results/raw"));
        Path reportDir = Path.of(opt.getOrDefault("reports", "multiclouddb-perf/results/reports"));
        List<String> providers = splitCsv(opt.getOrDefault("providers", "cosmos,dynamo,spanner"));
        String workload = workloadOpt(opt.get("workload"));
        List<String> scenarios = resolveScenarios(opt, workload);
        validateScenarioWorkload(scenarios, workload);
        List<Integer> threadLevels = splitCsv(opt.getOrDefault("threads", "8")).stream()
                .map(Integer::parseInt)
                .toList();
        int warmup = intOpt(opt, "warmup", 50);
        int iterations = intOpt(opt, "iterations", 500);
        int docSize = intOpt(opt, "doc-size", 1024);
        int pageSize = intOpt(opt, "page-size", 100);
        validateEffectiveDocSizes(scenarios, docSize);
        int repeat = Math.max(1, intOpt(opt, "repeat", 1));
        int cliCosmosRu = intOpt(opt, "cosmos-ru", 0);
        int cliDynamoRcu = optionalPositiveInt(opt, "dynamo-rcu");
        int cliDynamoWcu = optionalPositiveInt(opt, "dynamo-wcu");
        validateDynamoCapacityArgs(cliDynamoRcu, cliDynamoWcu);
        boolean enableDynamoStreams = opt.containsKey("enable-dynamo-streams");
        int splitWaitSeconds = intOpt(opt, "split-wait-seconds", 0);
        double cliTargetOpsPerSec = doubleOpt(opt, "target-ops-per-sec", 0.0);
        if (cliTargetOpsPerSec < 0.0) {
            throw new IllegalArgumentException("--target-ops-per-sec must be >= 0");
        }
        double invalidThrottleRate = throttleThreshold(opt);
        RegionFairness.Policy regionPolicy = RegionFairness.Policy.parse(opt.get("region-policy"));

        String batchId = opt.getOrDefault("title",
                Instant.now().toString().replace(":", "-").replaceAll("\\..*", "Z") + "-batch");
        String jdk = System.getProperty("java.vendor", "?") + " " + System.getProperty("java.version", "?");
        String host = hostname();

        List<ProviderRunPlan> plans = loadProviderPlans(configDir, providers);
        if (plans.isEmpty()) {
            System.out.println("No live configs found. Nothing to run.");
            return;
        }
        validateOfferedLoadTargets(plans, opt, cliTargetOpsPerSec);
        applyRegionPolicy(plans, regionPolicy);

        List<ResultRow> all = new ArrayList<>();
        int ran = 0;
        for (ProviderRunPlan plan : plans) {
            System.setProperty("multiclouddb.config", plan.configPath().toString());
            int cosmosRu = opt.containsKey("cosmos-ru")
                    ? cliCosmosRu
                    : positiveConfigInt(plan.cfg(), "multiclouddb.perf.cosmosRu");
            int dynamoRcu = opt.containsKey("dynamo-rcu")
                    ? cliDynamoRcu
                    : positiveConfigInt(plan.cfg(), "multiclouddb.perf.dynamoRcu");
            int dynamoWcu = opt.containsKey("dynamo-wcu")
                    ? cliDynamoWcu
                    : positiveConfigInt(plan.cfg(), "multiclouddb.perf.dynamoWcu");
            validateDynamoCapacityArgs(dynamoRcu, dynamoWcu);
            double targetOpsPerSec = effectiveTargetOpsPerSec(plan, opt, cliTargetOpsPerSec);
            Double endpointRttMs = NetworkBaseline.probeRttMs(plan.providerId(), plan.cfg());
            MetadataProbe.Meta meta = plan.metadata();
            String comparisonRegion = RegionFairness.effectiveComparisonRegion(
                    plan.configuredComparisonRegion(), meta.region(), plan.configuredRegion());
            Path csv = outDir.resolve(batchId + "-" + plan.providerId() + ".csv");
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(plan.cfg().sdk());
                 CsvResultWriter writer = new CsvResultWriter(csv)) {
                ResourceAddress address = new ResourceAddress(plan.database(), plan.collection());
                client.ensureDatabase(plan.database());
                client.ensureContainer(address);

                if (cosmosRu > 0 && "cosmos".equals(plan.providerId())) {
                    ProvisioningAdmin.ensureCosmosThroughput(plan.cfg(), plan.database(), plan.collection(), cosmosRu);
                    meta = reprobe(plan, meta);
                    if (splitWaitSeconds > 0) {
                        System.out.printf(Locale.ROOT,
                                "-- waiting %ds for the Cosmos partition split to complete ...%n",
                                splitWaitSeconds);
                        try {
                            Thread.sleep(splitWaitSeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        meta = reprobe(plan, meta);
                    }
                }
                if (dynamoRcu > 0 && "dynamo".equals(plan.providerId())) {
                    ProvisioningAdmin.ensureDynamoProvisionedCapacity(
                            plan.cfg(), plan.database(), plan.collection(), dynamoRcu, dynamoWcu);
                    meta = reprobe(plan, meta);
                }
                if (enableDynamoStreams && "dynamo".equals(plan.providerId())) {
                    ProvisioningAdmin.ensureDynamoStreams(plan.cfg(), plan.database(), plan.collection());
                    meta = reprobe(plan, meta);
                }
                primeCaches(client, address);

                comparisonRegion = RegionFairness.effectiveComparisonRegion(
                        plan.configuredComparisonRegion(), meta.region(), plan.configuredRegion());
                System.out.printf(Locale.ROOT,
                        "-- %s metadata: region=%s comparison=%s billing=%s provisioned=%s%n",
                        plan.providerId(), meta.region(), comparisonRegion, meta.billingMode(),
                        meta.provisionedCapacity().isBlank() ? "(none)" : meta.provisionedCapacity());

                Consumer<ResultRow> sink = r -> {
                    writer.write(r);
                    synchronized (all) {
                        all.add(r);
                    }
                };

                for (int rep = 1; rep <= repeat; rep++) {
                    for (String scenario : scenarios) {
                        for (String scenarioWorkload : scenarioWorkloads(scenario, workload)) {
                            for (int threads : threadLevels) {
                                String runId = batchId + "-" + plan.providerId() + "-" + scenario
                                        + ("all".equals(workload) ? "-" + scenarioWorkload : "")
                                        + "-" + threads + "t"
                                        + (repeat > 1 ? "-rep" + rep : "");
                                RunContext ctx = new RunContext(runId, plan.providerId(), scenario, threads,
                                        warmup, iterations,
                                        Scenarios.docSizeFor(scenario, docSize),
                                        Scenarios.pageSizeFor(scenario, pageSize),
                                        meta.region(), comparisonRegion, transportProfile(plan.providerId(), plan.cfg()),
                                        endpointRttMs, host, jdk,
                                        plan.sdkVersion(), meta.billingMode(), meta.provisionedCapacity(),
                                        meta.sharedCapacityLimit(), meta.readCapacityLimit(), meta.writeCapacityLimit(),
                                        targetOpsPerSec > 0.0 ? targetOpsPerSec : null,
                                        scenarioWorkload);
                                System.out.printf(Locale.ROOT,
                                        "== %s / %s / %d threads / workload=%s (warmup=%d iter=%d%s)%s ==%n",
                                        plan.providerId(), scenario, threads,
                                        scenarioWorkloadLabel(scenario, ctx.pointWorkload()),
                                        warmup, iterations,
                                        targetOpsPerSec > 0.0
                                                ? String.format(Locale.ROOT, ", target=%.2f ops/s", targetOpsPerSec)
                                                : "",
                                        repeat > 1 ? " [repeat " + rep + "/" + repeat + "]" : "");
                                try {
                                    new ScenarioRunner(client, address, sink, ctx).run();
                                    ran++;
                                } catch (RuntimeException scenarioFailure) {
                                    System.out.printf(Locale.ROOT,
                                            "!! %s / %s / %dt / workload=%s aborted: %s"
                                                    + " — continuing with next scenario%n",
                                            plan.providerId(), scenario, threads, scenarioWorkload, scenarioFailure);
                                }
                            }
                        }
                    }
                }
            }
            System.out.printf(Locale.ROOT, "-> %s raw rows written to %s%n", plan.providerId(), csv);
        }

        if (all.isEmpty()) {
            System.out.println("No results produced. Nothing to report.");
            return;
        }
        renderReports(all, reportDir, batchId, "in-memory results from this run",
                opt.get("baseline"), invalidThrottleRate);
        System.out.printf(Locale.ROOT, "== done == %d scenario-runs, %d raw rows.%n", ran, all.size());
    }

    // ── report (offline re-aggregation) ──────────────────────────────────────

    private static void report(Map<String, String> opt) {
        Path rawDir = Path.of(opt.getOrDefault("raw", "multiclouddb-perf/results/raw"));
        Path reportDir = Path.of(opt.getOrDefault("reports", "multiclouddb-perf/results/reports"));
        double invalidThrottleRate = throttleThreshold(opt);

        if (opt.containsKey("combined")) {
            String title = opt.getOrDefault("title",
                    Instant.now().toString().replace(":", "-").replaceAll("\\..*", "Z") + "-combined");
            List<ResultRow> rows = Statistics.readRawCsv(rawDir);
            if (rows.isEmpty()) {
                System.err.println("No raw CSV rows found under " + rawDir);
                System.exit(1);
            }
            renderReports(rows, reportDir, title, rawDir + " (all runs, pooled)",
                    opt.get("baseline"), invalidThrottleRate);
            return;
        }

        Map<String, List<ResultRow>> byBatch = Statistics.readRawByBatch(rawDir);
        if (byBatch.isEmpty()) {
            System.err.println("No raw CSV rows found under " + rawDir);
            System.exit(1);
        }
        String only = opt.get("run");
        int made = 0;
        for (Map.Entry<String, List<ResultRow>> e : byBatch.entrySet()) {
            if (only != null && !only.isBlank() && !e.getKey().contains(only)) {
                continue;
            }
            renderReports(e.getValue(), reportDir, e.getKey(), rawDir + " (run " + e.getKey() + ")",
                    opt.get("baseline"), invalidThrottleRate);
            made++;
        }
        if (made == 0) {
            System.err.println("No run matching --run '" + only + "' under " + rawDir
                    + ". Available runs: " + String.join(", ", byBatch.keySet()));
            System.exit(1);
        }
        System.out.printf(Locale.ROOT, "== done == %d per-run report(s) written to %s.%n", made, reportDir);
    }

    private static void renderReports(List<ResultRow> rows, Path reportDir, String title, String source,
                                      String baselineReq, double invalidThrottleRate) {
        List<StatRow> stats = Statistics.aggregate(rows);
        List<EnvRow> env = Statistics.environment(rows);
        String baseline = ThreadAnalysis.resolveBaseline(baselineReq, Reports.providerOrder(stats));
        ReportMeta meta = new ReportMeta(title,
                Instant.now().toString().replaceAll("\\..*", "Z"), source, baseline, invalidThrottleRate);
        Path md = MarkdownReport.write(stats, env, meta, reportDir);
        Path html = HtmlReport.write(stats, env, meta, reportDir);
        System.out.println("Pooled " + rows.size() + " raw rows into " + stats.size() + " group(s).");
        System.out.println("Markdown report: " + md);
        System.out.println("HTML report (charts, open in browser): " + html);
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    private static void cleanup(Map<String, String> opt) throws Exception {
        assertNotCi();
        boolean dryRun = opt.containsKey("dry-run");
        System.setProperty("perf.dryRun", Boolean.toString(dryRun));
        List<Path> configs = new ArrayList<>();
        if (opt.containsKey("config")) {
            configs.add(Path.of(opt.get("config")));
        } else {
            Path configDir = Path.of(opt.getOrDefault("config-dir", "multiclouddb-perf/config"));
            for (String provider : splitCsv(opt.getOrDefault("providers", "cosmos,dynamo,spanner"))) {
                Path p = configDir.resolve(provider + ".live.properties");
                if (Files.exists(p)) {
                    configs.add(p);
                }
            }
        }
        if (configs.isEmpty()) {
            System.err.println("No live config found to clean up.");
            System.exit(1);
        }
        for (Path cfg : configs) {
            System.setProperty("multiclouddb.config", cfg.toString());
            PerfCleanup.main(new String[0]);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static void assertNotCi() {
        boolean ci = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null
                || System.getenv("BUILD_ID") != null;
        if (ci && !"1".equals(System.getenv("PERF_ALLOW_CI"))) {
            System.err.println("Refusing to run live perf tests in a CI environment "
                    + "(CI/GITHUB_ACTIONS/BUILD_ID detected). These are manual, billable, live-account "
                    + "tests. Set PERF_ALLOW_CI=1 only if you truly intend this.");
            System.exit(3);
        }
    }

    private static void primeCaches(MulticloudDbClient client, ResourceAddress address) {
        client.query(address, QueryRequest.builder().maxPageSize(1).build());
        MulticloudDbKey warm = MulticloudDbKey.of("__warmup__", "__warmup__");
        client.upsert(address, warm, Map.of("id", "__warmup__", "category", "perf"));
        client.delete(address, warm);
    }

    private static List<ProviderRunPlan> loadProviderPlans(Path configDir, List<String> providers) throws Exception {
        List<ProviderRunPlan> plans = new ArrayList<>();
        for (String provider : providers) {
            Path cfgPath = configDir.resolve(provider + ".live.properties");
            if (!Files.exists(cfgPath)) {
                System.out.printf(Locale.ROOT, "!! Skipping %s — no live config at %s%n", provider, cfgPath);
                continue;
            }
            System.setProperty("multiclouddb.config", cfgPath.toString());
            ConfigLoader.AppConfig cfg = ConfigLoader.load(cfgPath.toString());
            String providerId = cfg.sdk().provider().id();
            String database = cfg.get("multiclouddb.database", "perfdb");
            String collection = cfg.get("multiclouddb.collection", "perf");
            String sdkVersion = cfg.get("multiclouddb.sdkVersion", "dev");
            String configuredRegion = cfg.get("multiclouddb.region",
                    cfg.get("multiclouddb.connection.region", "unknown"));
            String configuredComparisonRegion = cfg.get("multiclouddb.comparisonRegion", "");
            String cfgProvisioned = cfg.get("multiclouddb.provisionedCapacity", "");
            MetadataProbe.Meta meta = MetadataProbe.probe(providerId, cfg, database, collection,
                    configuredRegion, cfgProvisioned);
            plans.add(new ProviderRunPlan(cfgPath, cfg, providerId, database, collection,
                    sdkVersion, configuredRegion, configuredComparisonRegion, meta));
        }
        return plans;
    }

    private static void applyRegionPolicy(List<ProviderRunPlan> plans, RegionFairness.Policy policy) {
        List<RegionFairness.ProviderRegion> regions = new ArrayList<>();
        for (ProviderRunPlan plan : plans) {
            regions.add(new RegionFairness.ProviderRegion(
                    plan.providerId(),
                    plan.configuredRegion(),
                    plan.metadata().region(),
                    RegionFairness.effectiveComparisonRegion(
                            plan.configuredComparisonRegion(),
                            plan.metadata().region(),
                            plan.configuredRegion())));
        }
        RegionFairness.CheckResult result = RegionFairness.validate(regions, policy);
        for (String message : result.messages()) {
            String prefix = policy == RegionFairness.Policy.FAIL ? "!!" : "--";
            System.out.printf(Locale.ROOT, "%s region-policy %s%n", prefix, message);
        }
        if (result.failed()) {
            System.err.println("Aborting before measurements because --region-policy=fail detected a mismatch.");
            System.exit(2);
        }
    }

    private static MetadataProbe.Meta reprobe(ProviderRunPlan plan, MetadataProbe.Meta current) {
        MetadataProbe.Meta reprobed = MetadataProbe.probe(plan.providerId(), plan.cfg(), plan.database(),
                plan.collection(), plan.configuredRegion(), current.provisionedCapacity());
        System.out.printf(Locale.ROOT,
                "-- refreshed metadata: region=%s comparison=%s billing=%s provisioned=%s%n",
                reprobed.region(),
                RegionFairness.effectiveComparisonRegion(plan.configuredComparisonRegion(),
                        reprobed.region(), plan.configuredRegion()),
                reprobed.billingMode(),
                reprobed.provisionedCapacity().isBlank() ? "(none)" : reprobed.provisionedCapacity());
        return reprobed;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private static Map<String, String> parseOpts(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Expected --flag but got: " + a);
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opt.put(key, args[++i]);
            } else {
                opt.put(key, "");
            }
        }
        return opt;
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static List<String> resolveScenarios(Map<String, String> opt, String workload) {
        if (opt.containsKey("scenarios")) {
            return splitCsv(opt.get("scenarios"));
        }
        if ("query".equals(workload)) {
            return List.of("S4", "S5", "S6");
        }
        if ("all".equals(workload)) {
            return List.of("S1", "S2", "S3", "S4", "S5", "S6");
        }
        if ("read".equals(workload) || "write".equals(workload) || "mixed".equals(workload)) {
            return List.of("S1", "S2", "S3");
        }
        return splitCsv("S1,S2,S3,S4,S5,S6");
    }

    static String workloadOpt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "read", "write", "mixed", "query", "all" -> value;
            default -> throw new IllegalArgumentException(
                    "--workload must be read, write, mixed, query, or all (was '" + raw + "')");
        };
    }

    private static void validateScenarioWorkload(List<String> scenarios, String workload) {
        if (workload == null) {
            return;
        }
        for (String scenario : scenarios) {
            if ("all".equals(workload)) {
                if ("S7".equals(scenario)) {
                    throw new IllegalArgumentException(
                            "--workload=all supports point and query scenarios, not change-feed scenario 'S7'");
                }
                continue;
            }
            if ("query".equals(workload)) {
                if (!isQueryScenario(scenario)) {
                    throw new IllegalArgumentException(
                            "--workload=query only supports query scenarios (S4/S5/S6), not '" + scenario + "'");
                }
            } else if (!isPointScenario(scenario)) {
                throw new IllegalArgumentException(
                        "--workload=" + workload + " only supports point-operation scenarios (S1/S2/S3), not '"
                                + scenario + "'");
            }
        }
    }

    /**
     * Tightest document-size ceiling across the supported providers: DynamoDB rejects items
     * larger than 400 KB. Cosmos allows 2 MB, but a run only some providers can complete is not
     * a comparison, so the ceiling is enforced for every provider.
     */
    static final int MAX_DOC_SIZE_BYTES = 400_000;

    /**
     * Fails before any live work when a scenario's effective document size would exceed
     * {@link #MAX_DOC_SIZE_BYTES}. The item-size scenarios multiply {@code --doc-size}, so a
     * baseline that looks harmless can put S3 past the limit; discovering that through write
     * failures mid-run would leave a half-measured comparison that has to be thrown away.
     */
    static void validateEffectiveDocSizes(List<String> scenarios, int baseDocSize) {
        for (String scenario : scenarios) {
            int effective = Scenarios.docSizeFor(scenario, baseDocSize);
            if (effective > MAX_DOC_SIZE_BYTES) {
                int multiplier = Math.max(1, effective / Math.max(1, baseDocSize));
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "--doc-size %d makes scenario %s write %d B documents, above the %d B "
                                + "DynamoDB item limit. Lower --doc-size to at most %d, or drop "
                                + "%s from --scenarios.",
                        baseDocSize, scenario, effective, MAX_DOC_SIZE_BYTES,
                        MAX_DOC_SIZE_BYTES / multiplier, scenario));
            }
        }
    }
    static List<String> scenarioWorkloads(String scenario, String workload) {
        if (isQueryScenario(scenario)) {
            return List.of("query");
        }
        if ("S7".equals(scenario)) {
            return List.of("changefeed");
        }
        if ("all".equals(workload)) {
            return List.of("read", "write");
        }
        return List.of(workload == null ? "mixed" : workload);
    }

    private static boolean isQueryScenario(String scenario) {
        return "S4".equals(scenario) || "S5".equals(scenario) || "S6".equals(scenario);
    }

    private static boolean isPointScenario(String scenario) {
        return !isQueryScenario(scenario) && !"S7".equals(scenario);
    }

    private static String scenarioWorkloadLabel(String scenario, String pointWorkload) {
        if (isQueryScenario(scenario)) {
            return "query";
        }
        if ("S7".equals(scenario)) {
            return "changefeed";
        }
        return pointWorkload;
    }

    private static int intOpt(Map<String, String> opt, String key, int def) {
        String v = opt.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Integer.parseInt(v.trim());
    }

    private static int optionalPositiveInt(Map<String, String> opt, String key) {
        String v = opt.get(key);
        if (v == null || v.isBlank()) {
            return 0;
        }
        int parsed = Integer.parseInt(v.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException("--" + key + " must be > 0 when set");
        }
        return parsed;
    }

    static void validateDynamoCapacityArgs(int dynamoRcu, int dynamoWcu) {
        boolean onlyOne = (dynamoRcu > 0) != (dynamoWcu > 0);
        if (onlyOne) {
            throw new IllegalArgumentException("--dynamo-rcu and --dynamo-wcu must be provided together");
        }
    }

    private static int positiveConfigInt(ConfigLoader.AppConfig cfg, String key) {
        String raw = cfg.get(key, "");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int value = Integer.parseInt(raw.trim());
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be > 0 when set");
        }
        return value;
    }

    private static double effectiveTargetOpsPerSec(ProviderRunPlan plan, Map<String, String> opt,
                                                   double cliTargetOpsPerSec) {
        if (opt.containsKey("target-ops-per-sec")) {
            return cliTargetOpsPerSec;
        }
        String raw = plan.cfg().get("multiclouddb.perf.targetOpsPerSec", "");
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        double value = Double.parseDouble(raw.trim());
        if (value < 0.0) {
            throw new IllegalArgumentException(
                    "multiclouddb.perf.targetOpsPerSec must be >= 0 when set");
        }
        return value;
    }

    static String transportProfile(String providerId, ConfigLoader.AppConfig cfg) {
        if ("cosmos".equals(providerId)) {
            String thinClient = cfg.get(
                    "multiclouddb.connection.thinClientEnabled", "").trim().toLowerCase(Locale.ROOT);
            String routing = switch (thinClient) {
                case "" -> "gateway HTTP/2 (Gateway V2 auto-probe)";
                case "true" -> "gateway-v2/thin-client HTTP/2 (forced)";
                case "false" -> "gateway HTTP/2 (Gateway V2 disabled)";
                default -> throw new IllegalArgumentException(
                        "multiclouddb.connection.thinClientEnabled must be true or false");
            };
            return routing + " pool="
                    + valueOrDefault(cfg, "multiclouddb.connection.gatewayHttp2MaxConnectionPoolSize")
                    + " minPool="
                    + valueOrDefault(cfg, "multiclouddb.connection.gatewayHttp2MinConnectionPoolSize")
                    + " streams="
                    + valueOrDefault(cfg, "multiclouddb.connection.gatewayHttp2MaxConcurrentStreams");
        }
        if ("dynamo".equals(providerId)) {
            return "Apache HTTP/1.1 pool="
                    + valueOrDefault(cfg, "multiclouddb.connection.maxConnections");
        }
        if ("spanner".equals(providerId)) {
            return "gRPC/HTTP/2 (SDK-managed)";
        }
        return "unknown";
    }

    private static String valueOrDefault(ConfigLoader.AppConfig cfg, String key) {
        String value = cfg.get(key, "");
        return value == null || value.isBlank() ? "sdk-default" : value.trim();
    }

    private static void validateOfferedLoadTargets(List<ProviderRunPlan> plans, Map<String, String> opt,
                                                   double cliTargetOpsPerSec) {
        Double expected = null;
        for (ProviderRunPlan plan : plans) {
            double target = effectiveTargetOpsPerSec(plan, opt, cliTargetOpsPerSec);
            if (expected == null) {
                expected = target;
            } else if (Double.compare(expected, target) != 0) {
                throw new IllegalArgumentException(
                        "Fair comparison requires the same multiclouddb.perf.targetOpsPerSec "
                                + "for every provider config; use --target-ops-per-sec to override all providers");
            }
        }
    }

    private static double doubleOpt(Map<String, String> opt, String key, double def) {
        String v = opt.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Double.parseDouble(v.trim());
    }

    static double throttleThreshold(Map<String, String> opt) {
        double pct = doubleOpt(opt, "invalid-throttle-rate-pct", 0.1d);
        if (pct < 0.0) {
            throw new IllegalArgumentException("--invalid-throttle-rate-pct must be >= 0");
        }
        return pct / 100.0d;
    }

    private static void printUsage() {
        System.out.println("""
            Multicloud DB perf harness (MANUAL, live accounts only).

            Usage:
              run     [--config-dir DIR] [--providers cosmos,dynamo,spanner]
                      [--scenarios S1,S2,S3,S4,S5,S6] [--workload read|write|mixed|query|all]
                      [--threads 1,8,32] [--target-ops-per-sec N]
                      [--warmup N] [--iterations N] [--doc-size BYTES] [--page-size N]
                      [--repeat N] [--cosmos-ru RU] [--dynamo-rcu N --dynamo-wcu N]
                      [--split-wait-seconds N] [--enable-dynamo-streams]
                      [--region-policy warn|fail|ignore]
                      [--invalid-throttle-rate-pct PCT]
                      [--out multiclouddb-perf/results/raw] [--reports multiclouddb-perf/results/reports] [--title NAME]
              report  [--raw multiclouddb-perf/results/raw] [--reports multiclouddb-perf/results/reports]
                      [--run BATCH_ID] [--combined [--title NAME]] [--baseline PROVIDER]
                      [--invalid-throttle-rate-pct PCT]
              cleanup [--config FILE | --config-dir DIR --providers ...] [--dry-run]

            --target-ops-per-sec N applies a fair offered-load cap across worker threads by pacing
            actual operation starts. Leave it unset or 0 for existing max-throughput mode.
            --workload read|write|mixed|query selects one explicit workload profile.
            --workload all runs read, write, and query profiles in one batch and writes one report.
            Point scenarios are an item-size ladder derived from --doc-size: S1 baseline, S2 8x,
            S3 64x. Query scenarios vary one dimension each: S4 partition scope, S5 quarter page
            size, S6 8x item size. Effective sizes are recorded per row in the report.
            --cosmos-ru RU raises Cosmos to manual throughput before running — COSTS MONEY.
            --dynamo-rcu/--dynamo-wcu switches the Dynamo table to PROVISIONED and waits for ACTIVE — COSTS MONEY.
            --enable-dynamo-streams turns on a NEW_AND_OLD_IMAGES stream for Dynamo change-feed runs — COSTS MONEY.
            --region-policy warns (default), fails, or ignores config/probed region mismatches and
            comparison-region mismatches before measurements.
            --invalid-throttle-rate-pct defaults to 0.1; rows above that throttled-op rate are reported invalid.

            'run' and 'cleanup' hit live accounts and refuse to run in CI (override: PERF_ALLOW_CI=1).
            'report' is offline. By default it writes ONE report per run (batch) found under --raw,
            named <batchId>-REPORT.{md,html}. Use --run BATCH_ID to report a single run, or
            --combined to pool every run into one cross-run report named by --title.
            --baseline PROVIDER sets the migration-source provider for the thread-parity analysis.
            """);
    }
}
