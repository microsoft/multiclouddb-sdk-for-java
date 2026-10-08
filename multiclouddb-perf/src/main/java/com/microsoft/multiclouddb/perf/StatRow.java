// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/**
 * One pooled statistics row: all raw samples sharing
 * {@code (provider, operation, scenario, threads, docSizeBytes, pageSize)} are
 * pooled across runs (run id is NOT part of the key), percentiles recomputed over
 * the combined sample, and {@code runCount} records how many distinct runs contributed.
 */
record StatRow(
        String provider, String operation, String workload, String scenario,
        String variant, int threads,
        int docSizeBytes, Integer pageSize,
        int runCount, int count, int successCount,
        double p50, double p90, double p99, double max, double mean, double stdev,
        Double endpointRttMs, Double serviceP50, Double serviceP99,
        double throughputOpsSec,
        Double targetOpsPerSec, double offeredOpsSec, double achievedOfferedRatio,
        String costUnit, Double costMean, Double costP99, Double consumedUnitsPerSec,
        String capacityLimitUnit, Double capacityLimitValue, Double capacityUtilizationPct,
        int throttledCount, double throttledRate,
        Integer retryCountTotal, Double retryMean,
        double errorRate) {
}
