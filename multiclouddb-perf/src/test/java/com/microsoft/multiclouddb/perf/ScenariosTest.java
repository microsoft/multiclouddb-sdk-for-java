package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenariosTest {

    @Test
    void variantAcceptsOnlyTheTwoScopeTokens() {
        assertEquals("scoped", Scenarios.variant("scoped"));
        assertEquals("unscoped", Scenarios.variant(" unscoped "));
        assertNull(Scenarios.variant(null));
        assertNull(Scenarios.variant("query seeding failed: boom"));
        assertNull(Scenarios.variant("2part"));
    }

    @Test
    void scopeLabelDistinguishesCreateFromTheOtherPointOperations() {
        // create writes a unique partition key per item; the others share one.
        assertEquals("single item, unique partition key", Scenarios.scopeLabel(null, "create"));
        assertEquals("single item, shared partition key", Scenarios.scopeLabel(null, "update"));
        assertEquals("all partitions", Scenarios.scopeLabel(null, "readChanges"));
        assertEquals("single-partition", Scenarios.scopeLabel("scoped", "query"));
        assertEquals("cross-partition", Scenarios.scopeLabel("unscoped", "query"));
    }

    @Test
    void profilesSplitByOperationAndScopeSoEachRowDescribesItsOwnScope() {
        Map<String, Scenarios.Profile> profiles = Scenarios.profiles(List.of(
                statRow("S1", "write", "create", null),
                statRow("S1", "write", "update", null),
                statRow("S4", "query", "query", "scoped"),
                statRow("S4", "query", "query", "unscoped")));

        assertEquals(4, profiles.size());
        assertEquals(List.of("single item, unique partition key",
                        "single item, shared partition key",
                        "single-partition", "cross-partition"),
                profiles.values().stream().map(Scenarios.Profile::scope).toList());
    }

    @Test
    void purposesDescribeEachWorkloadOfAScenarioNotJustTheFirst() {
        Map<String, String> purposes = Scenarios.purposes(Scenarios.profiles(List.of(
                statRow("S1", "read", "read", null),
                statRow("S1", "write", "create", null))));

        assertEquals(List.of("S1 \u00b7 read", "S1 \u00b7 write"), List.copyOf(purposes.keySet()));
        assertTrue(purposes.get("S1 \u00b7 write").contains("unique partition key"),
                "write description should describe the write phases: " + purposes.get("S1 \u00b7 write"));
        assertTrue(purposes.get("S1 \u00b7 read").contains("reads them back"),
                "read description should describe the read phase: " + purposes.get("S1 \u00b7 read"));
    }

    @Test
    void duplicateScenarioNoteNamesScenariosThatRanIdenticalProfiles() {
        String note = Scenarios.duplicateScenarioNote(Scenarios.profiles(List.of(
                statRow("S4", "query", "query", "scoped"),
                statRow("S5", "query", "query", "unscoped"),
                statRow("S6", "query", "query", "unscoped"))));

        assertTrue(note.contains("S5, S6"), note);

        assertNull(Scenarios.duplicateScenarioNote(Scenarios.profiles(List.of(
                statRow("S4", "query", "query", "scoped"),
                statRow("S5", "query", "query", "unscoped")))));
    }

    @Test
    void eachQueryScenarioVariesExactlyOneDimension() {
        assertEquals(100, Scenarios.pageSizeFor("S4", 100));
        assertEquals(1024, Scenarios.docSizeFor("S4", 1024));

        // S5 varies page size only.
        assertEquals(25, Scenarios.pageSizeFor("S5", 100));
        assertEquals(1024, Scenarios.docSizeFor("S5", 1024));

        // S6 varies item size, shrinking the page so bytes per page stay near the baseline.
        assertEquals(8192, Scenarios.docSizeFor("S6", 1024));
        assertEquals(12, Scenarios.pageSizeFor("S6", 100));
        int baselineBytesPerPage = 100 * 1024;
        int s5BytesPerPage = Scenarios.pageSizeFor("S6", 100) * Scenarios.docSizeFor("S6", 1024);
        assertTrue(s5BytesPerPage <= baselineBytesPerPage,
                "S6 must not read more bytes per page than the baseline: " + s5BytesPerPage);
        assertTrue(s5BytesPerPage > baselineBytesPerPage / 2,
                "S6 should stay near the baseline bytes per page: " + s5BytesPerPage);

        // Point scenarios are untouched, and a page size can never collapse to zero.
        assertEquals(100, Scenarios.pageSizeFor("S1", 100));
        assertEquals(1, Scenarios.pageSizeFor("S6", 1));
    }

    @Test
    void queryScenariosAreNoLongerDuplicates() {
        String note = Scenarios.duplicateScenarioNote(Scenarios.profiles(List.of(
                queryStatRow("S4", "unscoped", 1024, 100),
                queryStatRow("S5", "unscoped", 1024, 25),
                queryStatRow("S6", "unscoped", 8192, 12))));

        assertNull(note, "distinct query profiles should not be reported as duplicates: " + note);
    }

    @Test
    void pointScenariosFormAnItemSizeLadderOverOneProfile() {
        // Only document size varies. Page size is meaningless for point operations, so it must
        // stay at the baseline or a gap between S1, S2 and S3 would have more than one cause.
        assertEquals(1024, Scenarios.docSizeFor("S1", 1024));
        assertEquals(8192, Scenarios.docSizeFor("S2", 1024));
        assertEquals(65_536, Scenarios.docSizeFor("S3", 1024));
        assertEquals(100, Scenarios.pageSizeFor("S2", 100));
        assertEquals(100, Scenarios.pageSizeFor("S3", 100));

        // S2 carries S6's item size so a point cost and a query cost can be read at the same
        // document size.
        assertEquals(Scenarios.docSizeFor("S6", 1024), Scenarios.docSizeFor("S2", 1024));

        assertFalse(Scenarios.isQuery("S2"), "S2 is a point scenario");
        assertFalse(Scenarios.isQuery("S3"), "S3 is a point scenario");
    }

    @Test
    void eachPointScenarioDescribesItselfDistinctly() {
        // The report renders one purpose per scenario; identical text would invite readers to
        // treat three different item sizes as a repeated measurement.
        String s1 = Scenarios.purpose("S1");
        String s2 = Scenarios.purpose("S2");
        String s6 = Scenarios.purpose("S3");
        assertNotEquals(s1, s2);
        assertNotEquals(s2, s6);
        assertNotEquals(s1, s6);
        assertTrue(s2.contains("eight times"), s2);
        assertTrue(s6.contains("sixty-four times"), s6);
    }

    @Test
    void pointScenariosAreNotDuplicatesOfEachOther() {
        String note = Scenarios.duplicateScenarioNote(Scenarios.profiles(List.of(
                pointStatRow("S1", 1024),
                pointStatRow("S2", 8192),
                pointStatRow("S3", 65_536))));

        assertNull(note, "distinct point profiles should not be reported as duplicates: " + note);
    }

    @Test
    void docSizeLabelDistinguishesNoBodyFromASize() {
        assertEquals("no body", Scenarios.docSizeLabel(0));
        assertEquals("1024 B", Scenarios.docSizeLabel(1024));
    }

    private static StatRow queryStatRow(String scenario, String variant, int docSize, int pageSize) {
        return new StatRow("cosmos", "query", "query", scenario, variant, 8, docSize, pageSize,
                1, 100, 100, 10.0, 20.0, 30.0, 30.0, 15.0, 1.0,
                5.0, 5.0, 25.0, 80.0, 80.0, 80.0, 1.0,
                "RU", 1.0, 1.0, 80.0, "RU/s", 400.0, 20.0,
                0, 0.0, null, null, 0.0);
    }

    private static StatRow pointStatRow(String scenario, int docSize) {
        return new StatRow("cosmos", "read", "read", scenario, null, 8, docSize, null,
                1, 100, 100, 10.0, 20.0, 30.0, 30.0, 15.0, 1.0,
                5.0, 5.0, 25.0, 80.0, 80.0, 80.0, 1.0,
                "RU", 1.0, 1.0, 80.0, "RU/s", 400.0, 20.0,
                0, 0.0, null, null, 0.0);
    }

    private static StatRow statRow(String scenario, String workload, String operation, String variant) {
        return new StatRow("cosmos", operation, workload, scenario, variant, 8, 1024, 100,
                1, 100, 100, 10.0, 20.0, 30.0, 30.0, 15.0, 1.0,
                5.0, 5.0, 25.0, 80.0, 80.0, 80.0, 1.0,
                "RU", 1.0, 1.0, 80.0, "RU/s", 400.0, 20.0,
                0, 0.0, null, null, 0.0);
    }
}
