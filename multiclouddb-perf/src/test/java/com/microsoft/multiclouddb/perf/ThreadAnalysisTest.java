package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAnalysisTest {

    private static final double THRESHOLD = 0.001;

    @Test
    void aThrottledBaselineDoesNotHandOutPassingVerdicts() {
        // The baseline collapsed to 2.5 ops/s with a 50s p99 because it was throttled. Any
        // target beats that trivially, so the comparison must be reported as unmeasured
        // rather than as a migration pass.
        List<StatRow> stats = List.of(
                row("dynamo", "update", 2.5, 50_000.0, 0.0167),
                row("cosmos", "update", 80.0, 12.0, 0.0));

        List<ThreadAnalysis.ParityRow> parity =
                ThreadAnalysis.parity(stats, List.of("dynamo", "cosmos"), "dynamo", THRESHOLD);

        assertEquals(1, parity.size());
        assertFalse(parity.get(0).measurementValid(),
                "a baseline above the throttle threshold is not a usable measurement");
    }

    @Test
    void aThrottledTargetIsAlsoUnmeasured() {
        List<StatRow> stats = List.of(
                row("dynamo", "update", 80.0, 20.0, 0.0),
                row("cosmos", "update", 2.5, 50_000.0, 0.0167));

        List<ThreadAnalysis.ParityRow> parity =
                ThreadAnalysis.parity(stats, List.of("dynamo", "cosmos"), "dynamo", THRESHOLD);

        assertEquals(1, parity.size());
        assertFalse(parity.get(0).measurementValid(),
                "a throttled target cannot be judged against a healthy baseline");
    }

    @Test
    void cleanRowsStillProduceARealVerdict() {
        List<StatRow> stats = List.of(
                row("dynamo", "update", 80.0, 20.0, 0.0),
                row("cosmos", "update", 80.0, 12.0, 0.0));

        List<ThreadAnalysis.ParityRow> parity =
                ThreadAnalysis.parity(stats, List.of("dynamo", "cosmos"), "dynamo", THRESHOLD);

        assertEquals(1, parity.size());
        assertTrue(parity.get(0).measurementValid());
        assertTrue(parity.get(0).pass(), "cosmos matches baseline throughput at a lower p99");
    }

    @Test
    void validityMatchesTheRowTablesSoTheReportCannotContradictItself() {
        StatRow throttled = row("dynamo", "update", 2.5, 50_000.0, 0.0167);
        StatRow clean = row("cosmos", "update", 80.0, 12.0, 0.0);

        assertEquals("invalid (throttled)", Reports.validity(throttled, THRESHOLD));
        assertFalse(Reports.valid(throttled, THRESHOLD));
        assertEquals("valid", Reports.validity(clean, THRESHOLD));
        assertTrue(Reports.valid(clean, THRESHOLD));

        // A rate exactly at the threshold is still reported valid, matching the per-row tables.
        assertTrue(Reports.valid(row("cosmos", "update", 80.0, 12.0, THRESHOLD), THRESHOLD));
    }

    private static StatRow row(String provider, String operation, double tput, double p99,
                               double throttledRate) {
        return new StatRow(provider, operation, "write", "S3", null, 8, 65_536, null,
                1, 100, 100, 10.0, 20.0, p99, p99, 15.0, 1.0,
                5.0, 5.0, 25.0, tput,
                80.0, 80.0, 1.0,
                "RU", 1.0, 1.0, 80.0, "RU/s", 400.0, 20.0,
                0, throttledRate, null, null, 0.0);
    }
}