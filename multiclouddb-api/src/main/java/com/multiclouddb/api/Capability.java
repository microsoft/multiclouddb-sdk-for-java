// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A named capability that may be supported (or not) by a provider.
 * <p>
 * Under strict LCD the public API only exposes features supported by every
 * provider — so the well-known capability set is small. Capabilities remain
 * useful for documentation, runtime introspection, and forward-compatibility
 * with future portable features.
 * <p>
 * <h3>Usage</h3>
 * Well-known capabilities are exposed as pre-built singleton pairs:
 * {@code Capability.TRANSACTIONS_CAP} (supported) and
 * {@code Capability.TRANSACTIONS_UNSUPPORTED} (unsupported).
 * Providers use these directly instead of constructing new instances.
 *
 * <h3>Extensibility</h3>
 * Third-party providers can register additional capabilities via
 * {@link #of(String, boolean)} or {@link #of(String, boolean, String)}.
 */
public final class Capability {

    // ── Internal registry — must be declared BEFORE the public constants ────

    /**
     * Intern registry keyed by {@code "name:supported"} to ensure the same
     * logical capability always returns the same instance when notes are absent.
     */
    private static final Map<String, Capability> REGISTRY = new ConcurrentHashMap<>();

    // ── Well-known LCD capability name constants ─────────────────────────────
    // Only capabilities supported by ALL providers are exposed.

    public static final String CONTINUATION_TOKEN_PAGING    = "continuation_token_paging";
    public static final String TRANSACTIONS                 = "transactions";
    public static final String BATCH_OPERATIONS             = "batch_operations";
    public static final String STRONG_CONSISTENCY           = "strong_consistency";
    public static final String CHANGE_FEED                  = "change_feed";
    public static final String PORTABLE_QUERY_EXPRESSION    = "portable_query_expression";
    public static final String ORDER_BY                     = "order_by";

    // ── Pre-built singleton instances ─────────────────────────────────────────

    /** Supported singleton — cursor-based paging via continuation tokens. */
    public static final Capability CONTINUATION_TOKEN_PAGING_CAP    = intern(CONTINUATION_TOKEN_PAGING, true);
    /** Unsupported singleton — continuation token paging. */
    public static final Capability CONTINUATION_TOKEN_PAGING_UNSUPPORTED = intern(CONTINUATION_TOKEN_PAGING, false);

    /** Supported singleton — multi-document transactions. */
    public static final Capability TRANSACTIONS_CAP                 = intern(TRANSACTIONS, true);
    /** Unsupported singleton — transactions. */
    public static final Capability TRANSACTIONS_UNSUPPORTED         = intern(TRANSACTIONS, false);

    /** Supported singleton — batch read/write operations. */
    public static final Capability BATCH_OPERATIONS_CAP             = intern(BATCH_OPERATIONS, true);
    /** Unsupported singleton — batch operations. */
    public static final Capability BATCH_OPERATIONS_UNSUPPORTED     = intern(BATCH_OPERATIONS, false);

    /** Supported singleton — strongly-consistent reads. */
    public static final Capability STRONG_CONSISTENCY_CAP           = intern(STRONG_CONSISTENCY, true);
    /** Unsupported singleton — strong consistency. */
    public static final Capability STRONG_CONSISTENCY_UNSUPPORTED   = intern(STRONG_CONSISTENCY, false);

    /** Supported singleton — change feed / event stream. */
    public static final Capability CHANGE_FEED_CAP                  = intern(CHANGE_FEED, true);
    /** Unsupported singleton — change feed. */
    public static final Capability CHANGE_FEED_UNSUPPORTED          = intern(CHANGE_FEED, false);

    /** Supported singleton — portable query expression DSL. */
    public static final Capability PORTABLE_QUERY_EXPRESSION_CAP    = intern(PORTABLE_QUERY_EXPRESSION, true);
    /** Unsupported singleton — portable query expression. */
    public static final Capability PORTABLE_QUERY_EXPRESSION_UNSUPPORTED = intern(PORTABLE_QUERY_EXPRESSION, false);

    /** Supported singleton — ORDER BY (restricted to sortKey field). */
    public static final Capability ORDER_BY_CAP                     = intern(ORDER_BY, true);
    /** Unsupported singleton — ORDER BY. */
    public static final Capability ORDER_BY_UNSUPPORTED             = intern(ORDER_BY, false);

    // ── Instance fields ───────────────────────────────────────────────────────

    private final String name;
    private final boolean supported;
    private final String notes;

    private Capability(String name, boolean supported, String notes) {
        this.name = name;
        this.supported = supported;
        this.notes = notes;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Return the interned instance for the given name/supported pair (no notes).
     * Well-known capabilities return the pre-built singleton; unknown names are
     * registered and interned on first call.
     */
    public static Capability of(String name, boolean supported) {
        return REGISTRY.computeIfAbsent(registryKey(name, supported),
                k -> new Capability(name, supported, null));
    }

    /**
     * Create a capability with provider-specific notes.
     * <p>
     * Because notes vary per provider, instances with notes are <em>not</em>
     * interned — a new instance is returned each time. For a notes-free instance
     * use {@link #of(String, boolean)}.
     */
    public static Capability of(String name, boolean supported, String notes) {
        if (notes == null || notes.isBlank()) {
            return of(name, supported);
        }
        return new Capability(name, supported, notes);
    }

    /**
     * Return a copy of this capability with the given notes attached.
     */
    public Capability withNotes(String notes) {
        return of(this.name, this.supported, notes);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String name() {
        return name;
    }

    public boolean supported() {
        return supported;
    }

    /**
     * Optional provider-specific description, or {@code null} if not set.
     */
    public String notes() {
        return notes;
    }

    // ── Object ────────────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Capability c)) return false;
        return supported == c.supported && name.equals(c.name);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + Boolean.hashCode(supported);
    }

    @Override
    public String toString() {
        return "Capability{" + name + "=" + (supported ? "supported" : "unsupported")
                + (notes != null ? ", notes=" + notes : "") + "}";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Capability intern(String name, boolean supported) {
        return REGISTRY.computeIfAbsent(registryKey(name, supported),
                k -> new Capability(name, supported, null));
    }

    private static String registryKey(String name, boolean supported) {
        return name + ':' + supported;
    }

    /**
     * Returns an unmodifiable snapshot of all registered capability instances.
     */
    public static Collection<Capability> registeredValues() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }
}
