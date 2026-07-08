// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link SpannerProviderClient#formatRetentionPeriod(Duration)}.
 * <p>
 * The format helper picks the coarsest GoogleSQL retention_period suffix that
 * exactly represents the input {@link Duration}. Stable formatting keeps DDL
 * diffs minimal — the same {@code Duration} must always emit the same string
 * so re-runs of {@code ensureContainer} do not appear to "change" the
 * change-stream definition.
 *
 * <p>Stability matters because Spanner's {@code CREATE CHANGE STREAM ...
 * OPTIONS(retention_period = '<value>')} DDL is idempotent only at the
 * string-equality level; a different-shaped value (even if numerically
 * equivalent) is read by tooling as a schema change.
 */
class SpannerRetentionPeriodFormatTest {

    @Test
    @DisplayName("Multi-day durations format as days (suffix 'd')")
    void daysFormat() {
        assertEquals("7d", SpannerProviderClient.formatRetentionPeriod(Duration.ofDays(7)));
        assertEquals("30d", SpannerProviderClient.formatRetentionPeriod(Duration.ofDays(30)));
        assertEquals("365d", SpannerProviderClient.formatRetentionPeriod(Duration.ofDays(365)));
        assertEquals("1d", SpannerProviderClient.formatRetentionPeriod(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("Hour-multiples that aren't whole days format as hours (suffix 'h')")
    void hoursFormat() {
        assertEquals("36h", SpannerProviderClient.formatRetentionPeriod(Duration.ofHours(36)),
                "36h is not divisible by 24, so the coarsest unit is hours");
        assertEquals("25h", SpannerProviderClient.formatRetentionPeriod(Duration.ofHours(25)));
        // 48h would format as "2d" because the helper picks the coarsest unit
        assertEquals("2d", SpannerProviderClient.formatRetentionPeriod(Duration.ofHours(48)),
                "48h is divisible by 24h, so the helper should collapse to days");
    }

    @Test
    @DisplayName("Minute-multiples that aren't whole hours format as minutes (suffix 'm')")
    void minutesFormat() {
        assertEquals("90m", SpannerProviderClient.formatRetentionPeriod(Duration.ofMinutes(90)));
        assertEquals("1m", SpannerProviderClient.formatRetentionPeriod(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("Sub-minute durations format as seconds (suffix 's')")
    void secondsFormat() {
        assertEquals("45s", SpannerProviderClient.formatRetentionPeriod(Duration.ofSeconds(45)));
        assertEquals("1s", SpannerProviderClient.formatRetentionPeriod(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("Sub-second residue must NOT silently collapse to the baseline (24h + 1ms → not '1d')")
    void subSecondResidueDoesNotCollapseToBaseline() {
        // ChangeFeedConfig.Builder.extendedRetention rejects values <= 24h,
        // green-listing 24h + 1ms as the smallest valid opt-in. If the
        // formatter silently truncates the residue, Spanner gets exactly the
        // 24h baseline the opt-in is meant to exceed — a silent downgrade.
        String just = SpannerProviderClient.formatRetentionPeriod(
                java.time.Duration.ofHours(24).plusMillis(1));
        assertNotEquals("1d", just,
                "24h + 1ms must NOT format as '1d' (that is the portable baseline the opt-in is meant to exceed)");
        assertNotEquals(SpannerProviderClient.formatRetentionPeriod(java.time.Duration.ofHours(24)),
                just,
                "the formatter must distinguish 24h from 24h + 1ms — otherwise the opt-in is silently no-op'd");
    }
    @Test
    @DisplayName("Two equal Durations always produce identical strings — DDL stability")
    void formatIsDeterministic() {
        Duration a = Duration.ofDays(7);
        Duration b = Duration.ofHours(7 * 24);
        assertEquals(SpannerProviderClient.formatRetentionPeriod(a),
                SpannerProviderClient.formatRetentionPeriod(b),
                "equal Durations expressed differently must yield identical retention strings");
    }

    @Test
    @DisplayName("parseRetentionPeriod is the inverse of formatRetentionPeriod for the common suffixes")
    void parseRetentionPeriodInvertsFormat() {
        Duration[] cases = {
                Duration.ofSeconds(45),
                Duration.ofMinutes(90),
                Duration.ofHours(36),
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofDays(365),
        };
        for (Duration d : cases) {
            String formatted = SpannerProviderClient.formatRetentionPeriod(d);
            Duration roundTrip = SpannerProviderClient.parseRetentionPeriod(formatted);
            assertEquals(d, roundTrip,
                    "parseRetentionPeriod must be the inverse of formatRetentionPeriod for "
                            + d + " (formatted as " + formatted + ")");
        }
    }

    @Test
    @DisplayName("parseRetentionPeriod accepts SQL-string-literal quotes around the value")
    void parseRetentionPeriodAcceptsQuotes() {
        // INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS.OPTION_VALUE returns the option
        // value as the SQL string literal as authored — including surrounding
        // single quotes. The parser must tolerate that wrapping.
        assertEquals(Duration.ofDays(7), SpannerProviderClient.parseRetentionPeriod("'7d'"));
        assertEquals(Duration.ofHours(36), SpannerProviderClient.parseRetentionPeriod("'36h'"));
    }

    @Test
    @DisplayName("parseRetentionPeriod returns null on garbage so the caller falls through to log-and-continue")
    void parseRetentionPeriodReturnsNullOnGarbage() {
        // The duplicate-name catch in ensureContainer uses the null return value
        // to mean "could not verify, fall through to the prior log-and-continue
        // path" rather than mis-typing a different retention.
        assertNull(SpannerProviderClient.parseRetentionPeriod(null));
        assertNull(SpannerProviderClient.parseRetentionPeriod(""));
        assertNull(SpannerProviderClient.parseRetentionPeriod("''"));
        assertNull(SpannerProviderClient.parseRetentionPeriod("notADuration"));
        assertNull(SpannerProviderClient.parseRetentionPeriod("7x"),
                "unknown suffix 'x' is rejected, not silently mis-coerced to a Duration");
        assertNull(SpannerProviderClient.parseRetentionPeriod("abcd"));
    }
}
