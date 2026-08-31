package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportTest {

    @TempDir
    Path tempDir;

    @Test
    void reportContainsProminentInlineCharts() throws Exception {
        StatRow cosmos = stat("cosmos", 90.0, 82.0, 20.0);
        StatRow dynamo = stat("dynamo", 100.0, 80.0, 75.0);
        List<EnvRow> env = List.of(
                new EnvRow("cosmos", "westus2", "west",
                        "gateway HTTP/2 (Gateway V2 auto-probe) pool=64 minPool=8 streams=32",
                        64.0, "host", "jdk", "manual", "4000 RU/s", "dev"),
                new EnvRow("dynamo", "us-west-2", "west", "Apache HTTP/1.1 pool=64",
                        38.0, "host", "jdk", "PROVISIONED",
                        "rcu=1000 wcu=200", "dev"));

        Path report = HtmlReport.write(List.of(cosmos, dynamo), env,
                new ReportMeta("charts", "now", "test", "dynamo", 0.001), tempDir);
        String html = Files.readString(report);

        assertTrue(html.contains("<h2>2. What was tested</h2>"));
        assertTrue(html.contains("<h2>3. At a glance</h2>"));
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("p99 latency"));
        assertTrue(html.contains("Achieved throughput"));
        assertTrue(html.contains("Capacity utilization"));
        assertTrue(html.contains("p99 service time (RTT-normalised)"));
        assertTrue(html.contains("Endpoint RTT"));
        assertTrue(html.contains("#0078d4"));
        assertTrue(html.contains("#ff9900"));
    }

    @Test
    void queryScopesAreReportedAsSeparateRows() throws Exception {
        StatRow scoped = query("cosmos", "scoped", 40.0);
        StatRow unscoped = query("cosmos", "unscoped", 90.0);
        List<EnvRow> env = List.of(new EnvRow("cosmos", "westus2", "west",
                "gateway HTTP/2 pool=64", 10.0, "host", "jdk", "manual", "4000 RU/s", "dev"));

        Path report = HtmlReport.write(List.of(scoped, unscoped), env,
                new ReportMeta("scopes", "now", "test", "dynamo", 0.001), tempDir);
        String html = Files.readString(report);

        assertTrue(html.contains("single-partition"), "expected a single-partition row");
        assertTrue(html.contains("cross-partition"), "expected a cross-partition row");
        assertTrue(html.contains("<th>Scope</th>"), "expected a Scope column");
    }

    private static StatRow query(String provider, String variant, double p99) {
        return new StatRow(provider, "query", "query", "S4", variant, 8, 1024, 100,
                1, 100, 100, p99 / 2, p99, p99, p99, p99 / 2, 1.0,
                10.0, p99 / 2 - 10.0, p99 - 10.0, 80.0, 80.0, 80.0, 1.0,
                "RU", 3.0, 3.0, 240.0, "RU/s", 4000.0, 6.0,
                0, 0.0, null, null, 0.0);
    }

    private static StatRow stat(String provider, double p99, double throughput, double utilization) {
        double rttMs = provider.equals("cosmos") ? 64.0 : 38.0;
        return new StatRow(provider, "read", "read", "S1", null, 8, 0, null,
                1, 100, 100, 60.0, 75.0, p99, p99, 70.0, 5.0,
                rttMs, Math.max(0.0, 60.0 - rttMs), Math.max(0.0, p99 - rttMs),
                throughput, 80.0, 80.0, throughput / 80.0,
                provider.equals("cosmos") ? "RU" : "RCU", 1.0, 1.0, throughput,
                provider.equals("cosmos") ? "RU/s" : "RCU/s", 1000.0, utilization,
                0, 0.0, null, null, 0.0);
    }
}
