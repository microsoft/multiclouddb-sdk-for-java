// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the one-shot WARN that {@link DefaultMulticloudDbClient#readChanges(
 * ResourceAddress, ChangeFeedCursor, OperationOptions)} emits when a caller
 * supplies {@link OperationOptions#timeout()}.
 * <p>
 * The v1 change-feed path does not enforce {@code options.timeout()} on any
 * built-in provider (Cosmos: per-request; Dynamo: ~5s GetRecords; Spanner: 5s
 * TVF window — see follow-up task T174 in
 * {@code specs/001-clouddb-sdk/tasks.md}). The facade emits a single WARN the
 * first time a non-default timeout is observed and stays silent thereafter so
 * the log isn't flooded by per-call repetition. These tests pin that contract:
 * <ul>
 *   <li><b>No timeout</b>: {@code options.timeout() == null} produces no WARN.</li>
 *   <li><b>First non-default timeout</b>: produces exactly one WARN at level
 *       {@link Level#WARN} whose message names the timeout value and the
 *       "logged once per JVM" guidance.</li>
 *   <li><b>Subsequent non-default timeouts</b>: produce zero additional WARNs
 *       (the {@code AtomicBoolean} guard is sticky).</li>
 * </ul>
 * <p>
 * The {@code READ_CHANGES_TIMEOUT_WARNED} guard is a JVM-wide singleton; each
 * test resets it via reflection in {@link #attachAppender()} so the tests are
 * order-independent and reproducible.
 */
class DefaultMulticloudDbClientReadChangesTimeoutTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "col");
    private static final MulticloudDbClientConfig CONFIG = MulticloudDbClientConfig.builder()
            .provider(ProviderId.COSMOS)
            .build();
    private static final CapabilitySet CAPS = new CapabilitySet(List.of(Capability.CHANGE_FEED_CAP));

    private ListAppender<ILoggingEvent> appender;
    private Logger clientLogger;

    @BeforeEach
    void attachAppender() throws Exception {
        clientLogger = (Logger) LoggerFactory.getLogger(DefaultMulticloudDbClient.class);
        clientLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        clientLogger.addAppender(appender);
        // Reset the JVM-wide one-shot guard so this test sees a fresh state
        // regardless of suite ordering or whether a prior test triggered it.
        resetWarnGuard();
    }

    @AfterEach
    void detachAppender() throws Exception {
        clientLogger.detachAppender(appender);
        appender.stop();
        // Leave the guard in a known state for the next test class.
        resetWarnGuard();
    }

    private static void resetWarnGuard() throws Exception {
        Field f = DefaultMulticloudDbClient.class.getDeclaredField("READ_CHANGES_TIMEOUT_WARNED");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(null)).set(false);
    }

    private long warnCount(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .count();
    }

    private static MulticloudDbProviderClient stubProvider() {
        return new MulticloudDbProviderClient() {
            @Override public void create(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public DocumentResult read(ResourceAddress a, MulticloudDbKey k, OperationOptions o) { return null; }
            @Override public void update(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public void upsert(ResourceAddress a, MulticloudDbKey k, Map<String, Object> d, OperationOptions o) {}
            @Override public void delete(ResourceAddress a, MulticloudDbKey k, OperationOptions o) {}
            @Override public QueryPage query(ResourceAddress a, QueryRequest q, OperationOptions o) { return null; }
            @Override public CapabilitySet capabilities() { return CAPS; }
            @Override public ProviderId providerId() { return ProviderId.COSMOS; }
            @Override public void close() {}
            @Override public List<ChangeFeedCursor> listCursors(ResourceAddress address) {
                return List.of(ChangeFeedCursor.now());
            }
            @Override public ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor, OperationOptions o) {
                return new ChangeFeedPage(List.<ChangeEvent>of(), cursor, false, false);
            }
        };
    }

    private DefaultMulticloudDbClient newClient() {
        return new DefaultMulticloudDbClient(stubProvider(), CONFIG);
    }

    @Test
    @DisplayName("Default OperationOptions (timeout == null) produces no WARN")
    void defaultOptions_noWarn() {
        DefaultMulticloudDbClient client = newClient();
        client.readChanges(ADDR, ChangeFeedCursor.now(), OperationOptions.defaults());
        // Default OperationOptions carry a null timeout — the guard branch is
        // never entered, so no WARN should fire even on the very first call.
        assertEquals(0, warnCount("readChanges: OperationOptions.timeout()"),
                "default options must not trigger the unenforced-timeout WARN; got "
                        + appender.list);
    }

    @Test
    @DisplayName("First call with options.timeout() set → exactly one WARN naming the value")
    void firstCallWithTimeout_emitsExactlyOneWarn() {
        DefaultMulticloudDbClient client = newClient();
        Duration timeout = Duration.ofSeconds(7);
        client.readChanges(ADDR, ChangeFeedCursor.now(),
                OperationOptions.builder().timeout(timeout).build());

        assertEquals(1, warnCount("readChanges: OperationOptions.timeout()"),
                "first call with a non-default timeout must emit exactly one WARN");
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("PT7S")),
                "WARN message must name the supplied timeout value (PT7S); got "
                        + appender.list);
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("logged once per JVM")),
                "WARN must include the 'logged once per JVM' guidance so operators "
                        + "are not surprised by the absence of subsequent warnings");
    }

    @Test
    @DisplayName("Subsequent calls with options.timeout() set → no additional WARN (one-shot guard)")
    void subsequentCallsWithTimeout_noAdditionalWarn() {
        DefaultMulticloudDbClient client = newClient();
        OperationOptions opts = OperationOptions.builder().timeout(Duration.ofSeconds(3)).build();
        client.readChanges(ADDR, ChangeFeedCursor.now(), opts);
        client.readChanges(ADDR, ChangeFeedCursor.now(), opts);
        client.readChanges(ADDR, ChangeFeedCursor.now(), opts);

        assertEquals(1, warnCount("readChanges: OperationOptions.timeout()"),
                "the one-shot guard must suppress all WARNs after the first; got "
                        + appender.list.stream()
                                .filter(e -> e.getLevel() == Level.WARN)
                                .map(ILoggingEvent::getFormattedMessage)
                                .toList());
    }

    @Test
    @DisplayName("Default → timeout → default → timeout: still exactly one WARN total")
    void interleavedCalls_stillOneWarn() {
        DefaultMulticloudDbClient client = newClient();
        client.readChanges(ADDR, ChangeFeedCursor.now(), OperationOptions.defaults());
        client.readChanges(ADDR, ChangeFeedCursor.now(),
                OperationOptions.builder().timeout(Duration.ofSeconds(1)).build());
        client.readChanges(ADDR, ChangeFeedCursor.now(), OperationOptions.defaults());
        client.readChanges(ADDR, ChangeFeedCursor.now(),
                OperationOptions.builder().timeout(Duration.ofSeconds(2)).build());

        assertEquals(1, warnCount("readChanges: OperationOptions.timeout()"),
                "the guard is sticky: interleaved default + timeout calls must still "
                        + "produce exactly one WARN total");
    }
}
