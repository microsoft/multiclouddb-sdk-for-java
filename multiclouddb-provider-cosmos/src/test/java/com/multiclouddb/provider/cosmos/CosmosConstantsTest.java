// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.ConsistencyLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CosmosConstants}.
 * <p>
 * Verifies that every constant has the expected value so a future refactor
 * cannot silently change a key or default that providers, properties files,
 * and diagnostic log lines depend on.
 */
class CosmosConstantsTest {

    // ── Config keys ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONFIG_ENDPOINT key value")
    void configEndpointKey() {
        assertEquals("endpoint", CosmosConstants.CONFIG_ENDPOINT);
    }

    @Test
    @DisplayName("CONFIG_KEY key value")
    void configKeyKey() {
        assertEquals("key", CosmosConstants.CONFIG_KEY);
    }

    @Test
    @DisplayName("CONFIG_TENANT_ID key value")
    void configTenantIdKey() {
        assertEquals("tenantId", CosmosConstants.CONFIG_TENANT_ID);
    }


    @Test
    @DisplayName("CONFIG_CONNECTION_MODE key value")
    void configConnectionModeKey() {
        assertEquals("connectionMode", CosmosConstants.CONFIG_CONNECTION_MODE);
    }

    // ── Connection mode values ────────────────────────────────────────────────

    @Test
    @DisplayName("CONNECTION_MODE_GATEWAY value")
    void connectionModeGateway() {
        assertEquals("gateway", CosmosConstants.CONNECTION_MODE_GATEWAY);
    }

    @Test
    @DisplayName("CONNECTION_MODE_DIRECT value")
    void connectionModeDirect() {
        assertEquals("direct", CosmosConstants.CONNECTION_MODE_DIRECT);
    }

    @Test
    @DisplayName("CONNECTION_MODE_DEFAULT is gateway")
    void connectionModeDefaultIsGateway() {
        assertEquals(CosmosConstants.CONNECTION_MODE_GATEWAY, CosmosConstants.CONNECTION_MODE_DEFAULT);
    }

    // ── Consistency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONFIG_CONSISTENCY_LEVEL key value")
    void configConsistencyLevelKey() {
        assertEquals("consistencyLevel", CosmosConstants.CONFIG_CONSISTENCY_LEVEL);
    }

    @Test
    @DisplayName("parseConsistencyLevel: SESSION (upper-case)")
    void parseConsistencyLevelSession() {
        assertEquals(ConsistencyLevel.SESSION, CosmosConstants.parseConsistencyLevel("SESSION"));
    }

    @Test
    @DisplayName("parseConsistencyLevel: EVENTUAL (lower-case)")
    void parseConsistencyLevelEventualLowerCase() {
        assertEquals(ConsistencyLevel.EVENTUAL, CosmosConstants.parseConsistencyLevel("eventual"));
    }

    @Test
    @DisplayName("parseConsistencyLevel: STRONG (mixed-case)")
    void parseConsistencyLevelStrongMixedCase() {
        assertEquals(ConsistencyLevel.STRONG, CosmosConstants.parseConsistencyLevel("Strong"));
    }

    @Test
    @DisplayName("parseConsistencyLevel: BOUNDED_STALENESS")
    void parseConsistencyLevelBoundedStaleness() {
        assertEquals(ConsistencyLevel.BOUNDED_STALENESS,
                CosmosConstants.parseConsistencyLevel("BOUNDED_STALENESS"));
    }

    @Test
    @DisplayName("parseConsistencyLevel: CONSISTENT_PREFIX")
    void parseConsistencyLevelConsistentPrefix() {
        assertEquals(ConsistencyLevel.CONSISTENT_PREFIX,
                CosmosConstants.parseConsistencyLevel("CONSISTENT_PREFIX"));
    }

    @Test
    @DisplayName("parseConsistencyLevel: leading/trailing whitespace is stripped")
    void parseConsistencyLevelStripsWhitespace() {
        assertEquals(ConsistencyLevel.SESSION, CosmosConstants.parseConsistencyLevel("  SESSION  "));
    }

