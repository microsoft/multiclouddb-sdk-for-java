// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable opt-in configuration for the portable change-feed read path.
 * <p>
 * v1 ships a 24-hour portable retention baseline that every provider honours
 * out of the box: a token returned by {@link ChangeFeedCursor#toToken()} can
 * be replayed for 24 hours regardless of which provider minted it. This class
 * carries the <em>opt-in</em> request to retain history beyond that baseline.
 *
 * <h3>How the opt-in is honoured</h3>
 * Setting {@link Builder#extendedRetention(Duration)} declares a desired
 * server-side retention window strictly greater than 24 hours. On
 * {@link com.multiclouddb.api.MulticloudDbClient} build the SDK fails fast
 * with {@link com.multiclouddb.api.MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY
 * UNSUPPORTED_CAPABILITY} (reason {@code extended_retention_unavailable}) on
 * any provider that does not declare {@link
 * com.multiclouddb.api.Capability#EXTENDED_CHANGE_FEED_HISTORY EXTENDED_CHANGE_FEED_HISTORY}.
 * On providers that do declare it, the corresponding
 * {@code ensureContainer(address)} call provisions the substrate that backs
 * the requested window (Cosmos AVAD container on a Continuous Backup account;
 * Spanner {@code CREATE CHANGE STREAM ... OPTIONS(retention_period=...)}).
 *
 * <h3>Cost model — important</h3>
 * Extended retention has <em>different bill shapes per provider</em>; they
 * are not interchangeable. Read the "Extending change-feed history beyond
 * 24 hours" section of {@code docs/guide.md} (or
 * <a href="https://github.com/microsoft/multiclouddb-sdk-for-java/blob/main/docs/guide.md">on GitHub</a>)
 * for the per-provider price drivers.
 *
 * <h3>Defaults &amp; backwards compatibility</h3>
 * The default instance ({@link #defaults()}) carries no extended-retention
 * request, so callers that never touch this class behave bit-for-bit
 * identical to v1 (24-hour portable baseline on every provider).
 */
public final class ChangeFeedConfig {

    private static final ChangeFeedConfig DEFAULTS = new ChangeFeedConfig(null);

    /** Lower bound for {@link #extendedRetention} — the v1 portable baseline. */
    public static final Duration BASELINE_RETENTION = Duration.ofHours(24);

    private final Duration extendedRetention;

    private ChangeFeedConfig(Duration extendedRetention) {
        this.extendedRetention = extendedRetention;
    }

    /**
     * Return the empty/default configuration — no extended-retention requested.
     * Wired in to {@link com.multiclouddb.api.MulticloudDbClientConfig} when
     * the caller does not explicitly set one.
     */
    public static ChangeFeedConfig defaults() {
        return DEFAULTS;
    }

    /**
     * Requested change-feed history window beyond the v1 24-hour baseline.
     * <p>
     * An empty {@code Optional} means "use the provider's portable baseline"
     * — token replay remains bounded by 24 hours on every provider.
     * A non-empty value means the caller is explicitly asking for longer
     * server-side retention; the SDK will refuse to build a client against
     * any provider that does not declare
     * {@link com.multiclouddb.api.Capability#EXTENDED_CHANGE_FEED_HISTORY}.
     */
    public Optional<Duration> extendedRetention() {
        return Optional.ofNullable(extendedRetention);
    }

    /**
     * Whether this configuration requests extended retention — i.e., whether
     * {@link #extendedRetention()} carries a value.
     */
    public boolean hasExtendedRetention() {
        return extendedRetention != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChangeFeedConfig c)) return false;
        return Objects.equals(extendedRetention, c.extendedRetention);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(extendedRetention);
    }

    @Override
    public String toString() {
        return "ChangeFeedConfig{extendedRetention="
                + (extendedRetention != null ? extendedRetention : "<provider default>") + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link ChangeFeedConfig}.
     * <p>
     * Validation runs at {@link #build()} time so an illegal request surfaces
     * as a plain {@link IllegalArgumentException} at config-time, not later
     * at the first provider call.
     */
    public static final class Builder {
        private Duration extendedRetention;

        /**
         * Request server-side change-feed history retention longer than the
         * portable 24-hour baseline.
         * <p>
         * The value MUST be strictly greater than 24 hours; the floor is the
         * portable baseline already shipped by every provider, so a request
         * for less than 24 hours is meaningless (and is rejected as such).
         * A {@code null} value clears any previously-set request.
         *
         * @param retention requested server-side retention window
         * @return this builder
         * @throws IllegalArgumentException if {@code retention} is negative,
         *         zero, or less than or equal to 24 hours
         */
        public Builder extendedRetention(Duration retention) {
            if (retention != null) {
                if (retention.isNegative() || retention.isZero()) {
                    throw new IllegalArgumentException(
                            "extendedRetention must be a positive Duration; was " + retention);
                }
                if (retention.compareTo(BASELINE_RETENTION) <= 0) {
                    throw new IllegalArgumentException(
                            "extendedRetention must be strictly greater than the 24-hour portable "
                                    + "baseline (the SDK already guarantees 24h on every provider); was "
                                    + retention);
                }
            }
            this.extendedRetention = retention;
            return this;
        }

        public ChangeFeedConfig build() {
            if (extendedRetention == null) {
                return DEFAULTS;
            }
            return new ChangeFeedConfig(extendedRetention);
        }
    }
}