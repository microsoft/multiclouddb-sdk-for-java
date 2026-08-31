// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Runs one scenario for one provider against a live account and emits one
 * {@link ResultRow} per measured operation.
 */
final class ScenarioRunner {

    static final String MARKER_TAG = "perfHarness";
    static final String MARKER_PK = "perfPartitionKey";
    static final String MARKER_SK = "perfSortKey";

    private final MulticloudDbClient client;
    private final ResourceAddress address;
    private final Consumer<ResultRow> sink;
    private final RunContext ctx;
    private final String payload;

    ScenarioRunner(MulticloudDbClient client, ResourceAddress address,
                   Consumer<ResultRow> sink, RunContext ctx) {
        this.client = client;
        this.address = address;
        this.sink = sink;
        this.ctx = ctx;
        this.payload = "x".repeat(Math.max(0, ctx.docSize() - 128));
    }

    void run() {
        switch (ctx.scenario()) {
            case "S4" -> {
                queryPhase(true);
                queryPhase(false);
            }
            case "S5", "S6" -> queryPhase(false);
            case "S7" -> changeFeedPhase();
            default -> pointOpsPhase();
        }
    }

    private void pointOpsPhase() {
        switch (ctx.pointWorkload()) {
            case "read" -> pointReadPhase();
            case "write" -> pointWritePhase();
            default -> pointMixedPhase();
        }
    }

    private void pointMixedPhase() {
        int total = ctx.warmup() + ctx.iterations();
        forEachKey("mixed", "create", null, total,
                i -> MulticloudDbKey.of(ctx.runId() + "-" + i),
                (key, i) -> client.createWithDiagnostics(address, key, docFor(key)));
        forEachKey("mixed", "read", null, total,
                i -> MulticloudDbKey.of(ctx.runId() + "-" + i),
                (key, i) -> diagnostics(client.read(address, key)));
        forEachKey("mixed", "update", null, total,
                i -> MulticloudDbKey.of(ctx.runId() + "-" + i),
                (key, i) -> client.updateWithDiagnostics(address, key, docFor(key)));
        forEachKey("mixed", "delete", null, total,
                i -> MulticloudDbKey.of(ctx.runId() + "-" + i),
                (key, i) -> client.deleteWithDiagnostics(address, key));
    }

    private void pointReadPhase() {
        int total = ctx.warmup() + ctx.iterations();
        List<MulticloudDbKey> seeded = seedIndependentKeys("read", total);
        try {
            forEachKey("read", "read", null, total,
                    i -> seeded.get(i),
                    (key, i) -> diagnostics(client.read(address, key)));
        } finally {
            cleanupKeys(seeded);
        }
    }

    private void pointWritePhase() {
        int total = ctx.warmup() + ctx.iterations();
        List<MulticloudDbKey> updateKeys = seedIndependentKeys("update", total);
        List<MulticloudDbKey> upsertKeys = seedIndependentKeys("upsert", total);
        List<MulticloudDbKey> deleteKeys = seedIndependentKeys("delete", total);
        List<MulticloudDbKey> createdKeys = new ArrayList<>(total);
        try {
            forEachKey("write", "create", null, total,
                    i -> {
                        MulticloudDbKey key = MulticloudDbKey.of(ctx.runId() + "-write-create-" + i,
                                ctx.runId() + "-write-create-" + i);
                        synchronized (createdKeys) {
                            createdKeys.add(key);
                        }
                        return key;
                    },
                    (key, i) -> client.createWithDiagnostics(address, key, docFor(key)));
            forEachKey("write", "update", null, total,
                    updateKeys::get,
                    (key, i) -> client.updateWithDiagnostics(address, key, docFor(key)));
            forEachKey("write", "upsert", null, total,
                    upsertKeys::get,
                    (key, i) -> client.upsertWithDiagnostics(address, key, docFor(key)));
            forEachKey("write", "delete", null, total,
                    deleteKeys::get,
                    (key, i) -> client.deleteWithDiagnostics(address, key));
        } finally {
            cleanupKeys(createdKeys);
            cleanupKeys(updateKeys);
            cleanupKeys(upsertKeys);
            cleanupKeys(deleteKeys);
        }
    }

