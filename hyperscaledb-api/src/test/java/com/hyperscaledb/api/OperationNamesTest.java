package com.hyperscaledb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OperationNames}.
 * <p>
 * Verifies that every constant has the expected string value and that all
 * values are unique so no two operations share the same name (which would
 * make log and error correlation ambiguous).
 */
class OperationNamesTest {

    @Test
    @DisplayName("CREATE has correct value")
    void createValue() {
        assertEquals("create", OperationNames.CREATE);
    }

    @Test
    @DisplayName("READ has correct value")
    void readValue() {
        assertEquals("read", OperationNames.READ);
    }

    @Test
    @DisplayName("UPDATE has correct value")
    void updateValue() {
        assertEquals("update", OperationNames.UPDATE);
    }

    @Test
    @DisplayName("UPSERT has correct value")
    void upsertValue() {
        assertEquals("upsert", OperationNames.UPSERT);
    }

    @Test
    @DisplayName("DELETE has correct value")
    void deleteValue() {
        assertEquals("delete", OperationNames.DELETE);
    }

    @Test
    @DisplayName("QUERY has correct value")
    void queryValue() {
        assertEquals("query", OperationNames.QUERY);
    }

    @Test
    @DisplayName("QUERY_WITH_TRANSLATION has correct value")
    void queryWithTranslationValue() {
        assertEquals("queryWithTranslation", OperationNames.QUERY_WITH_TRANSLATION);
    }

    @Test
    @DisplayName("ENSURE_DATABASE has correct value")
    void ensureDatabaseValue() {
        assertEquals("ensureDatabase", OperationNames.ENSURE_DATABASE);
    }

    @Test
    @DisplayName("ENSURE_CONTAINER has correct value")
    void ensureContainerValue() {
        assertEquals("ensureContainer", OperationNames.ENSURE_CONTAINER);
    }

    @Test
    @DisplayName("All operation name constants are non-null and non-blank")
    void allNonNullAndNonBlank() {
        List<String> all = allNames();
        for (String name : all) {
            assertNotNull(name, "Operation name must not be null");
            assertFalse(name.isBlank(), "Operation name must not be blank");
        }
    }

    @Test
    @DisplayName("All operation name constants are unique")
    void allUnique() {
        List<String> all = allNames();
        long distinctCount = all.stream().distinct().count();
        assertEquals(all.size(), distinctCount,
                "Every operation name constant must be unique — duplicates would break log correlation");
    }

    private List<String> allNames() {
        return List.of(
                OperationNames.CREATE,
                OperationNames.READ,
                OperationNames.UPDATE,
                OperationNames.UPSERT,
                OperationNames.DELETE,
                OperationNames.QUERY,
                OperationNames.QUERY_WITH_TRANSLATION,
                OperationNames.ENSURE_DATABASE,
                OperationNames.ENSURE_CONTAINER
        );
    }
}

