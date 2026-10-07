// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Thread-safe, append-only writer for the raw perf result CSV.
 *
 * <p>Schema is defined in {@code multiclouddb-perf/templates/RESULT_SCHEMA.md},
 * one row per measured operation). Warmup iterations are never written here.
 */
final class CsvResultWriter implements AutoCloseable {

    static final String HEADER =
            "run_id,timestamp_utc,provider,region,comparison_region,transport_profile,endpoint_rtt_ms,"
            + "host_label,jdk,operation,workload,scenario,"
            + "doc_size_bytes,page_size,threads,iteration,start_offset_ms,end_offset_ms,latency_ms,success,error_category,"
            + "cost_unit,cost_value,retry_count,capacity_limit_unit,capacity_limit_value,billing_mode,"
            + "provisioned_capacity,sdk_version,target_ops_per_sec,notes";

    private final Writer writer;
    private final Object lock = new Object();

    CsvResultWriter(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            boolean existed = Files.exists(file) && Files.size(file) > 0;
            if (existed) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String existingHeader = reader.readLine();
                    if (!HEADER.equals(existingHeader)) {
                        throw new IllegalStateException(
                                "Result CSV schema does not match the current harness: " + file
                                        + ". Use a new --title or remove the old raw file.");
                    }
                }
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!existed) {
                writer.write(HEADER);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open result CSV: " + file, e);
        }
    }

    /** Appends one measured-operation row. Thread-safe. */
    void write(ResultRow r) {
        String line = String.join(",",
                q(r.runId()), q(r.timestampUtc()), q(r.provider()), q(r.region()), q(r.comparisonRegion()),
                q(r.transportProfile()),
                r.endpointRttMs() == null ? "" : fmt(r.endpointRttMs()),
                q(r.hostLabel()), q(r.jdk()), q(r.operation()), q(r.workload()), q(r.scenario()),
                Integer.toString(r.docSizeBytes()),
                r.pageSize() == null ? "" : Integer.toString(r.pageSize()),
                Integer.toString(r.threads()), Integer.toString(r.iteration()),
                fmt(r.startOffsetMs()), fmt(r.endOffsetMs()),
                fmt(r.latencyMs()), Boolean.toString(r.success()),
                q(r.errorCategory()), q(r.costUnit()),
                r.costValue() == null ? "" : fmt(r.costValue()),
                r.retryCount() == null ? "" : Integer.toString(r.retryCount()),
                q(r.capacityLimitUnit()),
                r.capacityLimitValue() == null ? "" : fmt(r.capacityLimitValue()),
                q(r.billingMode()), q(r.provisionedCapacity()), q(r.sdkVersion()),
                r.targetOpsPerSec() == null ? "" : fmt(r.targetOpsPerSec()),
                q(r.notes()));
        synchronized (lock) {
            try {
                writer.write(line);
                writer.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write result row", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close result CSV", e);
            }
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** Minimal CSV quoting: only when the value contains a comma, quote, or newline. */
    private static String q(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