    private void queryPhase(boolean scoped) {
        String pk = ctx.runId() + "-qpk";
        int seed = Math.max(ctx.pageSize() * 2, 200);
        List<MulticloudDbKey> seeded = new ArrayList<>(seed);
        try {
            for (int i = 0; i < seed; i++) {
                MulticloudDbKey k = MulticloudDbKey.of(pk, "item-" + i);
                client.create(address, k, docFor(k));
                seeded.add(k);
            }
        } catch (RuntimeException seedFailure) {
            String cat = seedFailure instanceof MulticloudDbException me
                    ? errorCategory(me) : "PROVIDER_ERROR";
            emitFailureRow("query", "query", ctx.pageSize(),
                    scoped ? "scoped" : "unscoped",
                    "query seeding failed: " + seedFailure.getMessage(), cat);
            cleanupKeys(seeded);
            return;
        }
        try {
            String note = scoped ? "scoped" : "unscoped";
            forEachIteration("query", "query", ctx.pageSize(), note, (i) -> {
                QueryRequest.Builder qb = QueryRequest.builder()
                        .expression("category = @cat")
                        .parameter("cat", "perf")
                        .maxPageSize(ctx.pageSize());
                if (scoped) {
                    qb.partitionKey(pk);
                }
                QueryPage page = client.query(address, qb.build());
                return page.diagnostics();
            });
        } finally {
            cleanupKeys(seeded);
        }
    }

    private void changeFeedPhase() {
        List<ChangeFeedCursor> cursors;
        try {
            cursors = client.listCursors(address);
        } catch (MulticloudDbException e) {
            emitFailureRow("changefeed", "readChanges", null, "unsupported",
                    "change feed unsupported: " + e.error().category().getValue(), errorCategory(e));
            System.out.println("   change feed unsupported on this provider — recorded skip row.");
            return;
        }
        if (cursors.isEmpty()) {
            emitFailureRow("changefeed", "readChanges", null, "no-cursors", "no cursors returned", "PROVIDER_ERROR");
            return;
        }
        final ChangeFeedCursor[] cur = cursors.toArray(new ChangeFeedCursor[0]);
        final int partitions = cur.length;
        System.out.printf(Locale.ROOT,
                "   change feed: %d physical partition(s)/cursor(s) at tip%n", partitions);

        int seed = Math.min(ctx.warmup() + ctx.iterations(), Math.max(ctx.pageSize() * 5, 500));
        List<MulticloudDbKey> seeded = new ArrayList<>(seed);
        try {
            for (int i = 0; i < seed; i++) {
                MulticloudDbKey k = MulticloudDbKey.of(ctx.runId() + "-cf-" + i);
                client.create(address, k, docFor(k));
                seeded.add(k);
            }
        } catch (RuntimeException seedFailure) {
            String cat = seedFailure instanceof MulticloudDbException me
                    ? errorCategory(me) : "PROVIDER_ERROR";
            emitFailureRow("changefeed", "readChanges", null, "seed-failed",
                    "change feed seeding failed: " + seedFailure.getMessage(), cat);
            cleanupKeys(seeded);
            return;
        }
        try {
            forEachIteration("changefeed", "readChanges", null, partitions + "part", (i) -> {
                ChangeFeedPage page = client.readChanges(address, cur[i % partitions]);
                return page == null ? null : page.diagnostics();
            });
        } finally {
            cleanupKeys(seeded);
        }
    }

    private List<MulticloudDbKey> seedIndependentKeys(String prefix, int total) {
        List<MulticloudDbKey> seeded = new ArrayList<>(total);
        try {
            for (int i = 0; i < total; i++) {
                MulticloudDbKey key = MulticloudDbKey.of(ctx.runId() + '-' + prefix, prefix + '-' + i);
                client.upsert(address, key, docFor(key));
                seeded.add(key);
            }
        } catch (RuntimeException seedFailure) {
            cleanupKeys(seeded);
            throw seedFailure;
        }
        return seeded;
    }

    private void cleanupKeys(List<MulticloudDbKey> keys) {
        for (MulticloudDbKey key : keys) {
            try {
                client.delete(address, key);
            } catch (RuntimeException ignore) {
                // best-effort cleanup
            }
        }
    }

    private Map<String, Object> docFor(MulticloudDbKey key) {
        Map<String, Object> d = new HashMap<>();
        d.put("category", "perf");
        d.put("inStock", true);
        d.put("price", 9.99);
        d.put("payload", payload);
        d.put(MARKER_TAG, "true");
        d.put(MARKER_PK, key.partitionKey());
        d.put(MARKER_SK, key.sortKey() == null ? "" : key.sortKey());
        return d;
    }

