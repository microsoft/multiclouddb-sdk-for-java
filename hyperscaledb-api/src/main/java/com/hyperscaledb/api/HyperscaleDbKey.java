// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Portable representation of the minimum key material needed to uniquely
 * identify a record across cloud database providers.
 * <p>
 * Uses DynamoDB-inspired naming:
 * <ul>
 * <li>{@code partitionKey} (mandatory) — the distribution/hash key that
 * determines which physical partition stores the item</li>
 * <li>{@code sortKey} (optional) — the item identifier within a partition;
 * maps to DynamoDB sort/range key, Cosmos DB {@code id}, or Spanner's
 * second primary-key column</li>
 * <li>{@code components} — additional composite key material (future use)</li>
 * </ul>
 * <p>
 * Instances are immutable. The {@link #toString()} representation is computed
 * once at construction time and cached.
 */
public final class HyperscaleDbKey {

    private final String partitionKey;
    private final String sortKey;
    private final Map<String, String> components;
    /** Cached toString value — computed once in the constructor. */
    private final String stringValue;

    private HyperscaleDbKey(String partitionKey, String sortKey, Map<String, String> components) {
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("HyperscaleDbKey partitionKey must be non-empty");
        }
        this.partitionKey = partitionKey;
        this.sortKey = sortKey;
        this.components = components != null ? Map.copyOf(components) : Collections.emptyMap();
        this.stringValue = buildString(partitionKey, sortKey, this.components);
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    /**
     * Create a key with only a partition key (no sort key).
     *
     * @param partitionKey the mandatory partition/distribution key; must be
     *                     non-null and non-blank
     * @return a new {@code HyperscaleDbKey}
     * @throws IllegalArgumentException if {@code partitionKey} is null or blank
     */
    public static HyperscaleDbKey of(String partitionKey) {
        return new HyperscaleDbKey(partitionKey, null, null);
    }

    /**
     * Create a key with a partition key and a sort key.
     *
     * @param partitionKey the mandatory partition/distribution key
     * @param sortKey      the optional sort/range key; may be {@code null}
     * @return a new {@code HyperscaleDbKey}
     * @throws IllegalArgumentException if {@code partitionKey} is null or blank
     */
    public static HyperscaleDbKey of(String partitionKey, String sortKey) {
        return new HyperscaleDbKey(partitionKey, sortKey, null);
    }

    /**
     * Create a key with a partition key, sort key, and additional composite
     * components.
     *
     * @param partitionKey the mandatory partition/distribution key
     * @param sortKey      the optional sort/range key; may be {@code null}
     * @param components   additional composite key fields; may be {@code null}
     * @return a new {@code HyperscaleDbKey}
     * @throws IllegalArgumentException if {@code partitionKey} is null or blank
     */
    public static HyperscaleDbKey of(String partitionKey, String sortKey,
            Map<String, String> components) {
        return new HyperscaleDbKey(partitionKey, sortKey, components);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * The partition/distribution key (mandatory). Determines which physical
     * partition stores the item.
     *
     * @return the partition key; never {@code null}
     */
    public String partitionKey() {
        return partitionKey;
    }

    /**
     * The sort/range key (optional). Identifies the item within its partition.
     *
     * @return the sort key, or {@code null} when the key has no sort component
     */
    public String sortKey() {
        return sortKey;
    }

    /**
     * Additional composite key components (future use).
     * <p>
     * The returned map is <em>unmodifiable</em>; mutations throw
     * {@link UnsupportedOperationException}. Returns an empty map when no
     * components were provided.
     *
     * @return unmodifiable map of additional key components; never {@code null}
     */
    public Map<String, String> components() {
        return components;
    }

    // ── Object overrides ──────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HyperscaleDbKey that)) return false;
        return partitionKey.equals(that.partitionKey)
                && Objects.equals(sortKey, that.sortKey)
                && components.equals(that.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partitionKey, sortKey, components);
    }

    /**
     * Returns a human-readable string representation of this key.
     * The value is computed once at construction time and cached.
     *
     * @return cached string representation, e.g.
     *         {@code HyperscaleDbKey{partitionKey=pk, sortKey=sk}}
     */
    @Override
    public String toString() {
        return stringValue;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String buildString(String partitionKey, String sortKey,
            Map<String, String> components) {
        StringBuilder sb = new StringBuilder("HyperscaleDbKey{partitionKey=").append(partitionKey);
        if (sortKey != null) sb.append(", sortKey=").append(sortKey);
        if (!components.isEmpty()) sb.append(", components=").append(components);
        return sb.append('}').toString();
    }
}

