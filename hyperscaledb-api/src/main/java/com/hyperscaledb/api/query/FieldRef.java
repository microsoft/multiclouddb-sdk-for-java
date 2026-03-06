package com.hyperscaledb.api.query;

import java.util.Objects;

/**
 * A reference to a document field. Supports single-level dot notation
 * (e.g., {@code address.city}).
 */
public record FieldRef(String name) {

    public FieldRef {
        Objects.requireNonNull(name, "Field name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Field name must not be blank");
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
