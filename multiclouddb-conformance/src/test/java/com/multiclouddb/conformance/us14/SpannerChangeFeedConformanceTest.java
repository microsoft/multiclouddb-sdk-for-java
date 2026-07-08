// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us14;

import com.google.cloud.spanner.*;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Spanner change-feed conformance, running against the Spanner Emulator.
 * <p>
 * <b>Provisioning prerequisite</b>: a change stream must be created on the test
 * table with {@code value_capture_type='NEW_ROW'}. The {@link #ensureSchema()}
 * hook creates a {@code todos_cf} table plus a {@code todos_cf_changes} change
 * stream over it.
 */
@Tag("spanner")
@Tag("emulator")
@Tag("changefeed")
class SpannerChangeFeedConformanceTest extends ChangeFeedConformanceTest {

    private static final String EMULATOR_HOST = System.getProperty(
            "spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID = "test-project";
    private static final String INSTANCE_ID = "test-instance";
    private static final String DATABASE_ID = "testdb_cf";
    private static final String TABLE = "todos_cf";
    private static final String CHANGE_STREAM = TABLE + "_changes";

    @BeforeAll
    static void ensureSchema() throws ExecutionException, InterruptedException {
        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST)
                .setProjectId(PROJECT_ID)
                .build();
        Spanner spanner = options.getService();
        try {
            InstanceAdminClient instanceAdmin = spanner.getInstanceAdminClient();
            try {
                instanceAdmin.createInstance(
                        InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                                .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                                .setDisplayName("Change-Feed Test Instance")
                                .setNodeCount(1)
                                .build())
                        .get();
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS)) {
                    throw e;
                }
            }

            DatabaseAdminClient dbAdmin = spanner.getDatabaseAdminClient();
            try {
                dbAdmin.createDatabase(INSTANCE_ID, DATABASE_ID, List.of(
                        "CREATE TABLE " + TABLE + " ("
                                + "  partitionKey STRING(MAX) NOT NULL,"
                                + "  sortKey STRING(MAX) NOT NULL,"
                                + "  v INT64,"
                                + "  data STRING(MAX)"
                                + ") PRIMARY KEY (partitionKey, sortKey)",
                        "CREATE CHANGE STREAM " + CHANGE_STREAM
                                + " FOR " + TABLE
                                + " OPTIONS (value_capture_type = 'NEW_ROW')"))
                        .get();
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS)) {
                    throw e;
                }
            }
        } finally {
            spanner.close();
        }
    }

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", PROJECT_ID)
                .connection("instanceId", INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", EMULATOR_HOST)
                .connection("changeStream." + TABLE, CHANGE_STREAM)
                .build();
        return MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE_ID, TABLE);
    }
}
