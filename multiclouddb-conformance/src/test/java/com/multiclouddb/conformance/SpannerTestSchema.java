// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.google.cloud.spanner.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Shared Spanner emulator schema setup for conformance tests.
 * <p>
 * Ensures the test instance, database, and table exist with a schema that
 * includes all columns used across the conformance test suite. Idempotent —
 * safe to call from multiple test classes regardless of execution order.
 * <p>
 * When the database already exists (e.g. created by {@code ensureContainer}
 * with a minimal schema), the table is dropped and recreated with the full
 * conformance schema.
 * <p>
 * Project / instance / emulator host are resolved from the same system
 * properties and environment variables that {@link ConformanceConfig}
 * uses to build the Spanner client, so schema provisioning always targets
 * the same Spanner instance the client connects to.
 */
public final class SpannerTestSchema {

    static final String EMULATOR_HOST = ConformanceConfig.resolve(
            "spanner.emulatorHost", "SPANNER_EMULATOR_HOST", "localhost:9010");
    static final String PROJECT_ID = ConformanceConfig.resolve(
            "spanner.projectId", "SPANNER_PROJECT_ID", "test-project");
    static final String INSTANCE_ID = ConformanceConfig.resolve(
            "spanner.instanceId", "SPANNER_INSTANCE_ID", "test-instance");

    /**
     * DDL for the conformance test table — includes all columns used across
     * CrudConformanceTests, ErrorNormalizationConformanceTest, etc.
     */
    private static final String TABLE_DDL_TEMPLATE =
            "CREATE TABLE %s ("
                    + "  partitionKey STRING(MAX) NOT NULL,"
                    + "  sortKey STRING(MAX) NOT NULL,"
                    + "  data STRING(MAX),"
                    + "  title STRING(MAX),"
                    + "  value INT64,"
                    + "  active BOOL,"
                    + "  version INT64,"
                    + "  extra STRING(MAX),"
                    + "  batch STRING(MAX),"
                    + "  status STRING(MAX),"
                    + "  priority INT64,"
                    + "  category STRING(MAX),"
                    + "  shared STRING(MAX),"
                    + "  originalOnly STRING(MAX),"
                    + "  `group` STRING(MAX),"
                    + "  marker STRING(MAX),"
                    + "  n INT64,"
                    + "  strField STRING(MAX),"
                    + "  intField INT64,"
                    + "  longField INT64,"
                    + "  bigLongField INT64,"
                    + "  doubleField FLOAT64,"
                    + "  boolTrue BOOL,"
                    + "  boolFalse BOOL,"
                    + "  nullField STRING(MAX),"
                    + "  nestedObj STRING(MAX),"
                    + "  arrayField STRING(MAX),"
                    + "  emptyArray STRING(MAX),"
                    + "  age INT64"
                    + ") PRIMARY KEY (partitionKey, sortKey)";

    /**
     * Tracks which {@code (databaseId, table)} pairs this JVM has provisioned
     * for the lifetime of the test process. Keyed on the pair rather than a
     * single boolean so a second call with different arguments doesn't
     * silently no-op (which would surface much later as a confusing
     * {@code NOT_FOUND} downstream). {@code ConcurrentHashMap.newKeySet()} +
     * {@code add()} returning {@code false}-if-present gives synchronised
     * happens-before for free — no {@code synchronized} method needed.
     */
    private static final java.util.Set<String> PROVISIONED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SpannerTestSchema() {
    }

    /**
     * Ensures the Spanner emulator has the test instance, database, and table
     * with the full conformance schema. Thread-safe and idempotent within a
     * single JVM, keyed on the {@code (databaseId, table)} pair so two test
     * classes that target different schemas both provision correctly.
     *
     * @param databaseId the database to create/ensure
     * @param table      the table name
     */
    public static void ensureSchema(String databaseId, String table)
            throws ExecutionException, InterruptedException {
        String key = databaseId + "/" + table;
        if (!PROVISIONED.add(key)) return;

        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST)
                .setProjectId(PROJECT_ID)
                .build();
        Spanner spanner = options.getService();
        try {
            // Outer guard: any unhandled failure inside the provisioning body
            // must drop the cache entry so a later test in the same JVM retries
            // cleanly. Without this, a failure on the table-recreate path below
            // would leave `PROVISIONED` populated and subsequent tests would
            // skip provisioning and run against a missing or stale schema.
            try {
                // Ensure instance
                InstanceAdminClient instanceAdmin = spanner.getInstanceAdminClient();
                try {
                    instanceAdmin.createInstance(
                            InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                                    .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                                    .setDisplayName("Test Instance")
                                    .setNodeCount(1)
                                    .build())
                            .get();
                    System.out.println("[SpannerTestSchema] Created instance: " + INSTANCE_ID);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof SpannerException se
                            && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                        System.out.println("[SpannerTestSchema] Instance already exists: " + INSTANCE_ID);
                    } else {
                        throw e;
                    }
                }

                // Ensure database + table
                DatabaseAdminClient dbAdmin = spanner.getDatabaseAdminClient();
                String tableDdl = String.format(TABLE_DDL_TEMPLATE, table);
                try {
                    dbAdmin.createDatabase(INSTANCE_ID, databaseId, List.of(tableDdl)).get();
                    System.out.println("[SpannerTestSchema] Created database: " + databaseId
                            + " with table: " + table);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof SpannerException se
                            && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                        System.out.println("[SpannerTestSchema] Database already exists: " + databaseId);
                        // Drop and recreate table to ensure full schema
                        try {
                            dbAdmin.updateDatabaseDdl(
                                    INSTANCE_ID, databaseId,
                                    List.of("DROP TABLE " + table), null).get();
                            System.out.println("[SpannerTestSchema] Dropped existing table: " + table);
                        } catch (ExecutionException ex) {
                            System.out.println("[SpannerTestSchema] Table drop skipped: "
                                    + ex.getMessage());
                        }
                        dbAdmin.updateDatabaseDdl(
                                INSTANCE_ID, databaseId,
                                List.of(tableDdl), null).get();
                        System.out.println("[SpannerTestSchema] Recreated table: " + table
                                + " with full conformance schema");
                    } else {
                        throw e;
                    }
                }
            } catch (Exception e) {
                // We are no longer in a "provisioned" state — clear the cache
                // entry so a later test sees a clean retry path. Rethrown so
                // the failure still surfaces to the caller.
                PROVISIONED.remove(key);
                throw e;
            }
        } finally {
            spanner.close();
        }
    }
}
