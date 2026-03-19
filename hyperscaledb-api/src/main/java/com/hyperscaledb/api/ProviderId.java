// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies a cloud database provider.
 * <p>
 * This class uses the <em>expandable value-object</em> pattern instead of an
 * {@code enum} so that third-party providers can register their own
 * {@code ProviderId} at runtime without requiring a new major SDK version.
 * Adding a new well-known constant here is a <strong>non-breaking</strong>
 * minor-version change.
 *
 * <h3>Consuming code guidelines</h3>
 * <ul>
 *   <li>Use {@link #equals(Object)} (or {@code ==}) to compare instances —
 *       {@link #fromId(String)} interns all values so reference equality holds
 *       for the same id string.</li>
 *   <li>Do <strong>not</strong> use Java {@code switch} statements on this
 *       type. Instead use {@code if}/{@code else if} chains on
 *       {@link #id()} with a {@code default} / catch-all branch so your code
 *       continues to work when new providers are added.</li>
 * </ul>
 *
 * <h3>Example — recommended pattern</h3>
 * <pre>{@code
 * ProviderId p = client.providerId();
 * if (ProviderId.COSMOS.equals(p)) {
 *     // Cosmos-specific path
 * } else if (ProviderId.DYNAMO.equals(p)) {
 *     // DynamoDB-specific path
 * } else {
 *     // handle any other / future provider
 * }
 * }</pre>
 */
public final class ProviderId {

    // ── Registry ─────────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, ProviderId> REGISTRY =
            new ConcurrentHashMap<>();

    // ── Well-known constants ──────────────────────────────────────────────────

    /** Azure Cosmos DB provider. */
    public static final ProviderId COSMOS  = register("cosmos",  "Azure Cosmos DB");

    /** Amazon DynamoDB provider. */
    public static final ProviderId DYNAMO  = register("dynamo",  "AWS DynamoDB");

    /** Google Cloud Spanner provider. */
    public static final ProviderId SPANNER = register("spanner", "Google Cloud Spanner");

    // ── Instance state ────────────────────────────────────────────────────────

    private final String id;
    private final String displayName;

    /** Private — use {@link #fromId(String)} or the well-known constants. */
    private ProviderId(String id, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = displayName != null ? displayName : id;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Return or create the {@code ProviderId} for the given string id
     * (case-insensitive).
     * <p>
     * If a {@code ProviderId} with a matching (lowercased) id has already been
     * registered, the same instance is returned — reference equality holds for
     * repeated calls with the same id.
     * <p>
     * If the id is not yet known, a new instance is registered with the id as
     * both id and display name. This allows providers loaded at runtime to
     * obtain a {@code ProviderId} without SDK changes.
     *
     * @param id the provider id string (case-insensitive); must be non-null and non-blank
     * @return the interned {@code ProviderId} for this id; never {@code null}
     * @throws IllegalArgumentException if {@code id} is null or blank
     */
    public static ProviderId fromId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ProviderId id must be non-blank");
        }
        String key = id.toLowerCase();
        return REGISTRY.computeIfAbsent(key, k -> new ProviderId(k, k));
    }

    /**
     * Register a new well-known {@code ProviderId} with an explicit display name.
     * <p>
     * If an instance with the same (lowercased) id already exists in the
     * registry, the existing instance is returned unchanged — the display name
     * of the first registration wins.
     *
     * @param id          the provider id string; must be non-null and non-blank
     * @param displayName the human-readable name shown in logs and diagnostics
     * @return the registered (possibly pre-existing) {@code ProviderId}
     */
    public static ProviderId register(String id, String displayName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ProviderId id must be non-blank");
        }
        String key = id.toLowerCase();
        return REGISTRY.computeIfAbsent(key, k -> new ProviderId(k, displayName));
    }

    /**
     * Return an unmodifiable snapshot of all currently registered
     * {@code ProviderId} instances, including any registered at runtime by
     * third-party providers.
     *
     * @return unmodifiable collection of known provider ids; never {@code null}
     */
    public static Collection<ProviderId> values() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * The canonical, lowercase provider id string (e.g. {@code "cosmos"}).
     *
     * @return the provider id; never {@code null}
     */
    public String id() {
        return id;
    }

    /**
     * A human-readable name for this provider suitable for log messages and
     * UI labels (e.g. {@code "Azure Cosmos DB"}).
     *
     * @return the display name; never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    // ── Object overrides ──────────────────────────────────────────────────────

    /**
     * Two {@code ProviderId} instances are equal if and only if their
     * {@link #id()} strings are equal (case-sensitive after normalisation).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderId that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns the provider id string (e.g. {@code "cosmos"}).
     */
    @Override
    public String toString() {
        return id;
    }
}
