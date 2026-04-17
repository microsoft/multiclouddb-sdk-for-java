// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for creating a {@link MulticloudDbClient}.
 * <p>
 * Selects the provider and supplies connection and auth details plus portable
 * operation defaults. Once built, all map accessors return
 * <em>unmodifiable</em> views — any attempt to mutate them will throw
 * {@link UnsupportedOperationException}.
 * <p>
 * Use {@link #builder()} to construct instances.
 */
public final class MulticloudDbClientConfig {

    private final ProviderId provider;
    private final Map<String, String> connection;
    private final Map<String, String> auth;
    private final OperationOptions defaultOptions;

    /**
     * When true, providers log the full native diagnostics object on every
     * operation (e.g., {@code CosmosDiagnostics} for Cosmos DB).
     * Disabled by default to avoid the per-call string-serialisation overhead.
     * Enable via {@link Builder#nativeDiagnosticsEnabled(boolean)}.
     */
    private final boolean nativeDiagnosticsEnabled;

    /**
     * Optional suffix appended to the SDK's user agent string.
     * When set, outgoing requests include the user agent
     * {@code azsdk-java-multiclouddb/<version> <userAgentSuffix>}.
     * {@code null} when no custom suffix is configured.
     */
    private final String userAgentSuffix;

    private MulticloudDbClientConfig(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider is required");
        this.connection = builder.connection != null ? Map.copyOf(builder.connection) : Collections.emptyMap();
        this.auth = builder.auth != null ? Map.copyOf(builder.auth) : Collections.emptyMap();
        this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions : OperationOptions.defaults();
        this.nativeDiagnosticsEnabled = builder.nativeDiagnosticsEnabled;
        this.userAgentSuffix = builder.userAgentSuffix;
    }

    /** The target provider. */
    public ProviderId provider() {
        return provider;
    }

    /**
     * Connection properties (endpoint URL, region, database name, etc.).
     * <p>
     * The returned map is <em>unmodifiable</em>; mutations throw
     * {@link UnsupportedOperationException}.
     */
    public Map<String, String> connection() {
        return connection;
    }

    /**
     * Authentication properties (keys, credentials, tenant ID, etc.).
     * <p>
     * The returned map is <em>unmodifiable</em>; mutations throw
     * {@link UnsupportedOperationException}.
     */
    public Map<String, String> auth() {
        return auth;
    }

    /** Default operation options applied when per-call options are omitted. */
    public OperationOptions defaultOptions() {
        return defaultOptions;
    }

    /**
     * Whether the full native diagnostics object is logged on every operation.
     * Disabled by default; enable via {@link Builder#nativeDiagnosticsEnabled(boolean)}.
     */
    public boolean nativeDiagnosticsEnabled() {
        return nativeDiagnosticsEnabled;
    }

    /**
     * Optional customer-provided suffix appended to the SDK user agent string.
     * Returns {@code null} when no custom suffix is configured.
     *
     * @see Builder#userAgentSuffix(String)
     */
    public String userAgentSuffix() {
        return userAgentSuffix;
    }


    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link MulticloudDbClientConfig}.
     * <p>
     * All bulk {@code Map}-accepting setters make a defensive copy of the supplied
     * map so subsequent mutations by the caller do not affect the builder state.
     */
    public static final class Builder {
        private ProviderId provider;
        private Map<String, String> connection;
        private Map<String, String> auth;
        private OperationOptions defaultOptions;
        private boolean nativeDiagnosticsEnabled = false;
        private String userAgentSuffix;

        /** Set the target provider. */
        public Builder provider(ProviderId provider) {
            this.provider = provider;
            return this;
        }

        /** Add a single connection property. */
        public Builder connection(String key, String value) {
            if (this.connection == null)
                this.connection = new HashMap<>();
            this.connection.put(key, value);
            return this;
        }

        /**
         * Replace all connection properties with the given map.
         * A defensive copy is made immediately.
         */
        public Builder connection(Map<String, String> connection) {
            this.connection = connection != null ? new HashMap<>(connection) : null;
            return this;
        }

        /** Add a single auth property. */
        public Builder auth(String key, String value) {
            if (this.auth == null)
                this.auth = new HashMap<>();
            this.auth.put(key, value);
            return this;
        }

        /**
         * Replace all auth properties with the given map.
         * A defensive copy is made immediately.
         */
        public Builder auth(Map<String, String> auth) {
            this.auth = auth != null ? new HashMap<>(auth) : null;
            return this;
        }

        /** Set the default operation options. */
        public Builder defaultOptions(OperationOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * When set to true, providers log the full native diagnostics string on every
         * operation (e.g., Cosmos {@code CosmosDiagnostics.toString()}).
         * This is an opt-in due to the per-call string-serialisation cost.
         * Portability is preserved — the portable API is unaffected; diagnostics are
         * captured and logged internally without leaking provider types into application
         * code.
         */
        public Builder nativeDiagnosticsEnabled(boolean enabled) {
            this.nativeDiagnosticsEnabled = enabled;
            return this;
        }

        /**
         * Set a custom suffix that is appended to the SDK's user agent string.
         * <p>
         * The resulting user agent sent with every request is:
         * {@code azsdk-java-multiclouddb/<version> <suffix>}.
         * Pass {@code null} to clear a previously set suffix.
         *
         * @param suffix the application-specific identifier to append
         * @return this builder
         */
        public Builder userAgentSuffix(String suffix) {
            this.userAgentSuffix = suffix;
            return this;
        }

        /** Build and return an immutable {@link MulticloudDbClientConfig}. */

        public MulticloudDbClientConfig build() {
            return new MulticloudDbClientConfig(this);
        }
    }
}
