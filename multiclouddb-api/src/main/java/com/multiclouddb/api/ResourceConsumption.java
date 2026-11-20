// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Objects;

/**
 * Observed provider consumption, not currency. A present zero is a measured
 * zero; unavailable consumption is represented by a null diagnostics field.
 */
public final class ResourceConsumption {
    public enum Unit {
        COSMOS_REQUEST_UNIT,
        DYNAMODB_CAPACITY_UNIT
    }

    /** Whether the observation covers the entire public call, including retries. */
    public enum Coverage {
        COMPLETE,
        PARTIAL,
        UNKNOWN
    }

    private final double amount;
    private final Unit unit;
    private final Coverage coverage;

    public ResourceConsumption(double amount, Unit unit, Coverage coverage) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Consumption must be finite and non-negative");
        }
        this.amount = amount;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.coverage = Objects.requireNonNull(coverage, "coverage");
    }

    public double amount() { return amount; }
    public Unit unit() { return unit; }
    public Coverage coverage() { return coverage; }
}