    @FunctionalInterface
    private interface KeyedOp {
        OperationDiagnostics apply(MulticloudDbKey key, int index) throws Exception;
    }

    @FunctionalInterface
    private interface IterOp {
        OperationDiagnostics apply(int index) throws Exception;
    }

    @FunctionalInterface
    private interface KeyFactory {
        MulticloudDbKey create(int index);
    }

    @FunctionalInterface
    private interface IndexedTask {
        Sample apply(int index);
    }

    private record Sample(int iteration, long startedNanos, long finishedNanos,
                          double latencyMs, boolean success, String errorCategory,
                          OperationDiagnostics diagnostics, String notes) {
    }

    private record CapacityLimit(String unit, Double value) {
    }

    private void forEachKey(String workload, String op, Integer pageSize, int total,
                            KeyFactory keyFactory, KeyedOp fn) {
        System.out.printf(Locale.ROOT, "   %-11s ...", op);
        System.out.flush();
        List<Sample> samples = runPool(total, (i) -> {
            MulticloudDbKey key = keyFactory.create(i);
            return measure(i, "", () -> fn.apply(key, i));
        });
        emitRows(workload, op, pageSize, samples);
        System.out.println(" done");
    }

    private void forEachIteration(String workload, String op, Integer pageSize, String note, IterOp fn) {
        int total = ctx.warmup() + ctx.iterations();
        System.out.printf(Locale.ROOT, "   %-11s (%s) ...", op, note);
        System.out.flush();
        List<Sample> samples = runPool(total, (i) -> measure(i, note, () -> fn.apply(i)));
        emitRows(workload, op, pageSize, samples);
        System.out.println(" done");
    }

