package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegionFairnessTest {

    @Test
    void normalizeIgnoresCaseSpaceAndHyphenOnly() {
        assertEquals("westus2", RegionFairness.normalize("West US-2"));
        assertEquals("uswest2", RegionFairness.normalize("us-west-2"));
        assertNotEquals(RegionFairness.normalize("westus2"), RegionFairness.normalize("us-west-2"));
    }

    @Test
    void explicitComparisonRegionWins() {
        assertEquals("colo-a",
                RegionFairness.effectiveComparisonRegion(" colo-a ", "westus2", "westus2"));
    }

    @Test
    void validateFlagsConfigProbeAndCrossProviderMismatch() {
        RegionFairness.CheckResult result = RegionFairness.validate(List.of(
                new RegionFairness.ProviderRegion("cosmos", "West US 2", "westus2", "colo-a"),
                new RegionFairness.ProviderRegion("dynamo", "us-west-2", "us-east-1", "colo-b")),
                RegionFairness.Policy.FAIL);

        assertTrue(result.failed());
        assertEquals(2, result.messages().size());
        assertTrue(result.messages().get(0).contains("configured region"));
        assertTrue(result.messages().get(1).contains("comparison regions differ"));
    }
}
