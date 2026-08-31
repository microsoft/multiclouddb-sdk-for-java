package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validity has to ask the same question of every provider. Counting throttled operations does
 * not: a client SDK that retries a rejection internally and eventually succeeds reports zero
 * throttled operations while sitting on a capacity ceiling, while one that surfaces the rejection
 * is marked invalid for the identical underlying condition. These tests pin the behaviour that
 * closes that gap.
 */
class ReportsValidityTest {

    private static final double THRESHOLD = 0.001;

    @Test
    @DisplayName("a healthy paced row is valid")
    void healthyRowIsValid() {
        StatRow row = row(80.0, 80.1, 0.0);

        assertTrue(Reports.valid(row, THRESHOLD));
        assertNull(Reports.invalidReason(row, THRESHOLD));
        assertEquals("valid", Reports.validity(row, THRESHOLD));
    }

    @Test
    @DisplayName("surfaced throttling invalidates a row and says so")
    void surfacedThrottlingIsNamed() {
        // DynamoDB shape: rejections reach the caller, so throughput collapses AND ops fail.
        StatRow row = row(80.0, 2.75, 0.028);

        assertFalse(Reports.valid(row, THRESHOLD));
        assertEquals("throttled", Reports.invalidReason(row, THRESHOLD));
        assertEquals("invalid (throttled)", Reports.validity(row, THRESHOLD));
    }

    @Test
    @DisplayName("a capacity ceiling absorbed by SDK retries is still invalid")
    void absorbedThrottlingIsStillInvalid() {
        // Cosmos shape: the SDK retries 429s internally, so nothing is reported as throttled even
        // though the run only reached 71.4 of the 80 ops/s it set out to measure. Before the
        // under-target rule this row was reported valid and handed out a parity verdict.
        StatRow row = row(80.0, 71.4, 0.0);

        assertFalse(Reports.valid(row, THRESHOLD),
                "a run that never reached its target is not a usable basis for comparison, "
                        + "however the provider chose to absorb the rejections");
        assertEquals("under target", Reports.invalidReason(row, THRESHOLD));
        assertEquals("invalid (under target)", Reports.validity(row, THRESHOLD));
    }

    @Test
    @DisplayName("both providers get the same verdict for the same condition")
    void theRuleIsProviderNeutral() {
        StatRow surfaced = row(80.0, 2.75, 0.028);
        StatRow absorbed = row(80.0, 2.75, 0.0);

        assertFalse(Reports.valid(surfaced, THRESHOLD));
        assertFalse(Reports.valid(absorbed, THRESHOLD),
                "identical throughput collapse must not depend on whether the SDK surfaced the "
                        + "rejection or swallowed it");
    }

    @Test
    @DisplayName("max-throughput sweeps set no target and are never judged against one")
    void unboundedRunsAreNotJudgedAgainstATarget() {
        StatRow unbounded = new StatRow("cosmos", "read", "read", "S1", null, 8, 1024, null,
                1, 100, 100, 10.0, 20.0, 30.0, 40.0, 15.0, 1.0,
                5.0, 5.0, 25.0, 12.3,
                null, 12.3, 1.0,
                "RU", 1.0, 1.0, 12.3, "RU/s", 400.0, 3.0,
                0, 0.0, null, null, 0.0);

        assertTrue(Reports.valid(unbounded, THRESHOLD),
                "an unbounded sweep has no target to fall short of");
    }

    @Test
    @DisplayName("the boundary is inclusive so a row exactly at the bar still counts")
    void exactlyAtTheBarIsValid() {
        double atBar = 80.0 * Reports.MIN_ATTAINED_TARGET_RATIO;

        assertTrue(Reports.valid(row(80.0, atBar, 0.0), THRESHOLD));
        assertFalse(Reports.valid(row(80.0, atBar - 0.01, 0.0), THRESHOLD));
    }

    @Test
    @DisplayName("throttling is reported ahead of the throughput symptom it causes")
    void throttlingIsReportedAheadOfItsSymptom() {
        // A throttled row is also under target; naming the cause is more useful than the effect.
        assertEquals("throttled", Reports.invalidReason(row(80.0, 2.75, 0.028), THRESHOLD));
    }

    private static StatRow row(Double target, double achieved, double throttledRate) {
        return new StatRow("cosmos", "update", "write", "S3", null, 8, 65_536, null,
                1, 100, 100, 10.0, 20.0, 30.0, 40.0, 15.0, 1.0,
                5.0, 5.0, 25.0, achieved,
                target, achieved, 1.0,
                "RU", 52.1, 52.1, 3719.0, "RU/s", 4000.0, 93.0,
                (int) Math.round(throttledRate * 100), throttledRate, null, null, 0.0);
    }
}