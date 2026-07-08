// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@code ensureDatabase(name)} validation contract for
 * {@link SpannerProviderClient}: a call whose {@code name} does not match
 * the {@code databaseId} the client was constructed with must throw a typed
 * {@link MulticloudDbException} with category
 * {@link MulticloudDbErrorCategory#INVALID_REQUEST}, citing both the supplied
 * and the configured database in the message — never silently succeed and
 * never leak a raw {@code IllegalArgumentException}.
 *
 * <p>The validation runs before any network call, so this test does not need
 * a running emulator. No provider tag is set so the unit profile picks the
 * test up.
 */
class SpannerEnsureDatabaseValidationTest {

    private SpannerProviderClient client;

    @AfterEach
    void tearDown() throws Exception {
        // Bypass the slow Spanner.close() shutdown wait — the test never
        // issued an operation, but the SDK's eager session pool would still
        // block close() against the unreachable emulator host. Setting the
        // closed flag directly is enough to satisfy this client's lifecycle
        // contract for the next GC cycle.
        if (client != null) {
            Field closedField = SpannerProviderClient.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            closedField.setBoolean(client, true);
        }
    }

    private SpannerProviderClient newClient(String configuredDatabase) {
        return new SpannerProviderClient(MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection(Map.of(
                        SpannerConstants.CONFIG_PROJECT_ID, "test-project",
                        SpannerConstants.CONFIG_INSTANCE_ID, "test-instance",
                        SpannerConstants.CONFIG_DATABASE_ID, configuredDatabase,
                        SpannerConstants.CONFIG_EMULATOR_HOST, "localhost:1"))
                .build());
    }

    @Test
    @DisplayName("ensureDatabase('other') throws INVALID_REQUEST when client is bound to a different database")
    void mismatchedDatabaseNameThrowsInvalidRequest() {
        client = newClient("configured-db");

        MulticloudDbException e = assertThrows(MulticloudDbException.class,
                () -> client.ensureDatabase("requested-db"));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category(),
                "name-mismatch must be INVALID_REQUEST (not CLIENT_CLOSED, not raw IllegalArgumentException)");
        assertEquals(ProviderId.SPANNER, e.error().provider());

        // Both names should appear so the operator can diagnose the mismatch
        // without having to inspect their own config to compare.
        String msg = e.error().message();
        assertNotNull(msg);
        assertTrue(msg.contains("requested-db"),
                "error message should cite the supplied database name: " + msg);
        assertTrue(msg.contains("configured-db"),
                "error message should cite the configured database name: " + msg);
    }

    @Test
    @DisplayName("ensureDatabase(null) throws INVALID_REQUEST (null != configured)")
    void nullDatabaseNameThrowsInvalidRequest() {
        client = newClient("configured-db");

        MulticloudDbException e = assertThrows(MulticloudDbException.class,
                () -> client.ensureDatabase(null));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
    }
}
