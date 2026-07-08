// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-4 regression: a row that pre-dates this SDK's {@code FIELD_DATA}
 * metadata column (or whose {@code FIELD_DATA} is {@code NULL} for any other
 * reason — e.g. inserted by a sibling system writing directly to Spanner) must
 * not lose its pre-existing columns when the SDK performs a partial
 * {@code update()}.
 *
 * <p>The earlier behaviour stamped {@code FIELD_DATA} with only the keys
 * named in the current {@code update()} call. The reader then filtered the
 * row's columns by that stamp on the next {@code read()}, so every legacy
 * column the row had that wasn't in the partial update payload disappeared
 * from the user's perspective — silent data loss with no exception or log.
 *
 * <p>The fix in {@code SpannerProviderClient.update()} now tracks whether
 * pre-existing {@code FIELD_DATA} metadata was successfully parsed. If not
 * (legacy or malformed), the stamp is deliberately skipped and the row stays
 * in the "no metadata => no filtering" reader-fallback regime — so the legacy
 * columns remain visible.
 *
 * <p>This test runs only under the {@code -Pemulator-spanner} CI profile
 * because it needs a real Spanner instance (the {@code readWriteTransaction}
 * + raw-DML inserts cannot be exercised against the in-process unit harness).
 * It is tagged with both {@code spanner} and {@code emulator} so {@code -Punit}
 * (which excludes those tags) skips it.
 */
