// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.util.List;
import java.util.Objects;

/** An immutable ordered array. Container nesting is limited to 128 levels. */
public final class ArrayValue implements DocumentValue {
    private final List<DocumentValue> values;
    private final int depth;

    private ArrayValue(List<? extends DocumentValue> values) {
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
        this.depth = ValueDepth.container(this.values);
    }

    /** Copies the list; elements must be non-null immutable document values. */
    public static ArrayValue of(List<? extends DocumentValue> values) {
        return new ArrayValue(values);
    }

    /** Returns the immutable elements in encounter order. */
    public List<DocumentValue> values() {
        return values;
    }

    int depth() {
        return depth;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArrayValue array && values.equals(array.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "ArrayValue[size=" + values.size() + "]";
    }
}
