// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.cosmos;

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
    @DisplayName("CONSISTENCY_LEVEL_DEFAULT is SESSION")
    void consistencyLevelDefaultIsSession() {
        assertEquals(ConsistencyLevel.SESSION, CosmosConstants.CONSISTENCY_LEVEL_DEFAULT);
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
    @DisplayName("DIAG_THRESHOLD_POINT_MS is 10 ms")
    void diagThresholdPointMs() {
        assertEquals(10L, CosmosConstants.DIAG_THRESHOLD_POINT_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_MS is 100 ms")
    void diagThresholdQueryMs() {
        assertEquals(100L, CosmosConstants.DIAG_THRESHOLD_QUERY_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_ERROR_MS is 1000 ms")
    void diagThresholdQueryErrorMs() {
        assertEquals(1000L, CosmosConstants.DIAG_THRESHOLD_QUERY_ERROR_MS);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_POINT_RU is 10.0")
    void diagThresholdPointRu() {
        assertEquals(10.0, CosmosConstants.DIAG_THRESHOLD_POINT_RU);
    }

    @Test
    @DisplayName("DIAG_THRESHOLD_QUERY_RU is 100.0")
    void diagThresholdQueryRu() {
        assertEquals(100.0, CosmosConstants.DIAG_THRESHOLD_QUERY_RU);
    }
}

