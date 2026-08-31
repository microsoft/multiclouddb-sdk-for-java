package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import com.microsoft.multiclouddb.e2e.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfMainValidationTest {

    @Test
    void workloadParsingAndScenarioDefaultsAreStable() {
        assertEquals("query", PerfMain.workloadOpt("query"));
        assertEquals(List.of("S4", "S5", "S6"), PerfMain.resolveScenarios(new LinkedHashMap<>(), "query"));
        assertEquals(List.of("S1", "S2", "S3"), PerfMain.resolveScenarios(new LinkedHashMap<>(), "read"));
        assertEquals("all", PerfMain.workloadOpt("all"));
        assertEquals(List.of("S1", "S2", "S3", "S4", "S5", "S6"),
                PerfMain.resolveScenarios(new LinkedHashMap<>(), "all"));
        assertEquals(List.of("read", "write"), PerfMain.scenarioWorkloads("S1", "all"));
        assertEquals(List.of("query"), PerfMain.scenarioWorkloads("S4", "all"));
        assertThrows(IllegalArgumentException.class, () -> PerfMain.workloadOpt("bogus"));
    }

    @Test
    void effectiveDocSizeIsRejectedAboveTheDynamoItemLimit() {
        // S3 multiplies --doc-size by 64, so a baseline that looks harmless can put the
        // scenario past DynamoDB's 400 KB item limit. The run must fail before it writes.
        assertEquals(65_536, Scenarios.docSizeFor("S3", 1024));
        PerfMain.validateEffectiveDocSizes(List.of("S1", "S2", "S3"), 1024);

        IllegalArgumentException tooBig = assertThrows(IllegalArgumentException.class,
                () -> PerfMain.validateEffectiveDocSizes(List.of("S1", "S2", "S3"), 8192));
        assertTrue(tooBig.getMessage().contains("S3"), tooBig.getMessage());
        assertTrue(tooBig.getMessage().contains("400000"), tooBig.getMessage());

        // The baseline scenario stays legal at a document size that only S3 cannot carry.
        PerfMain.validateEffectiveDocSizes(List.of("S1"), 8192);
    }

    @Test
    void dynamoCapacityArgsMustBePaired() {
        assertThrows(IllegalArgumentException.class, () -> PerfMain.validateDynamoCapacityArgs(100, 0));
        assertThrows(IllegalArgumentException.class, () -> PerfMain.validateDynamoCapacityArgs(0, 100));
    }

    @Test
    void cosmosTransportProfileRecordsGatewayV2Selection() {
        assertEquals("gateway HTTP/2 (Gateway V2 auto-probe) "
                        + "pool=sdk-default minPool=sdk-default streams=sdk-default",
                PerfMain.transportProfile("cosmos", cfg()));

        assertEquals("gateway-v2/thin-client HTTP/2 (forced) pool=64 minPool=8 streams=32",
                PerfMain.transportProfile("cosmos", cfg(
                        "multiclouddb.connection.thinClientEnabled", "true",
                        "multiclouddb.connection.gatewayHttp2MaxConnectionPoolSize", "64",
                        "multiclouddb.connection.gatewayHttp2MinConnectionPoolSize", "8",
                        "multiclouddb.connection.gatewayHttp2MaxConcurrentStreams", "32")));
    }

    @Test
    void transportProfilesDistinguishGatewayV2OptOutAndDynamo() {
        assertEquals("gateway HTTP/2 (Gateway V2 disabled) "
                        + "pool=64 minPool=sdk-default streams=sdk-default",
                PerfMain.transportProfile("cosmos", cfg(
                        "multiclouddb.connection.thinClientEnabled", "false",
                        "multiclouddb.connection.gatewayHttp2MaxConnectionPoolSize", "64")));

        assertEquals("Apache HTTP/1.1 pool=64",
                PerfMain.transportProfile("dynamo", cfg(
                        "multiclouddb.connection.maxConnections", "64")));

        assertThrows(IllegalArgumentException.class,
                () -> PerfMain.transportProfile("cosmos", cfg(
                        "multiclouddb.connection.thinClientEnabled", "sometimes")));
    }

    private static ConfigLoader.AppConfig cfg(String... keyValuePairs) {
        Properties props = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new ConfigLoader.AppConfig(null, props);
    }

    @Test
    void throttleThresholdParsesPercentToFraction() {
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("invalid-throttle-rate-pct", "0.5");
        assertEquals(0.005, PerfMain.throttleThreshold(opt), 1e-9);
    }
}
