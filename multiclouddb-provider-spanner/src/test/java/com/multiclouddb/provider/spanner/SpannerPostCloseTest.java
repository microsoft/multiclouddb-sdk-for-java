// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.query.TranslatedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the post-close contract for {@link SpannerProviderClient}: every
 * public entry point gated by {@code checkOpen()} must throw a typed
 * {@link MulticloudDbException} with category
 * {@link MulticloudDbErrorCategory#CLIENT_CLOSED}, rather than leaking a
 * raw {@code IllegalStateException} (which would violate the SDK's exception
 * envelope contract and force callers to string-match the message).
 *
 * <p>No emulator is required. The test sets the internal {@code closed} flag
 * via reflection rather than invoking {@link SpannerProviderClient#close()},
 * because the underlying Spanner SDK opens a background session pool eagerly
 * at construction time and the close-time shutdown of that pool would block
 * indefinitely against the unreachable emulator host configured here.
 * (The {@code close()} method itself — idempotency, double-close, etc. — is
 * covered separately by {@code CrudConformanceTests} against a real emulator.)
 * No provider tag is set so the unit profile picks the test up.
 */
class SpannerPostCloseTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "table");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private SpannerProviderClient client;

    @BeforeEach
    void setUp() throws Exception {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection(Map.of(
                        SpannerConstants.CONFIG_PROJECT_ID, "test-project",
                        SpannerConstants.CONFIG_INSTANCE_ID, "test-instance",
                        SpannerConstants.CONFIG_DATABASE_ID, "test-db",
                        // Emulator mode short-circuits credential lookup; the
                        // host is never actually contacted because all tests
                        // here exit through checkOpen() before any network call.
                        SpannerConstants.CONFIG_EMULATOR_HOST, "localhost:1"))
                .build();
        client = new SpannerProviderClient(config);

        // Flip the close flag directly so checkOpen() trips; this avoids the
        // session-pool shutdown wait inside Spanner.close().
        //
        // Note: this intentionally bypasses the real close() codepath because
        // the Spanner SDK's session pool blocks waiting for the (unreachable)
        // emulator host configured here. The real close() codepath is covered
        // end-to-end by CrudConformanceTests.postCloseOperationsThrowClientClosed
        // (@Order(21)) against a real emulator, and by CrudConformanceTests
        // .closeIsIdempotent (@Order(18), inherited by SpannerConformanceTest)
        // which calls close() twice against a real emulator. The flag-level
        // short-circuit invariant is locked in below by closeIsIdempotent().
        Field closedField = SpannerProviderClient.class.getDeclaredField("closed");
        closedField.setAccessible(true);
        closedField.setBoolean(client, true);
    }

    private static void assertClientClosed(MulticloudDbException e, String expectedOperation) {
        assertEquals(MulticloudDbErrorCategory.CLIENT_CLOSED, e.error().category(),
                "every post-close entry point must surface CLIENT_CLOSED, not "
                        + e.error().category());
        assertEquals(ProviderId.SPANNER, e.error().provider());
        // The operation field must carry the caller's actual operation, not the
        // generic "checkOpen" literal — telemetry/diagnostics consumers branch
        // on this to attribute post-close failures to the failing call.
        assertEquals(expectedOperation, e.error().operation(),
                "post-close error must attribute operation to the caller's op, not 'checkOpen'");
    }

    @Test
    @DisplayName("create() after close() throws CLIENT_CLOSED")
    void createAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.create(ADDR, KEY, Map.of("k", "v"), null)),
                OperationNames.CREATE);
    }

    @Test
    @DisplayName("read() after close() throws CLIENT_CLOSED")
    void readAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.read(ADDR, KEY, null)),
                OperationNames.READ);
    }

    @Test
    @DisplayName("update() after close() throws CLIENT_CLOSED")
    void updateAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.update(ADDR, KEY, Map.of("k", "v"), null)),
                OperationNames.UPDATE);
    }

    @Test
    @DisplayName("upsert() after close() throws CLIENT_CLOSED")
    void upsertAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDR, KEY, Map.of("k", "v"), null)),
                OperationNames.UPSERT);
    }

    @Test
    @DisplayName("delete() after close() throws CLIENT_CLOSED")
    void deleteAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.delete(ADDR, KEY, null)),
                OperationNames.DELETE);
    }

    @Test
    @DisplayName("query() after close() throws CLIENT_CLOSED")
    void queryAfterClose() {
        QueryRequest q = QueryRequest.builder().build();
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.query(ADDR, q, null)),
                OperationNames.QUERY);
    }

    @Test
    @DisplayName("queryWithTranslation() after close() throws CLIENT_CLOSED")
    void queryWithTranslationAfterClose() {
        // queryWithTranslation accepts a pre-translated SQL fragment from the
        // expression translator; the post-close guard must run before any
        // translation handoff so this path also surfaces CLIENT_CLOSED, not
        // a raw IllegalStateException. The translated payload is otherwise
        // irrelevant because checkOpen() returns first.
        QueryRequest q = QueryRequest.builder().build();
        TranslatedQuery translated = TranslatedQuery.withNamedParameters(
                "SELECT 1 FROM dummy", "1=1", java.util.Map.of());
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.queryWithTranslation(ADDR, translated, q, null)),
                OperationNames.QUERY_WITH_TRANSLATION);
    }

    @Test
    @DisplayName("ensureDatabase() after close() throws CLIENT_CLOSED")
    void ensureDatabaseAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.ensureDatabase("test-db")),
                OperationNames.ENSURE_DATABASE);
    }

    @Test
    @DisplayName("ensureContainer() after close() throws CLIENT_CLOSED")
    void ensureContainerAfterClose() {
        assertClientClosed(assertThrows(MulticloudDbException.class,
                () -> client.ensureContainer(ADDR)),
                OperationNames.ENSURE_CONTAINER);
    }

    @Test
    @DisplayName("close() is idempotent — second close short-circuits before re-entering Spanner.close()")
    void closeIsIdempotent() throws Exception {
        // The fixture's setUp() has already flipped the `closed` field to true
        // via reflection without invoking the real Spanner.close() (which would
        // block on session-pool shutdown against the unreachable emulator host
        // configured in setUp()). A correctly-guarded close() must therefore
        // short-circuit on the closed flag and never call Spanner.close() —
        // proving the DCL/synchronized guard at the top of close() is in place.
        //
        // If a regression removed that guard, close() would re-enter
        // Spanner.close() which would block on the session-pool shutdown timeout
        // against the unreachable emulator host. The tight preemptive timeout
        // turns that hang into a fast, attributable failure.
        //
        // This complements CrudConformanceTests.closeIsIdempotent (@Order(18),
        // inherited by SpannerConformanceTest) which exercises a real
        // double-close against the real emulator end-to-end. Together they
        // close the loop the finding warned about: SDK-level idempotency in
        // Spanner.close() would let a guard-less implementation pass a pure
        // behavioural test, so the flag-level structural invariant must also
        // be locked in here.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertDoesNotThrow(client::close,
                        "post-close close() must short-circuit via the closed flag, "
                                + "not re-enter Spanner.close() (which would block on "
                                + "session-pool shutdown against the unreachable emulator)"));

        // And the flag must remain set — close() must not toggle it back.
        Field closedField = SpannerProviderClient.class.getDeclaredField("closed");
        closedField.setAccessible(true);
        assertTrue(closedField.getBoolean(client),
                "closed flag must remain true after a second close() invocation");
    }
}

