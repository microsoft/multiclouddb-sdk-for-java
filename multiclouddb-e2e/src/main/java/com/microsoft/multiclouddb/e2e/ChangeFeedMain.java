// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.e2e;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.NewItemStateMode;
import com.multiclouddb.api.changefeed.StartPosition;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end test for the portable Change Feed API.
 *
 * <p>Exercises {@link MulticloudDbClient#readChanges} against whichever provider
 * is configured in the active properties file (Cosmos / Dynamo / Spanner).
 * The test runs three phases against a single client + collection:
 * <ol>
 *   <li><b>entireCollection round-trip + replay</b> — anchor at
 *       {@link StartPosition#now()}, seed one CREATE/UPDATE/DELETE, drain
 *       forward until all three types are observed for the seeded keys, then
 *       replay from the <em>original</em> anchor token and assert every event
 *       re-delivers (at-least-once contract).</li>
 *   <li><b>NewItemStateMode.OMIT</b> — seed a CREATE and verify the surfaced
 *       event has {@code data() == null}.</li>
 *   <li><b>maxPageSize=1 paging</b> — seed 3 CREATEs and assert the cursor
 *       returns at most one event per page while still surfacing all keys.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   mvn -pl multiclouddb-e2e process-resources exec:java \
 *       -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain
 *
 *   mvn -pl multiclouddb-e2e process-resources exec:java \
 *       -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain \
 *       -Dmulticlouddb.config=dynamo.properties
 * </pre>
 *
 * <p>Skips cleanly (with a clear message and exit code 0) when the configured
 * provider does not advertise {@link Capability#CHANGE_FEED}.
 */
public class ChangeFeedMain {

    /** Total wall-clock budget to observe the seeded events. */
    private static final long DRAIN_BUDGET_MS = 60_000;
    /** Sleep between empty pages while draining. */
    private static final long IDLE_SLEEP_MS = 500;

    private final MulticloudDbClient client;
    private final ResourceAddress address;

    ChangeFeedMain(MulticloudDbClient client, ResourceAddress address) {
        this.client = client;
        this.address = address;
    }

    public static void main(String[] args) throws Exception {

        ConfigLoader.AppConfig cfg = ConfigLoader.load("cosmos.properties");
        String database   = cfg.get("multiclouddb.database",   "e2etest");
        String collection = cfg.get("multiclouddb.collection", "products");

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Multicloud DB SDK — Change Feed E2E Test               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf(" Provider  : %s%n", cfg.sdk().provider().displayName());
        System.out.printf(" Database  : %s%n", database);
        System.out.printf(" Collection: %s%n", collection);
        System.out.println();

        try (MulticloudDbClient c = MulticloudDbClientFactory.create(cfg.sdk())) {
            new ChangeFeedMain(c, new ResourceAddress(database, collection)).run(cfg);
        }
    }

    void run(ConfigLoader.AppConfig cfg) throws Exception {

        // ── Capability gate ───────────────────────────────────────────
        System.out.println("── Capability check ───────────────────────────────────────────");
        boolean supported = client.capabilities().isSupported(Capability.CHANGE_FEED);
        System.out.printf("  CHANGE_FEED supported by %s : %s%n",
                cfg.sdk().provider().displayName(), supported ? "yes" : "no");
        if (!supported) {
            System.out.println();
            System.out.println("  Provider does not advertise CHANGE_FEED — skipping test.");
            System.out.println();
            return;
        }
        System.out.println();

        // ── Schema provisioning + warm-up ─────────────────────────────
        System.out.println("── Schema provisioning ────────────────────────────────────────");
        client.ensureDatabase(address.database());
        client.ensureContainer(address);
        System.out.println("  Database and collection are ready.");
        System.out.println();

        System.out.println("── SDK warm-up ────────────────────────────────────────────────");
        client.query(address, QueryRequest.builder().maxPageSize(1).build());
        MulticloudDbKey warmupKey = MulticloudDbKey.of("__cf_warmup__", "__cf_warmup__");
        client.upsert(address, warmupKey, Map.of("id", "__cf_warmup__"));
        client.delete(address, warmupKey);
        System.out.println("  Read and write metadata cached.");
        System.out.println();

        // Run each phase in sequence; each phase is self-contained.
        runEntireCollectionRoundTrip();
        runOmitMode();
        runMaxPageSizePaging();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf ("║  ✓  Change Feed E2E test completed on %-21s  ║%n",
                cfg.sdk().provider().displayName());
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 1: entire-collection round-trip + at-least-once replay
    // ──────────────────────────────────────────────────────────────────
    private void runEntireCollectionRoundTrip() throws Exception {
        System.out.println("── Phase 1: entireCollection round-trip ───────────────────────");

        // Capture an anchor token at now(); we will both drain forward
        // from it AND replay from it later to verify at-least-once.
        ChangeFeedRequest anchorReq = ChangeFeedRequest.builder(address)
                .startPosition(StartPosition.now())
                .maxPageSize(100)
                .build();
        ChangeFeedPage anchorPage = client.readChanges(anchorReq);
        String anchorToken = anchorPage.continuationToken();
        if (anchorToken == null) {
            throw new AssertionError(
                    "Expected non-null continuation token from StartPosition.now() seed page");
        }
        System.out.printf("  anchor token captured (len=%d)%n", anchorToken.length());

        // Seed one CREATE / UPDATE / DELETE on unique keys
        String prefix = "cf-e2e-" + UUID.randomUUID();
        String pkCreate = prefix + "-create";
        String pkUpdate = prefix + "-update";
        String pkDelete = prefix + "-delete";
        Set<String> seededKeys = Set.of(pkCreate, pkUpdate, pkDelete);

        MulticloudDbKey kCreate = MulticloudDbKey.of(pkCreate, pkCreate);
        MulticloudDbKey kUpdate = MulticloudDbKey.of(pkUpdate, pkUpdate);
        MulticloudDbKey kDelete = MulticloudDbKey.of(pkDelete, pkDelete);

        client.create(address, kCreate, doc(pkCreate, "created", 1));
        client.create(address, kUpdate, doc(pkUpdate, "initial", 1));
        client.upsert(address, kUpdate, doc(pkUpdate, "modified", 2));
        client.create(address, kDelete, doc(pkDelete, "tombstone-source", 1));
        client.delete(address, kDelete);
        System.out.println("  seeded CREATE+UPDATE+DELETE on 3 keys");

        // Drain forward — must observe all 3 event types for our keys.
        DrainResult drained = drain(anchorToken, seededKeys,
                EnumSet.of(ChangeType.CREATE, ChangeType.UPDATE, ChangeType.DELETE),
                100, NewItemStateMode.INCLUDE_IF_AVAILABLE,
                DRAIN_BUDGET_MS);
        System.out.printf("  drain: pages=%d total=%d typesSeen=%s keysSeen=%s%n",
                drained.pages, drained.totalEvents, drained.typesSeen, drained.keysSeen);
        if (!drained.typesSeen.containsAll(EnumSet.of(
                ChangeType.CREATE, ChangeType.UPDATE, ChangeType.DELETE))) {
            throw new AssertionError(
                    "Drain did not surface all of CREATE/UPDATE/DELETE within "
                            + DRAIN_BUDGET_MS + "ms. Got " + drained.typesSeen);
        }
        if (!drained.keysSeen.containsAll(seededKeys)) {
            throw new AssertionError(
                    "Drain did not surface every seeded key. Expected "
                            + seededKeys + " but saw " + drained.keysSeen);
        }

        // ── At-least-once replay ──────────────────────────────────
        // Replay from the *original* anchor token: every event we
        // observed during the forward drain MUST re-deliver. This is
        // the contractual at-least-once guarantee — token identity is
        // the only durable record of cursor position.
        System.out.println("  replaying from original anchor token (at-least-once)…");
        DrainResult replayed = drain(anchorToken, seededKeys,
                EnumSet.of(ChangeType.CREATE, ChangeType.UPDATE, ChangeType.DELETE),
                100, NewItemStateMode.INCLUDE_IF_AVAILABLE,
                DRAIN_BUDGET_MS);
        System.out.printf("  replay: pages=%d total=%d typesSeen=%s keysSeen=%s%n",
                replayed.pages, replayed.totalEvents,
                replayed.typesSeen, replayed.keysSeen);
        if (!replayed.typesSeen.containsAll(EnumSet.of(
                ChangeType.CREATE, ChangeType.UPDATE, ChangeType.DELETE))) {
            throw new AssertionError(
                    "Replay from anchor token did not re-deliver CREATE/UPDATE/DELETE — "
                            + "at-least-once contract violated. Got " + replayed.typesSeen);
        }
        if (!replayed.keysSeen.containsAll(seededKeys)) {
            throw new AssertionError(
                    "Replay from anchor token did not re-deliver every seeded key. "
                            + "Expected " + seededKeys + " but saw " + replayed.keysSeen);
        }

        // Cleanup the seeded items (kDelete already deleted above)
        client.delete(address, kCreate);
        client.delete(address, kUpdate);
        System.out.println("  ✓ entireCollection round-trip + replay passed");
        System.out.println();
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 2: NewItemStateMode.OMIT yields events with null data()
    // ──────────────────────────────────────────────────────────────────
    private void runOmitMode() throws Exception {
        System.out.println("── Phase 2: NewItemStateMode.OMIT ─────────────────────────────");

        ChangeFeedRequest anchor = ChangeFeedRequest.builder(address)
                .startPosition(StartPosition.now())
                .newItemStateMode(NewItemStateMode.OMIT)
                .build();
        String token = client.readChanges(anchor).continuationToken();
        if (token == null) {
            throw new AssertionError("OMIT-mode anchor returned null continuation token");
        }

        String pk = "cf-omit-" + UUID.randomUUID();
        MulticloudDbKey key = MulticloudDbKey.of(pk, pk);
        client.create(address, key, doc(pk, "created", 1));
        System.out.printf("  seeded CREATE on %s (OMIT-mode drain expects data()==null)%n", pk);

        long deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS;
        boolean observed = false;
        while (System.currentTimeMillis() < deadline && !observed) {
            ChangeFeedRequest next = ChangeFeedRequest.builder(address)
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .newItemStateMode(NewItemStateMode.OMIT)
                    .build();
            ChangeFeedPage page = client.readChanges(next);
            for (ChangeEvent ev : page.events()) {
                if (pk.equals(ev.key().partitionKey())) {
                    if (ev.data() != null) {
                        throw new AssertionError(
                                "OMIT mode must produce events with null data(), got "
                                        + ev.data());
                    }
                    System.out.printf("    ← %s %s data()=null ✓%n",
                            ev.eventType(), pk);
                    observed = true;
                    break;
                }
            }
            if (page.continuationToken() != null) token = page.continuationToken();
            if (page.events().isEmpty()) Thread.sleep(IDLE_SLEEP_MS);
        }
        if (!observed) {
            throw new AssertionError(
                    "OMIT-mode drain did not surface the seeded event within "
                            + DRAIN_BUDGET_MS + "ms");
        }
        client.delete(address, key);
        System.out.println("  ✓ OMIT mode passed");
        System.out.println();
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 3: small maxPageSize forces multi-page drain
    // ──────────────────────────────────────────────────────────────────
    private void runMaxPageSizePaging() throws Exception {
        System.out.println("── Phase 3: maxPageSize=1 multi-page drain ────────────────────");

        ChangeFeedRequest anchor = ChangeFeedRequest.builder(address)
                .startPosition(StartPosition.now())
                .maxPageSize(1)
                .build();
        String token = client.readChanges(anchor).continuationToken();
        if (token == null) {
            throw new AssertionError(
                    "maxPageSize=1 anchor returned null continuation token");
        }

        // Seed 3 distinct events that the cursor must surface.
        String prefix = "cf-page-" + UUID.randomUUID();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String pk = prefix + "-" + i;
            keys.add(pk);
            client.create(address, MulticloudDbKey.of(pk, pk), doc(pk, "p", i));
        }
        System.out.printf("  seeded 3 CREATE events with prefix %s%n", prefix);

        int pagesObserved = 0;
        Set<String> seenKeys = new HashSet<>();
        long deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS;
        while (System.currentTimeMillis() < deadline && !seenKeys.containsAll(keys)) {
            ChangeFeedRequest next = ChangeFeedRequest.builder(address)
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .maxPageSize(1)
                    .build();
            ChangeFeedPage page = client.readChanges(next);
            if (page.events().size() > 1) {
                throw new AssertionError(
                        "maxPageSize=1 violated — page returned " + page.events().size()
                                + " events");
            }
            if (!page.events().isEmpty()) {
                pagesObserved++;
                ChangeEvent ev = page.events().get(0);
                if (keys.contains(ev.key().partitionKey())) {
                    seenKeys.add(ev.key().partitionKey());
                }
            }
            if (page.continuationToken() != null) token = page.continuationToken();
            if (page.events().isEmpty()) Thread.sleep(IDLE_SLEEP_MS);
        }
        System.out.printf("  pagesWithEvents=%d keysSeen=%s%n", pagesObserved, seenKeys);
        if (!seenKeys.containsAll(keys)) {
            throw new AssertionError(
                    "maxPageSize=1 drain did not surface every seeded key. Expected "
                            + keys + " but saw " + seenKeys);
        }
        // Cleanup
        for (String pk : keys) {
            client.delete(address, MulticloudDbKey.of(pk, pk));
        }
        System.out.println("  ✓ maxPageSize paging passed");
        System.out.println();
    }

    // ──────────────────────────────────────────────────────────────────
    // Drain helper: pulls pages until either {@code requiredTypes} are
    // observed for keys in {@code targetKeys}, or {@code budgetMs}
    // elapses. Returns a snapshot of what was observed.
    // ──────────────────────────────────────────────────────────────────
    private DrainResult drain(
            String startToken,
            Set<String> targetKeys,
            Set<ChangeType> requiredTypes,
            int maxPageSize,
            NewItemStateMode mode,
            long budgetMs) throws Exception {
        DrainResult r = new DrainResult();
        String token = startToken;
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline
                && !r.typesSeen.containsAll(requiredTypes)) {
            ChangeFeedRequest next = ChangeFeedRequest.builder(address)
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .newItemStateMode(mode)
                    .maxPageSize(maxPageSize)
                    .build();
            ChangeFeedPage page = client.readChanges(next);
            r.pages++;
            r.totalEvents += page.events().size();
            for (ChangeEvent ev : page.events()) {
                String pk = ev.key().partitionKey();
                if (targetKeys.contains(pk)) {
                    r.typesSeen.add(ev.eventType());
                    r.keysSeen.add(pk);
                }
            }
            if (page.continuationToken() != null) token = page.continuationToken();
            if (page.events().isEmpty()) Thread.sleep(IDLE_SLEEP_MS);
        }
        r.lastToken = token;
        return r;
    }

    private static final class DrainResult {
        int pages;
        int totalEvents;
        Set<ChangeType> typesSeen = EnumSet.noneOf(ChangeType.class);
        Set<String> keysSeen = new HashSet<>();
        String lastToken;
    }

    private static Map<String, Object> doc(String id, String state, int version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", state);
        m.put("version", version);
        return m;
    }
}
