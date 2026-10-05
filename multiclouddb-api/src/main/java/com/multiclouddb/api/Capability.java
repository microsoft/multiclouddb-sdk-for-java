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
 * The common baseline follows Lowest-Common-Denominator (LCD) portability.
 * Optional extensions may have different provider support and must be
 * explicitly declared and capability-gated. For example, extended change-feed
 * history is supported by Cosmos and Spanner but not by DynamoDB.
 * <p>
 * <h3>Usage</h3>
 * Some well-known capabilities expose pre-built singleton pairs, for example:
 * {@code Capability.TRANSACTIONS_CAP} (supported) and
 * {@code Capability.TRANSACTIONS_UNSUPPORTED} (unsupported).
 * Providers can use these pairs directly. Names without public singleton pairs,
 * such as {@link #RESULT_LIMIT}, {@link #ROW_LEVEL_TTL}, and {@link #WRITE_TIMESTAMP},
 * can use {@link #of(String, boolean)} to obtain notes-free interned instances.
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

    // ── Well-known baseline and optional-extension capability names ──────────

    public static final String CONTINUATION_TOKEN_PAGING    = "continuation_token_paging";
    public static final String CROSS_PARTITION_QUERY        = "cross_partition_query";
    public static final String TRANSACTIONS                 = "transactions";
    public static final String BATCH_OPERATIONS             = "batch_operations";
    public static final String STRONG_CONSISTENCY           = "strong_consistency";
    public static final String NATIVE_SQL_QUERY             = "native_sql_query";
    public static final String CHANGE_FEED                  = "change_feed";
    public static final String EXTENDED_CHANGE_FEED_HISTORY = "extended_change_feed_history";
    public static final String PORTABLE_QUERY_EXPRESSION    = "portable_query_expression";
    public static final String LIKE_OPERATOR                = "like_operator";
    public static final String ORDER_BY                     = "order_by";
    public static final String ENDS_WITH                    = "ends_with";
    public static final String REGEX_MATCH                  = "regex_match";
    public static final String CASE_FUNCTIONS               = "case_functions";
    public static final String RESULT_LIMIT                 = "result_limit";
    public static final String ROW_LEVEL_TTL                = "row_level_ttl";
    public static final String WRITE_TIMESTAMP              = "write_timestamp";

    // ── Pre-built singleton instances ─────────────────────────────────────────

    /** Supported singleton — cursor-based paging via continuation tokens. */
    public static final Capability CONTINUATION_TOKEN_PAGING_CAP    = intern(CONTINUATION_TOKEN_PAGING, true);
    /** Unsupported singleton — continuation token paging. */
    public static final Capability CONTINUATION_TOKEN_PAGING_UNSUPPORTED = intern(CONTINUATION_TOKEN_PAGING, false);

    public static final Capability CROSS_PARTITION_QUERY_CAP = intern(CROSS_PARTITION_QUERY, true);
    public static final Capability CROSS_PARTITION_QUERY_UNSUPPORTED = intern(CROSS_PARTITION_QUERY, false);
    public static final Capability NATIVE_SQL_QUERY_CAP = intern(NATIVE_SQL_QUERY, true);
    public static final Capability NATIVE_SQL_QUERY_UNSUPPORTED = intern(NATIVE_SQL_QUERY, false);
    public static final Capability LIKE_OPERATOR_CAP = intern(LIKE_OPERATOR, true);
    public static final Capability LIKE_OPERATOR_UNSUPPORTED = intern(LIKE_OPERATOR, false);
    public static final Capability ENDS_WITH_CAP = intern(ENDS_WITH, true);
    public static final Capability ENDS_WITH_UNSUPPORTED = intern(ENDS_WITH, false);
    public static final Capability REGEX_MATCH_CAP = intern(REGEX_MATCH, true);
    public static final Capability REGEX_MATCH_UNSUPPORTED = intern(REGEX_MATCH, false);
    public static final Capability CASE_FUNCTIONS_CAP = intern(CASE_FUNCTIONS, true);
    public static final Capability CASE_FUNCTIONS_UNSUPPORTED = intern(CASE_FUNCTIONS, false);

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

    /** Supported singleton — change-feed history beyond the 24h portable baseline. */
    public static final Capability EXTENDED_CHANGE_FEED_HISTORY_CAP         = intern(EXTENDED_CHANGE_FEED_HISTORY, true);
    /** Unsupported singleton — provider cannot retain change-feed history past 24h. */
    public static final Capability EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED = intern(EXTENDED_CHANGE_FEED_HISTORY, false);

    /** Supported singleton — portable query expression DSL. */
    public static final Capability PORTABLE_QUERY_EXPRESSION_CAP    = intern(PORTABLE_QUERY_EXPRESSION, true);
    /** Unsupported singleton — portable query expression. */
    public static final Capability PORTABLE_QUERY_EXPRESSION_UNSUPPORTED = intern(PORTABLE_QUERY_EXPRESSION, false);

    /** Supported singleton — ORDER BY clause; check provider support before use. */
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
     * Returns an existing pre-built singleton when the name/supported pair has one.
     * Other pairs, including well-known names without public singleton fields, are
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
