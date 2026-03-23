package com.hyperscaledb.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration model for creating a Hyperscale DB client.
 * Selects the provider and supplies connection/auth details and portable
 * options.
 */
public final class HyperscaleDbClientConfig {

    private final ProviderId provider;
    private final Map<String, String> connection;
    private final Map<String, String> auth;
    private final OperationOptions defaultOptions;
    private final Map<String, String> featureFlags;

    /**
     * When true, providers log the full native diagnostics object on every
     * operation (e.g., {@code CosmosDiagnostics} for Cosmos DB).
     * Disabled by default to avoid the per-call string-serialisation overhead.
     * Enable via {@link Builder#nativeDiagnosticsEnabled(boolean)}.
     */
    private final boolean nativeDiagnosticsEnabled;

    private HyperscaleDbClientConfig(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider is required");
        this.connection = builder.connection != null ? Map.copyOf(builder.connection) : Collections.emptyMap();
        this.auth = builder.auth != null ? Map.copyOf(builder.auth) : Collections.emptyMap();
        this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions : OperationOptions.defaults();
        this.featureFlags = builder.featureFlags != null ? Map.copyOf(builder.featureFlags) : Collections.emptyMap();
        this.nativeDiagnosticsEnabled = builder.nativeDiagnosticsEnabled;
    }

    public ProviderId provider() {
        return provider;
    }

    public Map<String, String> connection() {
        return connection;
    }

    public Map<String, String> auth() {
        return auth;
    }

    public OperationOptions defaultOptions() {
        return defaultOptions;
    }

    /**
     * Explicit feature flags for provider-specific opt-ins.
     * Must be set deliberately; empty by default to preserve portability.
     */
    public Map<String, String> featureFlags() {
        return featureFlags;
    }

    /**
     * Whether the full native diagnostics object is logged on every operation.
     * Disabled by default; enable via {@link Builder#nativeDiagnosticsEnabled(boolean)}.
     */
    public boolean nativeDiagnosticsEnabled() {
        return nativeDiagnosticsEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ProviderId provider;
        private Map<String, String> connection;
        private Map<String, String> auth;
        private OperationOptions defaultOptions;
        private Map<String, String> featureFlags;
        private boolean nativeDiagnosticsEnabled = false;

        public Builder provider(ProviderId provider) {
            this.provider = provider;
            return this;
        }

        public Builder connection(String key, String value) {
            if (this.connection == null)
                this.connection = new HashMap<>();
            this.connection.put(key, value);
            return this;
        }

        public Builder connection(Map<String, String> connection) {
            this.connection = connection;
            return this;
        }

        public Builder auth(String key, String value) {
            if (this.auth == null)
                this.auth = new HashMap<>();
            this.auth.put(key, value);
            return this;
        }

        public Builder auth(Map<String, String> auth) {
            this.auth = auth;
            return this;
        }

        public Builder defaultOptions(OperationOptions options) {
            this.defaultOptions = options;
            return this;
        }

        public Builder featureFlag(String key, String value) {
            if (this.featureFlags == null)
                this.featureFlags = new HashMap<>();
            this.featureFlags.put(key, value);
            return this;
        }

        public Builder featureFlags(Map<String, String> featureFlags) {
            this.featureFlags = featureFlags;
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

        public HyperscaleDbClientConfig build() {
            return new HyperscaleDbClientConfig(this);
        }
    }
}
