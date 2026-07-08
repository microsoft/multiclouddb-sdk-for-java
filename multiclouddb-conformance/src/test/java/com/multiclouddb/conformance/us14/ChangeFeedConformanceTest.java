// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us14;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import com.multiclouddb.api.changefeed.internal.CursorAnchor;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.CursorTokenCodec;
import com.multiclouddb.api.changefeed.internal.PartitionPosition;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for User Story 14 — Portable Change Feed (FR-cf-001 … FR-cf-014).
 * <p>
 * Verifies the three-primitive change-feed API ({@link ChangeFeedCursor#now()},
 * {@link MulticloudDbClient#listCursors(ResourceAddress) listCursors},
 * {@link MulticloudDbClient#readChanges(ResourceAddress, ChangeFeedCursor) readChanges})
 * across providers.
 *
 * <h3>Provider provisioning prerequisites</h3>
 * The CREATE / UPDATE / DELETE distinction surfaced by {@link ChangeType} requires
 * provider-specific setup that the SDK does <em>not</em> auto-perform on
 * {@code ensureContainer}:
 * <ul>
 *   <li><b>Cosmos</b> — container must be created with All-Versions-and-Deletes
 *       (AVAD) change-feed mode and continuous backup enabled.</li>
 *   <li><b>DynamoDB</b> — table stream enabled with
 *       {@code StreamSpecification(NEW_AND_OLD_IMAGES)}.</li>
 *   <li><b>Spanner</b> — {@code CREATE CHANGE STREAM <name> FOR <table>
 *       OPTIONS (value_capture_type = 'NEW_ROW')}.</li>
 * </ul>
 * Subclasses are responsible for guaranteeing the test resource has been
 * provisioned with these settings (typically via the per-provider conformance
 * harness or an emulator-side setup script).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ChangeFeedConformanceTest {

    /** Build a client targeting this provider's change-feed-enabled resource. */
    protected abstract MulticloudDbClient createClient();

    /** Resource that has been provisioned with the provider-specific change-feed setup. */
    protected abstract ResourceAddress getAddress();

    /**
     * Whether the provider configuration under test surfaces the full
     * CREATE / UPDATE / DELETE distinction.
     * <p>
     * All three v1 provider configurations (Cosmos AVAD, Dynamo
     * {@code NEW_AND_OLD_IMAGES}, Spanner {@code NEW_ROW}) do, so this
     * defaults to {@code true} and v1 subclasses should not override.
     * The hook is kept on the abstract suite as forward-compat for a
     * future provider whose change-feed mode genuinely cannot distinguish
     * the three event types — that provider's subclass would override to
     * {@code false}, and tests {@code FR-cf-003} (CREATE) and
     * {@code FR-cf-005} (DELETE) would skip via
     * {@link Assumptions#assumeTrue(boolean, String)}.
     */
    protected boolean supportsCreateUpdateDeleteDistinction() {
        return true;
    }

    /**
     * How long to wait between a write and the corresponding event surfacing on
     * the change feed. Providers with eventual / lag-prone delivery should override
     * (Cosmos &lt; 5s, Dynamo &lt; 10s, Spanner &lt; 5s typically).
     */
    protected Duration propagationTimeout() {
        return Duration.ofSeconds(15);
    }

    /**
     * Per-call poll interval while draining all available pages.
     */
    protected Duration pollInterval() {
        return Duration.ofMillis(250);
    }

    private MulticloudDbClient client;

    @BeforeEach
    void setUp() {
        client = createClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
    }

    // -------------------------------------------- FR-cf-001
    @Test
    @Order(1)
    @DisplayName("FR-cf-001: listCursors returns >=1 cursor for a provisioned resource")
    void listCursorsReturnsAtLeastOne() {
        List<ChangeFeedCursor> cursors = client.listCursors(getAddress());
        assertNotNull(cursors, "listCursors must never return null");
        assertFalse(cursors.isEmpty(),
                "listCursors must return >=1 cursor for a provisioned resource");
    }

    // -------------------------------------------- FR-cf-002
    @Test
    @Order(2)
    @DisplayName("FR-cf-002: cursor tokens round-trip (toToken / fromToken) preserve position")
    void cursorTokenRoundTrip() {
        List<ChangeFeedCursor> cursors = client.listCursors(getAddress());
        for (ChangeFeedCursor c : cursors) {
            String wire = c.toToken();
            ChangeFeedCursor revived = ChangeFeedCursor.fromToken(wire);
            assertEquals(c.token().providerId(), revived.token().providerId());
            assertEquals(c.token().resource(), revived.token().resource());
            assertEquals(c.token().anchor(), revived.token().anchor());
            assertEquals(c.token().partitions(), revived.token().partitions());
        }
    }

    // -------------------------------------------- FR-cf-003
    @Test
    @Order(3)
    @DisplayName("FR-cf-003: CREATE event surfaces after upsert of a new key")
    void createEventSurfacesAfterUpsert() throws Exception {
        Assumptions.assumeTrue(supportsCreateUpdateDeleteDistinction(),
                "Provider configuration under test does not surface the "
                        + "CREATE/UPDATE distinction (e.g., Cosmos LatestVersion mode)");
        ChangeFeedCursor[] cursors = client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]);
        MulticloudDbKey key = ConformanceHarness.uniqueKey("cf-create");
        try {
            client.upsert(getAddress(), key, Map.of("v", 1));
            ChangeEvent ev = waitForEventByKey(cursors, key, propagationTimeout());
            assertNotNull(ev, "expected CREATE event for key " + key + " within "
                    + propagationTimeout().toMillis() + "ms");
            assertEquals(ChangeType.CREATE, ev.type(),
                    "first event for a fresh key must be CREATE");
        } finally {
            ConformanceHarness.safeDelete(client, getAddress(), key);
        }
    }

    // -------------------------------------------- FR-cf-004
    @Test
    @Order(4)
    @DisplayName("FR-cf-004: UPDATE event surfaces after upsert of an existing key")
    void updateEventSurfacesAfterUpsert() throws Exception {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("cf-update");
        client.upsert(getAddress(), key, Map.of("v", 1));
        ChangeFeedCursor[] cursors = client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]);
        try {
            client.upsert(getAddress(), key, Map.of("v", 2));
            ChangeEvent ev = waitForEventByKey(cursors, key, propagationTimeout());
            assertNotNull(ev, "expected UPDATE event for key " + key);
            assertEquals(ChangeType.UPDATE, ev.type(),
                    "second upsert of an existing key must surface as UPDATE");
        } finally {
            ConformanceHarness.safeDelete(client, getAddress(), key);
        }
    }

    // -------------------------------------------- FR-cf-005
    @Test
    @Order(5)
    @DisplayName("FR-cf-005: DELETE event surfaces after delete")
    void deleteEventSurfacesAfterDelete() throws Exception {
        Assumptions.assumeTrue(supportsCreateUpdateDeleteDistinction(),
                "Provider configuration under test does not surface DELETE events "
                        + "(e.g., Cosmos LatestVersion mode)");
        MulticloudDbKey key = ConformanceHarness.uniqueKey("cf-delete");
        client.upsert(getAddress(), key, Map.of("v", 1));
        ChangeFeedCursor[] cursors = client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]);
        client.delete(getAddress(), key);
        ChangeEvent ev = waitForEventByKey(cursors, key, propagationTimeout());
        assertNotNull(ev, "expected DELETE event for key " + key);
        assertEquals(ChangeType.DELETE, ev.type(),
                "delete must surface as DELETE");
    }

    // -------------------------------------------- FR-cf-006
    @Test
    @Order(6)
    @DisplayName("FR-cf-006: readChanges(now()) surfaces no events from before the call")
    void nowCursorIgnoresPriorEvents() throws Exception {
        // Generate some "noise" before now()
        MulticloudDbKey noiseKey = ConformanceHarness.uniqueKey("cf-noise");
        client.upsert(getAddress(), noiseKey, Map.of("v", 1));
        Thread.sleep(propagationTimeout().toMillis() / 3);

        // Mint cursor exactly at the live tip
        ChangeFeedCursor sentinel = ChangeFeedCursor.now();
        // Hydrate the sentinel before generating the next write
        ChangeFeedPage hydrate = client.readChanges(getAddress(), sentinel);
        ChangeFeedCursor liveTip = hydrate.nextCursor();

        // Now write a key — only this key should appear from liveTip onward
        MulticloudDbKey newKey = ConformanceHarness.uniqueKey("cf-after-now");
        try {
            client.upsert(getAddress(), newKey, Map.of("v", 1));
            // Collect every event seen between mint and the matching newKey
            // event so the noiseKey assertion below has real material to check.
            // The earlier `assertNotEquals(noiseKey, ev.key())` could never
            // fail because waitForEventByKey only ever returns events
            // matching `newKey` — making FR-cf-006 unenforced.
            List<ChangeEvent> seen = new java.util.ArrayList<>();
            ChangeEvent target = waitForEventByKeyCollecting(
                    new ChangeFeedCursor[]{liveTip}, newKey, propagationTimeout(), seen);
            assertNotNull(target, "expected the post-now() key to surface");
            assertTrue(seen.stream().noneMatch(e -> e.key().equals(noiseKey)),
                    "now() must not surface keys written before the call; saw events for: "
                            + seen.stream().map(e -> e.key().toString())
                                    .collect(java.util.stream.Collectors.toList()));
        } finally {
            ConformanceHarness.safeDelete(client, getAddress(), noiseKey);
            ConformanceHarness.safeDelete(client, getAddress(), newKey);
        }
    }

    // -------------------------------------------- FR-cf-007
    @Test
    @Order(7)
    @DisplayName("FR-cf-007: resuming from a saved nextCursor token continues after the checkpoint")
    void resumeFromSavedToken() throws Exception {
        ChangeFeedCursor[] cursors = client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]);
        MulticloudDbKey first = ConformanceHarness.uniqueKey("cf-resume-1");
        MulticloudDbKey second = ConformanceHarness.uniqueKey("cf-resume-2");
        try {
            client.upsert(getAddress(), first, Map.of("v", 1));
            ChangeEvent firstEvent = waitForEventByKey(cursors, first, propagationTimeout());
            assertNotNull(firstEvent);

            // Capture the cursor *after* observing the first event.
            // Re-listCursors and drain to ensure we are caught up before the second write.
            ChangeFeedCursor[] resumeCursors = drainToCaughtUp(
                    client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]));
            // Persist as wire form
            String[] wires = new String[resumeCursors.length];
            for (int i = 0; i < wires.length; i++) wires[i] = resumeCursors[i].toToken();

            client.upsert(getAddress(), second, Map.of("v", 1));

            // Restore and read
            ChangeFeedCursor[] restored = new ChangeFeedCursor[wires.length];
            for (int i = 0; i < wires.length; i++) {
                restored[i] = ChangeFeedCursor.fromToken(wires[i]);
            }
            ChangeEvent secondEvent = waitForEventByKey(restored, second, propagationTimeout());
            assertNotNull(secondEvent, "second event must surface on restored cursors");
        } finally {
            ConformanceHarness.safeDelete(client, getAddress(), first);
            ConformanceHarness.safeDelete(client, getAddress(), second);
        }
    }

    // -------------------------------------------- FR-cf-008
    @Test
    @Order(8)
    @DisplayName("FR-cf-008: client-side aged-out token (issued > 24h ago) raises CursorExpiredException")
    void agedOutTokenRaisesCursorExpired() {
        // Hand-craft a token with issuedAt > 24h ago
        long longAgo = System.currentTimeMillis() - (CursorTokenCodec.MAX_TOKEN_AGE_MILLIS + 1000);
        CursorToken aged = new CursorToken(
                client.providerId(),
                getAddress(),
                longAgo,
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("any", null)));
        String wire = CursorTokenCodec.encode(aged);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> ChangeFeedCursor.fromToken(wire));
        assertEquals(CursorTokenCodec.REASON_TOKEN_AGED_OUT,
                ex.error().providerDetails().get("reason"));
    }

    // -------------------------------------------- FR-cf-009
    @Test
    @Order(9)
    @DisplayName("FR-cf-009: cross-provider token raises CursorExpiredException(PROVIDER_MISMATCH)")
    void crossProviderTokenRaises() {
        // Mint a token claiming a different provider than the active client's.
        String foreignProviderId = client.providerId().id().equals("cosmos") ? "dynamo" : "cosmos";
        CursorToken foreign = new CursorToken(
                com.multiclouddb.api.ProviderId.fromId(foreignProviderId),
                getAddress(),
                System.currentTimeMillis(),
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("any", null)));
        ChangeFeedCursor cursor = new ChangeFeedCursor(foreign);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> client.readChanges(getAddress(), cursor));
        assertEquals(CursorTokenCodec.REASON_PROVIDER_MISMATCH,
                ex.error().providerDetails().get("reason"));
    }

    // -------------------------------------------- FR-cf-010
    @Test
    @Order(10)
    @DisplayName("FR-cf-010: wrong-resource token raises CursorExpiredException(RESOURCE_MISMATCH)")
    void wrongResourceTokenRaises() {
        ResourceAddress wrong = new ResourceAddress(
                getAddress().database(), "definitely_not_a_real_collection_xyz");
        CursorToken minted = new CursorToken(
                client.providerId(),
                wrong,
                System.currentTimeMillis(),
                CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("any", null)));
        ChangeFeedCursor cursor = new ChangeFeedCursor(minted);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> client.readChanges(getAddress(), cursor));
        assertEquals(CursorTokenCodec.REASON_RESOURCE_MISMATCH,
                ex.error().providerDetails().get("reason"));
    }

    // -------------------------------------------- FR-cf-011
    @Test
    @Order(11)
    @DisplayName("FR-cf-011: malformed Base64 garbage raises CursorExpiredException(MALFORMED)")
    void malformedTokenRaises() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> ChangeFeedCursor.fromToken("!!! not a token !!!"));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    // -------------------------------------------- FR-cf-012
    @Test
    @Order(12)
    @DisplayName("FR-cf-012: nextCursor on an empty page is non-null and re-callable")
    void emptyPageNextCursorIsCallable() {
        ChangeFeedCursor[] cursors = drainToCaughtUp(
                client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]));
        for (ChangeFeedCursor c : cursors) {
            assertNotNull(c, "drained cursor must be non-null");
            // Reading from the caught-up cursor returns an empty page with a non-null nextCursor.
            ChangeFeedPage empty = client.readChanges(getAddress(), c);
            assertNotNull(empty.nextCursor(), "empty page must still carry a non-null nextCursor");
            // And calling readChanges again must not throw.
            assertDoesNotThrow(() -> client.readChanges(getAddress(), empty.nextCursor()));
        }
    }

    // -------------------------------------------- FR-cf-013
    @Test
    @Order(13)
    @DisplayName("FR-cf-013: hasMore flips false once the cursor catches up")
    void hasMoreFlipsFalseOnceCaughtUp() {
        ChangeFeedCursor[] cursors = client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]);
        ChangeFeedCursor[] caughtUp = drainToCaughtUp(cursors);
        for (ChangeFeedCursor c : caughtUp) {
            ChangeFeedPage page = client.readChanges(getAddress(), c);
            assertFalse(page.hasMore(),
                    "a freshly drained cursor must report hasMore=false on the next read");
        }
    }

    // -------------------------------------------- FR-cf-014
    @Test
    @Order(14)
    @DisplayName("FR-cf-014: nextCursor's token issued-at is fresh, not the original mint time")
    void nextCursorIssuedAtIsFresh() {
        long beforeRead = System.currentTimeMillis();
        ChangeFeedCursor[] cursors = client.listCursors(getAddress()).toArray(new ChangeFeedCursor[0]);
        for (ChangeFeedCursor c : cursors) {
            ChangeFeedPage page = client.readChanges(getAddress(), c);
            long issuedAt = page.nextCursor().token().issuedAtEpochMillis();
            long ageMillis = System.currentTimeMillis() - issuedAt;
            assertTrue(issuedAt >= beforeRead - 5000,
                    "nextCursor.issuedAt (" + issuedAt + ") must be at or after the read started ("
                            + beforeRead + "); age=" + ageMillis + "ms");
            assertTrue(ageMillis < 60_000,
                    "nextCursor.issuedAt must be recent (<60s old); was " + ageMillis + "ms");
        }
    }

    // ────────────────────────────────────────── helpers ──────────────────────────────────────────

    /**
     * Round-robin across {@code cursors}, draining pages until one event matching
     * {@code key} surfaces or {@code timeout} elapses.
     * Returns the first matching event, or {@code null} on timeout.
     */
    protected ChangeEvent waitForEventByKey(ChangeFeedCursor[] cursors, MulticloudDbKey key,
                                            Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        ChangeFeedCursor[] live = cursors.clone();
        while (System.currentTimeMillis() < deadline) {
            boolean progressed = false;
            for (int i = 0; i < live.length; i++) {
                ChangeFeedPage page = client.readChanges(getAddress(), live[i]);
                live[i] = page.nextCursor();
                for (ChangeEvent ev : page.events()) {
                    if (ev.key().equals(key)) return ev;
                }
                if (!page.events().isEmpty() || page.hasMore()) progressed = true;
            }
            if (!progressed) Thread.sleep(pollInterval().toMillis());
        }
        return null;
    }

    /**
     * Round-robin across {@code cursors}, draining pages until one event
     * matching {@code key} surfaces or {@code timeout} elapses. Every event
     * inspected along the way is appended to {@code sink} so the caller can
     * assert that no other keys (e.g., a "noise" key written before the
     * cursor was minted) accompanied the target.
     * <p>
     * Returns the matching event, or {@code null} on timeout. {@code sink}
     * carries every event observed during the wait (including the matching
     * event itself, in its position relative to others).
     */
    protected ChangeEvent waitForEventByKeyCollecting(ChangeFeedCursor[] cursors, MulticloudDbKey key,
                                                      Duration timeout, java.util.List<ChangeEvent> sink)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        ChangeFeedCursor[] live = cursors.clone();
        ChangeEvent target = null;
        while (System.currentTimeMillis() < deadline) {
            boolean progressed = false;
            for (int i = 0; i < live.length; i++) {
                ChangeFeedPage page = client.readChanges(getAddress(), live[i]);
                live[i] = page.nextCursor();
                for (ChangeEvent ev : page.events()) {
                    sink.add(ev);
                    if (target == null && ev.key().equals(key)) target = ev;
                }
                if (!page.events().isEmpty() || page.hasMore()) progressed = true;
            }
            if (target != null) return target;
            if (!progressed) Thread.sleep(pollInterval().toMillis());
        }
        return target;
    }


    /**
     * Drain all cursors until each one reports {@code hasMore=false} on a fresh read.
     */
    protected ChangeFeedCursor[] drainToCaughtUp(ChangeFeedCursor[] cursors) {
        ChangeFeedCursor[] live = cursors.clone();
        long deadline = System.currentTimeMillis() + propagationTimeout().toMillis() * 2;
        while (System.currentTimeMillis() < deadline) {
            boolean anyMore = false;
            for (int i = 0; i < live.length; i++) {
                ChangeFeedPage page = client.readChanges(getAddress(), live[i]);
                live[i] = page.nextCursor();
                if (page.hasMore() || !page.events().isEmpty()) anyMore = true;
            }
            if (!anyMore) return live;
        }
        return live;
    }
}