@DisplayName("Spanner — Legacy-row update() preserves pre-existing columns (round-4 regression)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("spanner")
@Tag("emulator")
class SpannerLegacyRowUpdateEmulatorTest {

    private static final String EMULATOR_HOST = System.getProperty("spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID = "test-project";
    private static final String INSTANCE_ID = "test-instance";
    private static final String DATABASE_ID = "legacyupdatetestdb";
    private static final String TABLE = "legacyupdatetests";

    private MulticloudDbClient client;
    private Spanner rawSpanner;
    private DatabaseClient rawDbClient;
    private final ResourceAddress address = new ResourceAddress(DATABASE_ID, TABLE);

    @BeforeAll
    void setUp() throws ExecutionException, InterruptedException {
        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST).setProjectId(PROJECT_ID).build();
        rawSpanner = options.getService();

        InstanceAdminClient instanceAdmin = rawSpanner.getInstanceAdminClient();
        try {
            instanceAdmin.createInstance(InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                    .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                    .setDisplayName("Test Instance").setNodeCount(1).build()).get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof SpannerException se) || se.getErrorCode() != ErrorCode.ALREADY_EXISTS)
                throw e;
        }

        DatabaseAdminClient dbAdmin = rawSpanner.getDatabaseAdminClient();
        try {
            dbAdmin.createDatabase(INSTANCE_ID, DATABASE_ID, List.of(
                    "CREATE TABLE " + TABLE + " ("
                            + "  partitionKey STRING(MAX) NOT NULL,"
                            + "  sortKey STRING(MAX) NOT NULL,"
                            + "  data STRING(MAX),"
                            + "  name STRING(MAX),"
                            + "  email STRING(MAX),"
                            + "  status STRING(MAX),"
                            + "  priority INT64"
                            + ") PRIMARY KEY (partitionKey, sortKey)")).get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof SpannerException se) || se.getErrorCode() != ErrorCode.ALREADY_EXISTS)
                throw e;
        }

        rawDbClient = rawSpanner.getDatabaseClient(DatabaseId.of(PROJECT_ID, INSTANCE_ID, DATABASE_ID));

        client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", PROJECT_ID)
                .connection("instanceId", INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", EMULATOR_HOST)
                .build());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (rawSpanner != null) rawSpanner.close();
    }

    /**
     * Seeds a row directly via the raw Spanner client without writing the
     * internal {@code data} (FIELD_DATA) metadata column — exactly the shape
     * a row would have if it pre-dated this SDK or was written by a sibling
     * system. Returns nothing; the row is keyed on the supplied PK / SK.
     */
    private void seedLegacyRow(String pk, String sk, Mutation.WriteBuilder extraColumns) {
        Mutation mutation = extraColumns.build();
        rawDbClient.write(List.of(mutation));
    }

    @Test
    @DisplayName("legacy row (FIELD_DATA NULL): partial update() preserves untouched columns on read")
    void partialUpdatePreservesLegacyColumns() {
        String pk = "u1";
        String sk = "u1";

        // 1) Seed a row bypassing the SDK so FIELD_DATA stays NULL — mimics a
        //    row that pre-dates the metadata-column rollout or was written by
        //    a sibling system that doesn't know about FIELD_DATA.
        seedLegacyRow(pk, sk, Mutation.newInsertBuilder(TABLE)
                .set("partitionKey").to(pk)
                .set("sortKey").to(sk)
                .set("name").to("Ada")
                .set("email").to("ada@x")
                .set("status").to("active")
                .set("priority").to(5L));

        // 2) Run a partial SDK update() that names only the email field.
        client.update(address, MulticloudDbKey.of(pk, sk), Map.of("email", "ada@new"), null);

        // 3) Read back via the SDK. The untouched columns (name, status,
        //    priority) MUST still be visible. With the round-4 bug, only
        //    `email` would survive because the FIELD_DATA stamp would have
        //    narrowed the visible set to just that one key. With the fix in
        //    place, the SDK leaves FIELD_DATA NULL on legacy rows and the
        //    reader's "no metadata => project every column" fallback returns
        //    all columns.
        DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk), null);
        assertNotNull(result, "read() must find the row that was updated");
        JsonNode doc = result.document();

        assertEquals("ada@new", doc.path("email").asText(),
                "new field from the partial update() must round-trip");
        assertEquals("Ada", doc.path("name").asText(),
                "legacy `name` column must survive the partial update — "
                        + "if this fails, FIELD_DATA was stamped with only the "
                        + "partial-update keys, filtering out the legacy columns "
                        + "on read (silent data loss)");
        assertEquals("active", doc.path("status").asText(),
                "legacy `status` column must survive the partial update");
        assertTrue(doc.has("priority"),
                "legacy `priority` column must survive the partial update; "
                        + "got fields=" + fieldNames(doc));
        assertEquals(5L, doc.path("priority").asLong(),
                "legacy `priority` value must round-trip unchanged");
    }

    @Test
    @DisplayName("legacy row stays in fallback mode across multiple partial updates (no progressive narrowing)")
    void multiplePartialUpdatesDoNotProgressivelyNarrow() {
        String pk = "u2";
        String sk = "u2";

        seedLegacyRow(pk, sk, Mutation.newInsertBuilder(TABLE)
                .set("partitionKey").to(pk)
                .set("sortKey").to(sk)
                .set("name").to("Bob")
                .set("email").to("bob@x")
                .set("status").to("active")
                .set("priority").to(7L));

        // Two consecutive partial updates that each name different single keys.
        // Both must leave FIELD_DATA NULL; otherwise the second update's stamp
        // would only contain `priority`, hiding `name` / `status` / `email` on
        // the subsequent read.
        client.update(address, MulticloudDbKey.of(pk, sk), Map.of("email", "bob@new"), null);
        client.update(address, MulticloudDbKey.of(pk, sk), Map.of("priority", 9L), null);

        DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk), null);
        assertNotNull(result);
        JsonNode doc = result.document();
        assertEquals("Bob", doc.path("name").asText(),
                "legacy `name` must survive both partial updates; got fields=" + fieldNames(doc));
        assertEquals("bob@new", doc.path("email").asText());
        assertEquals("active", doc.path("status").asText(),
                "legacy `status` must survive both partial updates");
        assertEquals(9L, doc.path("priority").asLong(),
                "second partial update must apply");
    }

    @Test
    @DisplayName("SDK-written row (FIELD_DATA stamped): partial update() merges field set without dropping prior keys")
    void sdkWrittenRowMergesFieldSet() {
        String pk = "u3";
        String sk = "u3";

        // Establish FIELD_DATA via create() so the row enters the metadata
        // regime, then issue a partial update that names only one of the
        // existing keys. The merged stamp must include both keys, so a
        // subsequent read returns both.
        client.create(address, MulticloudDbKey.of(pk, sk),
                Map.of("name", "Cara", "email", "cara@x"), null);
        client.update(address, MulticloudDbKey.of(pk, sk),
                Map.of("email", "cara@new"), null);

        DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk), null);
        assertNotNull(result);
        JsonNode doc = result.document();
        assertEquals("Cara", doc.path("name").asText(),
                "field written by create() must survive a later partial update — "
                        + "this is the non-regression case (round-3 fix)");
        assertEquals("cara@new", doc.path("email").asText());
    }

    /** Helper: collect the visible field names on a Jackson ObjectNode for diagnostics. */
    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
