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
 */
public final class Key {

    private final String partitionKey;
    private final String sortKey;
    private final Map<String, String> components;

    private Key(String partitionKey, String sortKey, Map<String, String> components) {
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("Key partitionKey must be non-empty");
        }
        this.partitionKey = partitionKey;
        this.sortKey = sortKey;
        this.components = components != null ? Map.copyOf(components) : Collections.emptyMap();
    }

    /**
     * Create a key with only a partition key (no sort key).
     */
    public static Key of(String partitionKey) {
        return new Key(partitionKey, null, null);
    }

    /**
     * Create a key with partition key and sort key.
     */
    public static Key of(String partitionKey, String sortKey) {
        return new Key(partitionKey, sortKey, null);
    }

    /**
     * Create a key with partition key, sort key, and additional composite
     * components.
     */
    public static Key of(String partitionKey, String sortKey, Map<String, String> components) {
        return new Key(partitionKey, sortKey, components);
    }

    /**
     * The partition/distribution key (mandatory). Determines which physical
     * partition stores the item.
     */
    public String partitionKey() {
        return partitionKey;
    }

    /**
     * The sort/range key (optional). Identifies the item within its partition.
     * Returns {@code null} when the key has no sort component.
     */
    public String sortKey() {
        return sortKey;
    }

    public Map<String, String> components() {
        return components;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Key that))
            return false;
        return partitionKey.equals(that.partitionKey) && Objects.equals(sortKey, that.sortKey)
                && components.equals(that.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partitionKey, sortKey, components);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Key{partitionKey=").append(partitionKey);
        if (sortKey != null)
            sb.append(", sortKey=").append(sortKey);
        if (!components.isEmpty())
            sb.append(", components=").append(components);
        return sb.append('}').toString();
    }
}
