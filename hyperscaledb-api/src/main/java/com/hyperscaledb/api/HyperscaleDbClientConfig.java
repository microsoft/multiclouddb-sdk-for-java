// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for creating a {@link HyperscaleDbClient}.
 * <p>
 * Selects the provider and supplies connection and auth details plus portable
 * operation defaults. Once built, all map accessors return
 * <em>unmodifiable</em> views — any attempt to mutate them will throw
 * {@link UnsupportedOperationException}.
 * <p>
 * Use {@link #builder()} to construct instances.
 */
public final class HyperscaleDbClientConfig {

    private final ProviderId provider;
    private final Map<String, String> connection;
    private final Map<String, String> auth;
    private final OperationOptions defaultOptions;

    private HyperscaleDbClientConfig(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider is required");
        this.connection = builder.connection != null ? Map.copyOf(builder.connection) : Collections.emptyMap();
        this.auth = builder.auth != null ? Map.copyOf(builder.auth) : Collections.emptyMap();
        this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions : OperationOptions.defaults();
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

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link HyperscaleDbClientConfig}.
     * <p>
     * All bulk {@code Map}-accepting setters make a defensive copy of the supplied
     * map so subsequent mutations by the caller do not affect the builder state.
     */
    public static final class Builder {
        private ProviderId provider;
        private Map<String, String> connection;
        private Map<String, String> auth;
        private OperationOptions defaultOptions;

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

        /** Build and return an immutable {@link HyperscaleDbClientConfig}. */
        public HyperscaleDbClientConfig build() {
            return new HyperscaleDbClientConfig(this);
        }
    }
}
