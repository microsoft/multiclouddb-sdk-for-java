// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extensible string-based error category for provider-neutral failure
 * classification.
 * <p>
 * Follows the <em>expandable string enum</em> pattern: the well-known
 * categories are exposed as {@code public static final} constants, but
 * callers can create additional instances at runtime via
 * {@link #fromString(String)}. This means:
 * <ul>
 *   <li>New categories <strong>will</strong> be added in future minor
 *       versions without a major version bump — they are not breaking
 *       changes.</li>
 *   <li>Consumers <strong>must not</strong> use Java {@code switch}
 *       statements on values returned by {@link HyperscaleDbException#error()}.
 *       Use {@code .equals()} comparisons or an {@code if/else} chain with
 *       a final {@code else} branch that handles unknown categories
 *       gracefully (e.g., treat them like {@link #PROVIDER_ERROR}).</li>
 *   <li>Equality is purely string-based: two instances with the same
 *       value string are equal.</li>
 * </ul>
 *
 * <h3>Recommended usage pattern</h3>
 * <pre>{@code
 * HyperscaleDbErrorCategory cat = ex.error().category();
 * if (HyperscaleDbErrorCategory.NOT_FOUND.equals(cat)) {
 *     // handle not found
 * } else if (HyperscaleDbErrorCategory.THROTTLED.equals(cat)) {
 *     // back off and retry
 * } else {
 *     // handle unknown / unexpected categories
 *     log.warn("Unexpected error category: {}", cat);
 * }
 * }</pre>
 */
public final class HyperscaleDbErrorCategory {

    // ── Well-known categories ────────────────────────────────────────────────

    /** The request was malformed or contained invalid parameters. */
    public static final HyperscaleDbErrorCategory INVALID_REQUEST =
            fromString("INVALID_REQUEST");

    /** Authentication credentials were missing, expired, or invalid. */
    public static final HyperscaleDbErrorCategory AUTHENTICATION_FAILED =
            fromString("AUTHENTICATION_FAILED");

    /**
     * The authenticated identity does not have permission to perform the
     * requested operation.
     */
    public static final HyperscaleDbErrorCategory AUTHORIZATION_FAILED =
            fromString("AUTHORIZATION_FAILED");

    /** The requested resource (item, container, or database) was not found. */
    public static final HyperscaleDbErrorCategory NOT_FOUND =
            fromString("NOT_FOUND");

    /**
     * The operation conflicted with the current state of the resource
     * (e.g., duplicate key on create, precondition failure on replace).
     */
    public static final HyperscaleDbErrorCategory CONFLICT =
            fromString("CONFLICT");

    /**
     * The request was rate-limited or throttled by the provider.
     * Safe to retry after a back-off delay.
     */
    public static final HyperscaleDbErrorCategory THROTTLED =
            fromString("THROTTLED");

    /**
     * A transient infrastructure failure occurred.
     * Safe to retry, typically with exponential back-off.
     */
    public static final HyperscaleDbErrorCategory TRANSIENT_FAILURE =
            fromString("TRANSIENT_FAILURE");

    /**
     * A permanent, non-retryable provider failure occurred.
     * Retrying will not succeed without a configuration or data change.
     */
    public static final HyperscaleDbErrorCategory PERMANENT_FAILURE =
            fromString("PERMANENT_FAILURE");

    /**
     * An unclassified or unexpected error was returned by the provider.
     * Check {@link HyperscaleDbError#providerDetails()} for the raw
     * provider error code.
     */
    public static final HyperscaleDbErrorCategory PROVIDER_ERROR =
            fromString("PROVIDER_ERROR");

    /**
     * The operation requires a provider capability that is not supported
     * by the currently configured provider.
     *
     * @see com.hyperscaledb.api.CapabilitySet
     */
    public static final HyperscaleDbErrorCategory UNSUPPORTED_CAPABILITY =
            fromString("UNSUPPORTED_CAPABILITY");

    // ── Internal registry ────────────────────────────────────────────────────

    /**
     * Interned registry: ensures that {@code fromString("X") == fromString("X")}
     * for all well-known constants and any values created at runtime.
     */
    private static final Map<String, HyperscaleDbErrorCategory> VALUES =
            new ConcurrentHashMap<>();

    private final String value;

    private HyperscaleDbErrorCategory(String value) {
        this.value = value;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Returns the {@code HyperscaleDbErrorCategory} for the given string value,
     * creating and interning a new instance if the value has not been seen before.
     * <p>
     * The lookup is case-sensitive. Passing {@code null} throws
     * {@link NullPointerException}.
     *
     * @param value the category string (e.g. {@code "NOT_FOUND"})
     * @return the corresponding category instance; never {@code null}
     */
    public static HyperscaleDbErrorCategory fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return VALUES.computeIfAbsent(value, HyperscaleDbErrorCategory::new);
    }

    /**
     * Returns an unmodifiable snapshot of all currently known category values,
     * including any dynamically created via {@link #fromString(String)}.
     *
     * @return unmodifiable collection of all interned categories
     */
    public static Collection<HyperscaleDbErrorCategory> values() {
        return Collections.unmodifiableCollection(VALUES.values());
    }

    // ── Value accessors ──────────────────────────────────────────────────────

    /**
     * Returns the string value of this category (e.g. {@code "NOT_FOUND"}).
     *
     * @return the category string; never {@code null}
     */
    public String getValue() {
        return value;
    }

    // ── Object overrides ─────────────────────────────────────────────────────

    /**
     * Two {@code HyperscaleDbErrorCategory} instances are equal if and only if
     * their string values are equal (case-sensitive).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HyperscaleDbErrorCategory other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Returns the string value, suitable for logging and serialisation.
     *
     * @return the category string (e.g. {@code "NOT_FOUND"})
     */
    @Override
    public String toString() {
        return value;
    }
}
