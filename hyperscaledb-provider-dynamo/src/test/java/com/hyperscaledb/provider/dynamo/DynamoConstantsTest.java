package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.OperationNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DynamoConstants}.
 * <p>
 * Verifies that every constant has the expected value, the DynamoDB-specific
 * operation variant names are unique among themselves and do not collide with
 * any shared {@link OperationNames}, and that the header constant used for
 * extracting request IDs from Scan responses is correct.
 */
class DynamoConstantsTest {

    // ── Config keys ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONFIG_REGION key value")
    void configRegionKey() {
        assertEquals("region", DynamoConstants.CONFIG_REGION);
    }

    @Test
    @DisplayName("CONFIG_ENDPOINT key value")
    void configEndpointKey() {
        assertEquals("endpoint", DynamoConstants.CONFIG_ENDPOINT);
    }

    @Test
    @DisplayName("CONFIG_ACCESS_KEY_ID key value")
    void configAccessKeyIdKey() {
        assertEquals("accessKeyId", DynamoConstants.CONFIG_ACCESS_KEY_ID);
    }

    @Test
    @DisplayName("CONFIG_SECRET_ACCESS_KEY key value")
    void configSecretAccessKeyKey() {
        assertEquals("secretAccessKey", DynamoConstants.CONFIG_SECRET_ACCESS_KEY);
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("REGION_DEFAULT is us-east-1")
    void regionDefault() {
        assertEquals("us-east-1", DynamoConstants.REGION_DEFAULT);
    }

    // ── Attribute names ───────────────────────────────────────────────────────

    @Test
    @DisplayName("ATTR_PARTITION_KEY value")
    void attrPartitionKey() {
        assertEquals("partitionKey", DynamoConstants.ATTR_PARTITION_KEY);
    }

    @Test
    @DisplayName("ATTR_SORT_KEY value")
    void attrSortKey() {
        assertEquals("sortKey", DynamoConstants.ATTR_SORT_KEY);
    }

    // ── Table naming ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("TABLE_NAME_SEPARATOR is double underscore")
    void tableNameSeparator() {
        assertEquals("__", DynamoConstants.TABLE_NAME_SEPARATOR);
    }

    @Test
    @DisplayName("TABLE_BILLING_MODE is PAY_PER_REQUEST")
    void tableBillingMode() {
        assertEquals(BillingMode.PAY_PER_REQUEST, DynamoConstants.TABLE_BILLING_MODE);
    }

    @Test
    @DisplayName("KEY_ATTRIBUTE_TYPE is S (String)")
    void keyAttributeType() {
        assertEquals(ScalarAttributeType.S, DynamoConstants.KEY_ATTRIBUTE_TYPE);
    }

    // ── Paging ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PAGE_SIZE_DEFAULT is 100")
    void pageSizeDefault() {
        assertEquals(100, DynamoConstants.PAGE_SIZE_DEFAULT);
    }

    // ── Query constants ───────────────────────────────────────────────────────

    @Test
    @DisplayName("QUERY_SELECT_ALL_COSMOS is SELECT * FROM c")
    void querySelectAllCosmos() {
        assertEquals("SELECT * FROM c", DynamoConstants.QUERY_SELECT_ALL_COSMOS);
    }

    @Test
    @DisplayName("FILTER_PARAM_PREFIX is colon")
    void filterParamPrefix() {
        assertEquals(":", DynamoConstants.FILTER_PARAM_PREFIX);
    }

    @Test
    @DisplayName("SQL_WHERE is WHERE")
    void sqlWhere() {
        assertEquals("WHERE", DynamoConstants.SQL_WHERE);
    }

    @Test
    @DisplayName("PARTIQL_PARTITION_KEY wraps ATTR_PARTITION_KEY in quotes")
    void partiqlPartitionKey() {
        assertEquals("\"" + DynamoConstants.ATTR_PARTITION_KEY + "\"", DynamoConstants.PARTIQL_PARTITION_KEY);
    }

    @Test
    @DisplayName("PARTIQL_PARAM_PLACEHOLDER is question mark")
    void partiqlParamPlaceholder() {
        assertEquals("?", DynamoConstants.PARTIQL_PARAM_PLACEHOLDER);
    }

    @Test
    @DisplayName("SCAN_PARTITION_KEY_PARAM has correct expression attribute value name")
    void scanPartitionKeyParam() {
        assertEquals(DynamoConstants.FILTER_PARAM_PREFIX + "_pkval", DynamoConstants.SCAN_PARTITION_KEY_PARAM,
                "Scan partition key param must be FILTER_PARAM_PREFIX + '_pkval'");
    }

    // ── HTTP header ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("HEADER_REQUEST_ID is x-amzn-requestid")
    void headerRequestId() {
        assertEquals("x-amzn-requestid", DynamoConstants.HEADER_REQUEST_ID);
    }

    // ── DynamoDB-specific operation name variants ─────────────────────────────

    @Test
    @DisplayName("OP_QUERY_PARTIQL value")
    void opQueryPartiql() {
        assertEquals("query(partiql)", DynamoConstants.OP_QUERY_PARTIQL);
    }

    @Test
    @DisplayName("OP_QUERY_SCAN value")
    void opQueryScan() {
        assertEquals("query(scan)", DynamoConstants.OP_QUERY_SCAN);
    }

    @Test
    @DisplayName("OP_QUERY_SCAN_FILTER value")
    void opQueryScanFilter() {
        assertEquals("query(scan+filter)", DynamoConstants.OP_QUERY_SCAN_FILTER);
    }

    @Test
    @DisplayName("DynamoDB-specific op names are unique among themselves")
    void dynamoSpecificOpsAreUnique() {
        List<String> dynamo = List.of(
                DynamoConstants.OP_QUERY_PARTIQL,
                DynamoConstants.OP_QUERY_SCAN,
                DynamoConstants.OP_QUERY_SCAN_FILTER
        );
        long distinct = dynamo.stream().distinct().count();
        assertEquals(dynamo.size(), distinct,
                "DynamoDB-specific operation names must be unique");
    }

    @Test
    @DisplayName("DynamoDB-specific op names do not collide with shared OperationNames")
    void dynamoSpecificOpsDoNotCollideWithShared() {
        List<String> shared = List.of(
                OperationNames.CREATE, OperationNames.READ, OperationNames.UPDATE,
                OperationNames.UPSERT, OperationNames.DELETE, OperationNames.QUERY,
                OperationNames.QUERY_WITH_TRANSLATION,
                OperationNames.ENSURE_DATABASE, OperationNames.ENSURE_CONTAINER
        );
        List<String> dynamoSpecific = List.of(
                DynamoConstants.OP_QUERY_PARTIQL,
                DynamoConstants.OP_QUERY_SCAN,
                DynamoConstants.OP_QUERY_SCAN_FILTER
        );
        for (String dynOp : dynamoSpecific) {
            assertFalse(shared.contains(dynOp),
                    "DynamoDB-specific op '" + dynOp + "' must not collide with a shared OperationNames constant");
        }
    }
}

