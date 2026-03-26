package com.hyperscaledb.conformance;

import com.hyperscaledb.api.*;
import com.google.cloud.spanner.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Spanner conformance test running against the Spanner Emulator.
 * <p>
 * Prerequisites:
 * <ul>
 * <li>Spanner Emulator running on localhost:9010 (gRPC) / localhost:9020
 * (REST)</li>
 * </ul>
 * <p>
 * The test auto-creates the instance, database, and table in
 * {@code @BeforeAll}.
 */
@Tag("spanner")
@Tag("emulator")
class SpannerConformanceTest extends CrudConformanceTests {

    private static final String EMULATOR_HOST = System.getProperty(
            "spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID = "test-project";
    private static final String INSTANCE_ID = "test-instance";
    private static final String DATABASE_ID = "testdb";
    private static final String TABLE = "todos";

    @BeforeAll
    static void ensureInstanceAndDatabase() throws ExecutionException, InterruptedException {
        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST)
                .setProjectId(PROJECT_ID)
                .build();
        Spanner spanner = options.getService();
        try {
            // Create instance (idempotent — emulator may already have it)
            InstanceAdminClient instanceAdmin = spanner.getInstanceAdminClient();
            try {
                instanceAdmin.createInstance(
                        InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                                .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                                .setDisplayName("Test Instance")
                                .setNodeCount(1)
                                .build())
                        .get();
                System.out.println("[Spanner] Created instance: " + INSTANCE_ID);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                    System.out.println("[Spanner] Instance already exists: " + INSTANCE_ID);
                } else {
                    throw e;
                }
            }

            // Create database with table schema
            DatabaseAdminClient dbAdmin = spanner.getDatabaseAdminClient();
            try {
                dbAdmin.createDatabase(INSTANCE_ID, DATABASE_ID, List.of(
                        "CREATE TABLE " + TABLE + " ("
                                + "  partitionKey STRING(MAX) NOT NULL,"
                                + "  sortKey STRING(MAX) NOT NULL,"
                                + "  title STRING(MAX),"
                                + "  value INT64,"
                                + "  active BOOL,"
                                + "  version INT64,"
                                + "  extra STRING(MAX),"
                                + "  batch STRING(MAX),"
                                + "  status STRING(MAX),"
                                + "  priority INT64,"
                                + "  category STRING(MAX)"
                                + ") PRIMARY KEY (partitionKey, sortKey)"))
                        .get();
                System.out.println("[Spanner] Created database: " + DATABASE_ID + " with table: " + TABLE);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                    System.out.println("[Spanner] Database already exists: " + DATABASE_ID);
                } else {
                    throw e;
                }
            }
        } finally {
            spanner.close();
        }
    }

    @Override
    protected HyperscaleDbClient createClient() {
        HyperscaleDbClientConfig config = HyperscaleDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", PROJECT_ID)
                .connection("instanceId", INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", EMULATOR_HOST)
                .build();
        return HyperscaleDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE_ID, TABLE);
    }
}
