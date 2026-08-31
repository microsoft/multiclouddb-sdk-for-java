// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/**
 * One measured-operation row, matching {@code multiclouddb-perf/templates/RESULT_SCHEMA.md}.
 * {@code pageSize} and {@code costValue} are nullable (rendered blank in CSV).
 */
record ResultRow(
        String runId,
        String timestampUtc,
        String provider,
        String region,
        String comparisonRegion,
        String transportProfile,
        Double endpointRttMs,
        String hostLabel,
        String jdk,
        String operation,
        String workload,
        String scenario,
        int docSizeBytes,
        Integer pageSize,
        int threads,
        int iteration,
        double startOffsetMs,
        double endOffsetMs,
        double latencyMs,
        boolean success,
        String errorCategory,
        String costUnit,
        Double costValue,
        Integer retryCount,
        String capacityLimitUnit,
        Double capacityLimitValue,
        String billingMode,
        String provisionedCapacity,
        String sdkVersion,
        Double targetOpsPerSec,
        String notes) {
}
