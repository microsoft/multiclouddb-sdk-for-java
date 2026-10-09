// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An immutable object whose equality ignores field order. Maximum depth is 128. */
public final class ObjectValue implements DocumentValue {
    private final Map<String, DocumentValue> fields;
    private final int depth;

    private ObjectValue(Map<String, ? extends DocumentValue> fields) {
        Objects.requireNonNull(fields, "fields");
        Map<String, DocumentValue> copy = new LinkedHashMap<>();
        fields.forEach((name, value) -> copy.put(
                Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
        this.fields = Collections.unmodifiableMap(copy);
        this.depth = ValueDepth.container(copy.values());
    }

    /** Copies fields; duplicate names already overwritten in a Map cannot be detected. */
    public static ObjectValue of(Map<String, ? extends DocumentValue> fields) {
        return new ObjectValue(fields);
    }

    /** Returns immutable fields in their original encounter order. */
    public Map<String, DocumentValue> fields() {
        return fields;
    }

    /** Returns empty for absence, or a value (including NullValue) for a present field. */
    public Optional<DocumentValue> get(String name) {
        return Optional.ofNullable(fields.get(Objects.requireNonNull(name, "name")));
    }

    int depth() {
        return depth;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ObjectValue object && fields.equals(object.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return "ObjectValue[size=" + fields.size() + "]";
    }
}
