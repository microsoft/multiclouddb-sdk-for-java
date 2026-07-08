// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the reserved-field-name rejection contract: a user document that
 * contains a field named {@code "data"} (which collides with the Spanner
 * provider's internal {@code FIELD_DATA} metadata column) must throw a typed
 * {@link MulticloudDbException} with category
 * {@link MulticloudDbErrorCategory#INVALID_REQUEST}, rather than silently
 * drop the field. Silent drop would produce cross-provider data loss because
 * Cosmos and DynamoDB persist user fields named {@code "data"}.
 *
 * <p>The rejection happens before any network call, so this test does not
 * need a running emulator. No provider tag is set so the unit profile picks
 * the test up.
 */
class SpannerReservedFieldTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "table");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private SpannerProviderClient client;

    @BeforeEach
    void setUp() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection(Map.of(
                        SpannerConstants.CONFIG_PROJECT_ID, "test-project",
                        SpannerConstants.CONFIG_INSTANCE_ID, "test-instance",
                        SpannerConstants.CONFIG_DATABASE_ID, "test-db",
                        SpannerConstants.CONFIG_EMULATOR_HOST, "localhost:1"))
                .build();
        client = new SpannerProviderClient(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Bypass slow Spanner.close() shutdown — the rejection path never
        // reaches a network call.
        Field closedField = SpannerProviderClient.class.getDeclaredField("closed");
        closedField.setAccessible(true);
        closedField.setBoolean(client, true);
    }

    private static void assertReservedFieldRejected(MulticloudDbException e, String offendingFieldName) {
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category(),
                "reserved-field collision must surface INVALID_REQUEST, not "
                        + e.error().category());
        assertEquals(ProviderId.SPANNER, e.error().provider());
        assertTrue(e.error().message() != null
                        && e.error().message().contains(SpannerConstants.FIELD_DATA),
                "error message must cite the canonical reserved field name; got: "
                        + e.error().message());
        assertTrue(e.error().message() != null
                        && e.error().message().contains(offendingFieldName),
                "error message must echo the actual offending field name '" + offendingFieldName
                        + "' (so the user knows which key in their document to rename); got: "
                        + e.error().message());
    }

    private static void assertReservedFieldRejected(MulticloudDbException e) {
        // Default helper for the canonical lowercase 'data' case.
        assertReservedFieldRejected(e, SpannerConstants.FIELD_DATA);
    }

    @Test
    @DisplayName("create() with reserved field 'data' throws INVALID_REQUEST")
    void createRejectsReservedDataField() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("data", "user-payload");
        doc.put("other", "ok");
        assertReservedFieldRejected(assertThrows(MulticloudDbException.class,
                () -> client.create(ADDR, KEY, doc, null)));
    }

    @Test
    @DisplayName("update() with reserved field 'data' throws INVALID_REQUEST")
    void updateRejectsReservedDataField() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("data", "user-payload");
        assertReservedFieldRejected(assertThrows(MulticloudDbException.class,
                () -> client.update(ADDR, KEY, doc, null)));
    }

    @Test
    @DisplayName("upsert() with reserved field 'data' throws INVALID_REQUEST")
    void upsertRejectsReservedDataField() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("data", "user-payload");
        doc.put("status", "active");
        assertReservedFieldRejected(assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDR, KEY, doc, null)));
    }
    // ---- Case-insensitive coverage (regression for round-4 review: Spanner
    // column names are case-insensitive, so 'Data' / 'DATA' / 'dAtA' all
    // collide with the internal FIELD_DATA column. Earlier builds rejected
    // only the exact lowercase 'data', letting other-case variants slip past
    // validation and surface as a deep INVALID_ARGUMENT from the Spanner
    // client instead of our friendly INVALID_REQUEST). ----

    @Test
    @DisplayName("create() with reserved field 'Data' (capital-D) throws INVALID_REQUEST")
    void createRejectsReservedDataField_capitalD() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("Data", "user-payload");
        doc.put("other", "ok");
        assertReservedFieldRejected(assertThrows(MulticloudDbException.class,
                () -> client.create(ADDR, KEY, doc, null)), "Data");
    }

    @Test
    @DisplayName("update() with reserved field 'DATA' (all-caps) throws INVALID_REQUEST")
    void updateRejectsReservedDataField_allCaps() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("DATA", "user-payload");
        assertReservedFieldRejected(assertThrows(MulticloudDbException.class,
                () -> client.update(ADDR, KEY, doc, null)), "DATA");
    }

    @Test
    @DisplayName("upsert() with reserved field 'dAtA' (mixed-case) throws INVALID_REQUEST")
    void upsertRejectsReservedDataField_mixedCase() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("dAtA", "user-payload");
        doc.put("status", "active");
        assertReservedFieldRejected(assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDR, KEY, doc, null)), "dAtA");
    }
}