    @Test
    @DisplayName("parseConsistencyLevel: unknown value throws IllegalArgumentException with helpful message")
    void parseConsistencyLevelUnknownThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> CosmosConstants.parseConsistencyLevel("LINEARIZABLE"));
        assertTrue(ex.getMessage().contains("LINEARIZABLE"),
                "Error message should include the bad value");
        assertTrue(ex.getMessage().toLowerCase().contains("valid"),
                "Error message should mention valid values");
    }

    @Test
    @DisplayName("parseConsistencyLevel: blank string throws IllegalArgumentException with <blank> placeholder")
    void parseConsistencyLevelBlankThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CosmosConstants.parseConsistencyLevel("   "));
        assertTrue(ex.getMessage().contains("<blank>"),
                "Blank error message should use '<blank>' placeholder; got: " + ex.getMessage());
    }

    @Test
    @DisplayName("parseConsistencyLevel: null throws IllegalArgumentException with message that does not say 'null'")
    void parseConsistencyLevelNullThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CosmosConstants.parseConsistencyLevel(null));
        assertFalse(ex.getMessage().contains("'null'"),
                "Null error message should not interpolate null as a string; got: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("must not be null"),
                "Null error message should say 'must not be null'; got: " + ex.getMessage());
    }

    // ── Document field names ──────────────────────────────────────────────────

    @Test
    @DisplayName("FIELD_ID value")
    void fieldIdValue() {
        assertEquals("id", CosmosConstants.FIELD_ID);
    }

    @Test
    @DisplayName("FIELD_PARTITION_KEY value")
    void fieldPartitionKeyValue() {
        assertEquals("partitionKey", CosmosConstants.FIELD_PARTITION_KEY);
    }

    @Test
    @DisplayName("PARTITION_KEY_PATH is /partitionKey")
    void partitionKeyPath() {
        assertEquals("/" + CosmosConstants.FIELD_PARTITION_KEY, CosmosConstants.PARTITION_KEY_PATH);
    }

    // ── Paging ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PAGE_SIZE_DEFAULT is 100")
    void pageSizeDefault() {
        assertEquals(100, CosmosConstants.PAGE_SIZE_DEFAULT);
    }

    // ── Query ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("QUERY_SELECT_ALL is SELECT * FROM c")
    void querySelectAll() {
        assertEquals("SELECT * FROM c", CosmosConstants.QUERY_SELECT_ALL);
    }

    @Test
    @DisplayName("QUERY_PARAM_PREFIX is @")
    void queryParamPrefix() {
        assertEquals("@", CosmosConstants.QUERY_PARAM_PREFIX);
    }

    // ── Error messages ────────────────────────────────────────────────────────

    @Test
    @DisplayName("ERR_ENDPOINT_REQUIRED is non-blank and mentions endpoint")
    void errEndpointRequiredNonBlank() {
        assertNotNull(CosmosConstants.ERR_ENDPOINT_REQUIRED);
        assertTrue(CosmosConstants.ERR_ENDPOINT_REQUIRED.toLowerCase().contains("endpoint"),
                "Error message should mention 'endpoint' so callers understand what is missing");
    }

    // ── Diagnostic thresholds ─────────────────────────────────────────────────

    @Test
    @DisplayName("DIAG_THRESHOLD_POINT_MS is 100 ms")
    void diagThresholdPointMs() {
        assertEquals(100L, CosmosConstants.DIAG_THRESHOLD_POINT_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_MS is 500 ms")
    void diagThresholdQueryMs() {
        assertEquals(500L, CosmosConstants.DIAG_THRESHOLD_QUERY_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_ERROR_MS is 1000 ms")
    void diagThresholdQueryErrorMs() {
        assertEquals(1000L, CosmosConstants.DIAG_THRESHOLD_QUERY_ERROR_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_POINT_RU is 30.0")
    void diagThresholdPointRu() {
        assertEquals(30.0, CosmosConstants.DIAG_THRESHOLD_POINT_RU);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_RU is 100.0")
    void diagThresholdQueryRu() {
        assertEquals(100.0, CosmosConstants.DIAG_THRESHOLD_QUERY_RU);
    }
}

