package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StatisticsTest {

    @Test
    void aggregateComputesOfferedThroughputUtilizationAndRetries() {
        List<ResultRow> rows = List.of(
                new ResultRow("run-1", "2026-01-01T00:00:00Z", "dynamo", "us-east-1", "colo-a", "HTTP/1.1 pool=64", 12.0,
                        "host", "jdk", "create", "write", "S1", 1024, null, 4, 0,
                        0.0, 200.0, 200.0, true, "", "WCU", 3.0, 1,
                        "WCU/s", 10.0, "PROVISIONED", "PROVISIONED rcu=10 wcu=10", "dev", 2.0, ""),
                new ResultRow("run-1", "2026-01-01T00:00:01Z", "dynamo", "us-east-1", "colo-a", "HTTP/1.1 pool=64", 12.0,
                        "host", "jdk", "create", "write", "S1", 1024, null, 4, 1,
                        1000.0, 1200.0, 200.0, true, "", "WCU", 3.0, 0,
                        "WCU/s", 10.0, "PROVISIONED", "PROVISIONED rcu=10 wcu=10", "dev", 2.0, ""),
                new ResultRow("run-1", "2026-01-01T00:00:02Z", "dynamo", "us-east-1", "colo-a", "HTTP/1.1 pool=64", 12.0,
                        "host", "jdk", "create", "write", "S1", 1024, null, 4, 2,
                        2000.0, 2200.0, 200.0, false, "THROTTLED", "", null, null,
                        "WCU/s", 10.0, "PROVISIONED", "PROVISIONED rcu=10 wcu=10", "dev", 2.0, "")).stream().toList();

        StatRow stat = Statistics.aggregate(rows).get(0);

        assertEquals("write", stat.workload());
        assertEquals(2.0, stat.targetOpsPerSec(), 1e-9);
        assertEquals(1.5, stat.offeredOpsSec(), 1e-9);
        assertEquals(2.0 / 2.2, stat.throughputOpsSec(), 1e-9);
        assertEquals(stat.throughputOpsSec() / stat.offeredOpsSec(), stat.achievedOfferedRatio(), 1e-9);
        assertEquals(6.0 / 2.2, stat.consumedUnitsPerSec(), 1e-9);
        assertEquals((6.0 / 2.2) / 10.0 * 100.0, stat.capacityUtilizationPct(), 1e-9);
        assertEquals(1, stat.throttledCount());
        assertEquals(1.0 / 3.0, stat.throttledRate(), 1e-9);
        assertEquals(Integer.valueOf(1), stat.retryCountTotal());
        assertNotNull(stat.retryMean());
    }

    @Test
    void aggregateKeepsDifferentOfferedLoadTargetsSeparate() {
        ResultRow atFifty = rowWithTarget("run-50", 50.0);
        ResultRow atHundred = rowWithTarget("run-100", 100.0);

        List<StatRow> stats = Statistics.aggregate(List.of(atFifty, atHundred));

        assertEquals(2, stats.size());
        assertEquals(List.of(50.0, 100.0),
                stats.stream().map(StatRow::targetOpsPerSec).sorted().toList());
    }

    @Test
    void aggregateRejectsMixedTransportProfilesForOneProvider() {
        ResultRow http11 = rowWithTarget("http11", 50.0);
        ResultRow http2 = withTransport(rowWithTarget("http2", 50.0),
                "gateway HTTP/2 pool=8 minPool=2 streams=32");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Statistics.aggregate(List.of(http11, http2)));
    }

    @Test
    void aggregateKeepsSinglePartitionAndCrossPartitionQueriesSeparate() {
        // A partition-scoped query and a cross-partition fan-out are different operations;
        // averaging them yields a number that describes neither.
        ResultRow scoped = queryRow("run-q", "scoped", 20.0);
        ResultRow unscoped = queryRow("run-q", "unscoped", 80.0);

        List<StatRow> stats = Statistics.aggregate(List.of(scoped, unscoped));

        assertEquals(2, stats.size());
        assertEquals(List.of("scoped", "unscoped"),
                stats.stream().map(StatRow::variant).sorted().toList());
        assertEquals(List.of(20.0, 80.0), stats.stream().map(StatRow::p50).sorted().toList());
    }

    @Test
    void aggregateIgnoresNonScopeNotesWhenGrouping() {
        // notes also carries failure text; only the two scope tokens identify a measurement.
        ResultRow first = withNotes(rowWithTarget("run-n", 50.0), "seeding failed: boom");
        ResultRow second = withNotes(rowWithTarget("run-n", 50.0), "some other annotation");

        List<StatRow> stats = Statistics.aggregate(List.of(first, second));

        assertEquals(1, stats.size());
        org.junit.jupiter.api.Assertions.assertNull(stats.get(0).variant());
    }

    @Test
    void serviceTimeSubtractsRttAndClampsAtZero() {
        assertEquals(24.0, Statistics.serviceTime(62.0, 38.0), 1e-9);
        assertEquals(0.0, Statistics.serviceTime(30.0, 38.0), 1e-9);
        org.junit.jupiter.api.Assertions.assertNull(Statistics.serviceTime(62.0, null));
    }

    private static ResultRow withTransport(ResultRow row, String transport) {
        return new ResultRow(row.runId(), row.timestampUtc(), row.provider(), row.region(),
                row.comparisonRegion(), transport, row.endpointRttMs(), row.hostLabel(), row.jdk(), row.operation(),
                row.workload(), row.scenario(), row.docSizeBytes(), row.pageSize(), row.threads(),
                row.iteration(), row.startOffsetMs(), row.endOffsetMs(), row.latencyMs(), row.success(),
                row.errorCategory(), row.costUnit(), row.costValue(), row.retryCount(),
                row.capacityLimitUnit(), row.capacityLimitValue(), row.billingMode(),
                row.provisionedCapacity(), row.sdkVersion(), row.targetOpsPerSec(), row.notes());
    }

    private static ResultRow withNotes(ResultRow row, String notes) {
        return new ResultRow(row.runId(), row.timestampUtc(), row.provider(), row.region(),
                row.comparisonRegion(), row.transportProfile(), row.endpointRttMs(), row.hostLabel(),
                row.jdk(), row.operation(), row.workload(), row.scenario(), row.docSizeBytes(),
                row.pageSize(), row.threads(), row.iteration(), row.startOffsetMs(), row.endOffsetMs(),
                row.latencyMs(), row.success(), row.errorCategory(), row.costUnit(), row.costValue(),
                row.retryCount(), row.capacityLimitUnit(), row.capacityLimitValue(), row.billingMode(),
                row.provisionedCapacity(), row.sdkVersion(), row.targetOpsPerSec(), notes);
    }

    private static ResultRow queryRow(String runId, String scope, double latencyMs) {
        return new ResultRow(runId, "2026-01-01T00:00:00Z", "cosmos", "west us 2", "colo-a",
                "gateway HTTP/2 pool=64", 5.0, "host", "jdk", "query", "query", "S4", 1024, 100, 4, 0,
                0.0, latencyMs, latencyMs, true, "", "RU", 3.0, 0,
                "RU/s", 400.0, "manual", "400 RU/s", "dev", 50.0, scope);
    }

    private static ResultRow rowWithTarget(String runId, double targetOpsPerSec) {
        return new ResultRow(runId, "2026-01-01T00:00:00Z", "dynamo", "us-east-1", "colo-a", "HTTP/1.1 pool=64", 12.0,
                "host", "jdk", "read", "read", "S1", 0, null, 4, 0,
                0.0, 10.0, 10.0, true, "", "RCU", 1.0, 0,
                "RCU/s", 100.0, "PROVISIONED", "PROVISIONED rcu=100 wcu=100",
                "dev", targetOpsPerSec, "");
    }
}