    private List<Sample> runPool(int total, IndexedTask task) {
        ExecutorService pool = Executors.newFixedThreadPool(ctx.threads());
        Semaphore permits = new Semaphore(ctx.threads());
        StartPacer pacer = new StartPacer(ctx.targetOpsPerSec());
        List<Future<Sample>> futures = new ArrayList<>(total);
        long scheduleBaseNanos = System.nanoTime();
        try {
            for (int i = 0; i < total; i++) {
                pacer.await(i, scheduleBaseNanos);
                permits.acquireUninterruptibly();
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try {
                        return task.apply(idx);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<Sample> out = new ArrayList<>(Math.max(0, ctx.iterations()));
            for (Future<Sample> f : futures) {
                try {
                    Sample sample = f.get();
                    if (sample != null) {
                        out.add(sample);
                    }
                } catch (Exception e) {
                    // per-op failures are captured by measure(); do not abort the run here
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    private Sample measure(int rawIndex, String note, ThrowingSupplier<OperationDiagnostics> call) {
        boolean record = rawIndex >= ctx.warmup();
        int iteration = rawIndex - ctx.warmup();
        long started = System.nanoTime();
        OperationDiagnostics diagnostics = null;
        boolean success = true;
        String errorCategory = "";
        try {
            diagnostics = call.get();
        } catch (MulticloudDbException e) {
            success = false;
            errorCategory = errorCategory(e);
        } catch (Exception e) {
            success = false;
            errorCategory = "PROVIDER_ERROR";
        }
        long finished = System.nanoTime();
        double latencyMs = (finished - started) / 1_000_000.0;
        if (!record) {
            return null;
        }
        return new Sample(iteration, started, finished, latencyMs, success, errorCategory, diagnostics, note);
    }

    private void emitRows(String workload, String op, Integer pageSize, List<Sample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        long baseStart = Long.MAX_VALUE;
        for (Sample sample : samples) {
            baseStart = Math.min(baseStart, sample.startedNanos());
        }
        for (Sample sample : samples) {
            double startOffsetMs = (sample.startedNanos() - baseStart) / 1_000_000.0;
            double endOffsetMs = (sample.finishedNanos() - baseStart) / 1_000_000.0;
            sink.accept(row(workload, op, pageSize, sample, startOffsetMs, endOffsetMs));
        }
    }

    private void emitFailureRow(String workload, String op, Integer pageSize, String note,
                                String message, String errorCategory) {
        sink.accept(new ResultRow(
                ctx.runId(), Instant.now().toString(), ctx.provider(), ctx.region(), ctx.comparisonRegion(),
                ctx.transportProfile(), ctx.endpointRttMs(), ctx.hostLabel(), ctx.jdk(), op, workload, ctx.scenario(),
                op.equals("read") || op.equals("delete") ? 0 : ctx.docSize(),
                pageSize, ctx.threads(), 0,
                0.0, 0.0, 0.0,
                false, errorCategory,
                "", null, null,
                capacityLimit(op).unit(), capacityLimit(op).value(),
                ctx.billingMode(), ctx.provisionedCapacity(), ctx.sdkVersion(), ctx.targetOpsPerSec(),
                note + ": " + message));
    }

    private ResultRow row(String workload, String op, Integer pageSize, Sample sample,
                          double startOffsetMs, double endOffsetMs) {
        Double cost = requestCharge(sample.diagnostics());
        String costUnit = (cost != null && cost > 0.0) ? costUnit(ctx.provider(), op) : "";
        CapacityLimit capacityLimit = capacityLimit(op);
        return new ResultRow(
                ctx.runId(), Instant.now().toString(), ctx.provider(), ctx.region(), ctx.comparisonRegion(),
                ctx.transportProfile(), ctx.endpointRttMs(), ctx.hostLabel(), ctx.jdk(), op, workload, ctx.scenario(),
                op.equals("read") || op.equals("delete") ? 0 : ctx.docSize(),
                pageSize, ctx.threads(), sample.iteration(),
                startOffsetMs, endOffsetMs, sample.latencyMs(),
                sample.success(), sample.errorCategory(),
                costUnit, (cost != null && cost > 0.0) ? cost : null,
                retryCount(sample.diagnostics()),
                capacityLimit.unit(), capacityLimit.value(),
                ctx.billingMode(), ctx.provisionedCapacity(), ctx.sdkVersion(), ctx.targetOpsPerSec(), sample.notes());
    }

    private CapacityLimit capacityLimit(String op) {
        if ("cosmos".equals(ctx.provider()) && ctx.sharedCapacityLimit() != null) {
            return new CapacityLimit("RU/s", ctx.sharedCapacityLimit());
        }
        boolean read = op.equals("read") || op.equals("query") || op.equals("readChanges");
        boolean write = op.equals("create") || op.equals("update") || op.equals("upsert") || op.equals("delete");
        if ("dynamo".equals(ctx.provider())) {
            if (read && ctx.readCapacityLimit() != null) {
                return new CapacityLimit("RCU/s", ctx.readCapacityLimit());
            }
            if (write && ctx.writeCapacityLimit() != null) {
                return new CapacityLimit("WCU/s", ctx.writeCapacityLimit());
            }
        }
        return new CapacityLimit("", null);
    }

    private static OperationDiagnostics diagnostics(DocumentResult result) {
        return result == null ? null : result.diagnostics();
    }

    private static Double requestCharge(OperationDiagnostics diag) {
        if (diag == null) {
            return null;
        }
        double rc = diag.requestCharge();
        return rc > 0.0 ? rc : null;
    }

    private static Integer retryCount(OperationDiagnostics diag) {
        return diag == null ? null : diag.retryCount();
    }

    private static String costUnit(String provider, String op) {
        boolean write = op.equals("create") || op.equals("update")
                || op.equals("upsert") || op.equals("delete");
        return switch (provider) {
            case "cosmos" -> "RU";
            case "dynamo" -> write ? "WCU" : "RCU";
            case "spanner" -> "PU-ms";
            default -> "";
        };
    }

    private static String errorCategory(MulticloudDbException e) {
        try {
            return e.error().category().getValue();
        } catch (RuntimeException ignore) {
            return "PROVIDER_ERROR";
        }
    }

    private static final class StartPacer {
        private final double intervalNanos;

        private StartPacer(Double targetOpsPerSec) {
            this.intervalNanos = targetOpsPerSec == null || targetOpsPerSec <= 0.0
                    ? 0.0 : 1_000_000_000.0 / targetOpsPerSec;
        }

        private void await(int index, long baseNanos) {
            if (intervalNanos <= 0.0) {
                return;
            }
            long scheduled = baseNanos + Math.round(index * intervalNanos);
            while (true) {
                long remaining = scheduled - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                LockSupport.parkNanos(Math.min(remaining, 1_000_000L));
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
