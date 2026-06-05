// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us8;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.NewItemStateMode;
import com.multiclouddb.api.changefeed.StartPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for User Story 8 — portable Change Feed
 * (FR-CF-01..FR-CF-10). Subclass and supply {@link #createClient()} +
 * {@link #getAddress()}; tests skip cleanly when capabilities are not
 * supported.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ChangeFeedConformanceTest {

    protected abstract MulticloudDbClient createClient();
    protected abstract ResourceAddress getAddress();

    private MulticloudDbClient client;

    @BeforeEach
    void setUp() { client = createClient(); }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
    }

    // ── FR-CF-01: capability declared ──────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("FR-CF-01: provider declares CHANGE_FEED capability")
    void declaresChangeFeedCapability() {
        CapabilitySet caps = client.capabilities();
        // The capability matrix must mention CHANGE_FEED — either as supported or unsupported.
        boolean known = caps.get(Capability.CHANGE_FEED) != null;
        assertTrue(known, "provider must declare CHANGE_FEED capability (supported or unsupported)");
    }

    // ── FR-CF-02..04: round-trip create/update/delete on entire-collection ─

    @Test
    @Order(2)
    @DisplayName("FR-CF-02..04: entire-collection feed surfaces CREATE/UPDATE/DELETE")
    void entireCollectionRoundTrip() throws Exception {
        assumeChangeFeedSupported();
        String prefix = "cf-" + UUID.randomUUID();
        // Start watching from now() so we ignore historical noise
        ChangeFeedRequest req = ChangeFeedRequest.builder(getAddress())
                .startPosition(StartPosition.now())
                .build();
        ChangeFeedPage seed = client.readChanges(req);
        String token = seed.continuationToken();

        // Generate one of each event type
        MulticloudDbKey k1 = MulticloudDbKey.of(prefix + "-pk1", prefix + "-pk1");
        MulticloudDbKey k2 = MulticloudDbKey.of(prefix + "-pk2", prefix + "-pk2");
        client.create(getAddress(), k1, Map.of("v", 1));
        client.upsert(getAddress(), k1, Map.of("v", 2));
        client.create(getAddress(), k2, Map.of("v", 1));
        client.delete(getAddress(), k2);

        // Drain the feed for up to 30s. The test seeds one of each change
        // type, so to exercise FR-CF-02..04 we must observe CREATE, UPDATE,
        // and DELETE — not just "any 2 of 3", which previously let DELETE go
        // unverified on providers (or runs) where it arrives last.
        Set<ChangeType> typesSeen = new HashSet<>();
        Set<String> keysSeen = new HashSet<>();
        Set<ChangeType> required = EnumSet.of(ChangeType.CREATE, ChangeType.UPDATE, ChangeType.DELETE);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && !typesSeen.containsAll(required)) {
            ChangeFeedRequest next = ChangeFeedRequest.builder(getAddress())
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .build();
            ChangeFeedPage page = client.readChanges(next);
            for (ChangeEvent e : page.events()) {
                if (e.key().partitionKey().startsWith(prefix)) {
                    typesSeen.add(e.eventType());
                    keysSeen.add(e.key().partitionKey());
                }
            }
            if (page.continuationToken() != null) token = page.continuationToken();
            if (page.events().isEmpty()) Thread.sleep(500);
        }

        assertTrue(keysSeen.contains(prefix + "-pk1"),
                "feed must surface events for the seeded items; saw " + keysSeen);
        assertTrue(typesSeen.containsAll(required),
                "feed must observe CREATE, UPDATE, and DELETE; saw " + typesSeen);
    }

    // ── FR-CF-05: continuation-token resume ────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("FR-CF-05: continuation token resumes after the last consumed event")
    void continuationTokenResumes() throws Exception {
        assumeChangeFeedSupported();
        String prefix = "cf-resume-" + UUID.randomUUID();
        ChangeFeedRequest start = ChangeFeedRequest.builder(getAddress())
                .startPosition(StartPosition.now()).build();
        String token = client.readChanges(start).continuationToken();

        // Write one item, drain to checkpoint
        MulticloudDbKey k = MulticloudDbKey.of(prefix + "-1", prefix + "-1");
        client.create(getAddress(), k, Map.of("v", 1));
        token = drainAndCheckpoint(token, prefix, Duration.ofSeconds(20));

        // Write a second item AFTER the checkpoint; it should appear when we resume
        MulticloudDbKey k2 = MulticloudDbKey.of(prefix + "-2", prefix + "-2");
        client.create(getAddress(), k2, Map.of("v", 1));
        Set<String> seenAfter = new HashSet<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !seenAfter.contains(prefix + "-2")) {
            ChangeFeedRequest req = ChangeFeedRequest.builder(getAddress())
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .build();
            ChangeFeedPage page = client.readChanges(req);
            for (ChangeEvent e : page.events()) {
                if (e.key().partitionKey().startsWith(prefix)) {
                    seenAfter.add(e.key().partitionKey());
                }
            }
            if (page.continuationToken() != null) token = page.continuationToken();
            if (page.events().isEmpty()) Thread.sleep(500);
        }
        assertTrue(seenAfter.contains(prefix + "-2"),
                "resumed feed must surface item written after checkpoint");
    }

    // ── FR-CF-06: cross-provider continuation token rejected ───────────────

    @Test
    @Order(4)
    @DisplayName("FR-CF-06: continuation token from another provider rejected")
    void crossProviderTokenRejected() {
        assumeChangeFeedSupported();
        // Hand-crafted token whose resource fingerprint matches the current
        // address but whose provider id does not — so the codec's *provider-id*
        // validation (FR-CF-06) is the only check that can reject it.
        String r = getAddress().database() + "/" + getAddress().collection();
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"v\":1,\"p\":\"__not_my_provider__\",\"r\":\"" + r + "\",\"c\":\"x\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ChangeFeedRequest req = ChangeFeedRequest.builder(getAddress())
                .startPosition(StartPosition.fromContinuationToken(forged))
                .build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> client.readChanges(req));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
    }

    // ── FR-CF-10: NewItemStateMode.OMIT yields null data ───────────────────

    @Test
    @Order(6)
    @DisplayName("FR-CF-10: NewItemStateMode.OMIT yields events with null data()")
    void omitDataMode() throws Exception {
        assumeChangeFeedSupported();
        String prefix = "cf-omit-" + UUID.randomUUID();
        ChangeFeedRequest start = ChangeFeedRequest.builder(getAddress())
                .startPosition(StartPosition.now())
                .newItemStateMode(NewItemStateMode.OMIT)
                .build();
        String token = client.readChanges(start).continuationToken();
        client.create(getAddress(), MulticloudDbKey.of(prefix, prefix), Map.of("v", 1));

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ChangeFeedRequest req = ChangeFeedRequest.builder(getAddress())
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .newItemStateMode(NewItemStateMode.OMIT)
                    .build();
            ChangeFeedPage page = client.readChanges(req);
            for (ChangeEvent e : page.events()) {
                if (e.key().partitionKey().equals(prefix)) {
                    assertNull(e.data(), "OMIT mode must produce null data() for event " + e);
                    return;
                }
            }
            if (page.continuationToken() != null) token = page.continuationToken();
            if (page.events().isEmpty()) Thread.sleep(500);
        }
        fail("did not observe seeded event with prefix " + prefix);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void assumeChangeFeedSupported() {
        Assumptions.assumeTrue(
                client.capabilities().isSupported(Capability.CHANGE_FEED),
                "provider does not support CHANGE_FEED");
    }

    private String drainAndCheckpoint(String token, String prefix, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ChangeFeedRequest req = ChangeFeedRequest.builder(getAddress())
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .build();
            ChangeFeedPage page = client.readChanges(req);
            if (page.continuationToken() != null) token = page.continuationToken();
            for (ChangeEvent e : page.events()) {
                if (e.key().partitionKey().startsWith(prefix)) {
                    return token;
                }
            }
            if (page.events().isEmpty()) Thread.sleep(500);
        }
        return token;
    }
}
