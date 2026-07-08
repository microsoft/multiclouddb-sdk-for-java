// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChangeFeedConfig} and its builder. The builder is the
 * single point where extended-retention duration validation happens, so the
 * test coverage focuses on:
 * <ul>
 *   <li>{@code defaults()} — no extended retention requested.</li>
 *   <li>Builder validation — zero, negative, and &le;24h durations all throw
 *       {@link IllegalArgumentException} eagerly from the setter, before any
 *       I/O can be issued by the SDK.</li>
 *   <li>{@code null} clears any previously-set request and {@code build()}
 *       returns {@link ChangeFeedConfig#defaults()}.</li>
 *   <li>Round-trip — a valid duration is preserved through
 *       {@link ChangeFeedConfig#extendedRetention()} and reflected in
 *       {@link ChangeFeedConfig#hasExtendedRetention()}.</li>
 *   <li>{@code equals}, {@code hashCode}, {@code toString} — value-class contracts.</li>
 *   <li>{@link ChangeFeedConfig#BASELINE_RETENTION} — pinned at 24h so the
 *       portable floor cannot silently drift.</li>
 * </ul>
 */
class ChangeFeedConfigTest {

    @Test
    @DisplayName("defaults() reports no extended retention")
    void defaultsHasNoExtendedRetention() {
        ChangeFeedConfig cfg = ChangeFeedConfig.defaults();
        assertFalse(cfg.hasExtendedRetention(),
                "defaults() must opt out of extended retention");
        assertEquals(Optional.empty(), cfg.extendedRetention(),
                "defaults() extendedRetention() must be empty");
    }

    @Test
    @DisplayName("builder() with no extendedRetention call is equivalent to defaults()")
    void builderWithoutOptInEqualsDefaults() {
        ChangeFeedConfig built = ChangeFeedConfig.builder().build();
        assertEquals(ChangeFeedConfig.defaults(), built);
        assertSame(ChangeFeedConfig.defaults(), built,
                "build() with no opt-in must return the cached DEFAULTS singleton");
        assertFalse(built.hasExtendedRetention());
    }

    @Test
    @DisplayName("BASELINE_RETENTION is exactly 24h — the portable floor must not silently drift")
    void baselineRetentionIs24h() {
        assertEquals(Duration.ofHours(24), ChangeFeedConfig.BASELINE_RETENTION,
                "BASELINE_RETENTION pins the portable change-feed-history floor at 24h "
                        + "and is asserted by spec FR-068. Do not change without updating the spec.");
    }

    @Test
    @DisplayName("extendedRetention(48h) round-trips through the getter")
    void extendedRetentionRoundTrips() {
        Duration twoDays = Duration.ofHours(48);
        ChangeFeedConfig cfg = ChangeFeedConfig.builder()
                .extendedRetention(twoDays)
                .build();
        assertTrue(cfg.hasExtendedRetention());
        assertEquals(Optional.of(twoDays), cfg.extendedRetention());
    }

    @Test
    @DisplayName("extendedRetention(null) clears any previously-set value")
    void nullClearsPreviousValue() {
        ChangeFeedConfig cfg = ChangeFeedConfig.builder()
                .extendedRetention(Duration.ofDays(3))
                .extendedRetention(null)
                .build();
        assertFalse(cfg.hasExtendedRetention(),
                "passing null must clear the previously-set retention");
        assertEquals(ChangeFeedConfig.defaults(), cfg);
    }

    @Test
    @DisplayName("extendedRetention(Duration.ZERO) is rejected eagerly")
    void zeroRetentionRejected() {
        ChangeFeedConfig.Builder b = ChangeFeedConfig.builder();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> b.extendedRetention(Duration.ZERO));
        assertTrue(ex.getMessage().toLowerCase().contains("positive")
                        || ex.getMessage().toLowerCase().contains("retention"),
                "error message must reference the violation; got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Negative extendedRetention is rejected eagerly")
    void negativeRetentionRejected() {
        ChangeFeedConfig.Builder b = ChangeFeedConfig.builder();
        assertThrows(IllegalArgumentException.class,
                () -> b.extendedRetention(Duration.ofHours(-1)));
    }

    @Test
    @DisplayName("Exactly 24h is rejected — opting in must extend beyond the portable baseline")
    void exactlyBaselineRejected() {
        ChangeFeedConfig.Builder b = ChangeFeedConfig.builder();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> b.extendedRetention(Duration.ofHours(24)));
        assertTrue(ex.getMessage().toLowerCase().contains("24")
                        || ex.getMessage().toLowerCase().contains("baseline"),
                "error message should reference the 24h baseline; got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Just below baseline (23h59m) is rejected")
    void belowBaselineRejected() {
        ChangeFeedConfig.Builder b = ChangeFeedConfig.builder();
        assertThrows(IllegalArgumentException.class,
                () -> b.extendedRetention(Duration.ofHours(23).plusMinutes(59)));
    }

    @Test
    @DisplayName("24h + 1ms is the smallest accepted duration above the baseline")
    void justAboveBaselineAccepted() {
        Duration justAbove = Duration.ofHours(24).plusMillis(1);
        ChangeFeedConfig cfg = ChangeFeedConfig.builder().extendedRetention(justAbove).build();
        assertEquals(Optional.of(justAbove), cfg.extendedRetention());
    }

    @Test
    @DisplayName("Common retention windows (7d, 30d) are accepted")
    void commonRetentionsAccepted() {
        Duration sevenDays = Duration.ofDays(7);
        Duration thirtyDays = Duration.ofDays(30);
        ChangeFeedConfig week = ChangeFeedConfig.builder().extendedRetention(sevenDays).build();
        ChangeFeedConfig month = ChangeFeedConfig.builder().extendedRetention(thirtyDays).build();
        assertEquals(Optional.of(sevenDays), week.extendedRetention());
        assertEquals(Optional.of(thirtyDays), month.extendedRetention());
    }

    @Test
    @DisplayName("equals/hashCode honor the value-class contract")
    void equalsAndHashCode() {
        ChangeFeedConfig a = ChangeFeedConfig.builder().extendedRetention(Duration.ofDays(3)).build();
        ChangeFeedConfig b = ChangeFeedConfig.builder().extendedRetention(Duration.ofDays(3)).build();
        ChangeFeedConfig c = ChangeFeedConfig.builder().extendedRetention(Duration.ofDays(7)).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, ChangeFeedConfig.defaults());
        assertNotEquals(null, a);
        assertNotEquals("not a config", a);
        // Self equality
        assertEquals(a, a);
    }

    @Test
    @DisplayName("toString surfaces the retention value when set")
    void toStringContainsRetention() {
        String s = ChangeFeedConfig.builder().extendedRetention(Duration.ofDays(7)).build().toString();
        assertTrue(s.toLowerCase().contains("p7d") || s.contains("168") || s.toLowerCase().contains("7d"),
                "toString should surface the retention; got: " + s);
    }

    @Test
    @DisplayName("toString of defaults clearly indicates no extended retention is set")
    void toStringOfDefaultsIsReadable() {
        String s = ChangeFeedConfig.defaults().toString();
        assertNotNull(s);
        assertTrue(s.toLowerCase().contains("changefeedconfig"),
                "toString should at least name the class; got: " + s);
        assertTrue(s.toLowerCase().contains("default") || s.toLowerCase().contains("provider default"),
                "toString of defaults should indicate the provider default; got: " + s);
    }
}