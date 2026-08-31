// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/** Distinct per-provider environment metadata for the report Environment table. */
record EnvRow(
        String provider, String region, String comparisonRegion, String transportProfile,
        Double endpointRttMs, String hostLabel, String jdk,
        String billingMode, String provisionedCapacity, String sdkVersion) {
}
