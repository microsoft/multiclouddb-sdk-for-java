// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.document;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A fully immutable, object-root document, independent of any serializer library. */
public final class Document {
    private final ObjectValue root;

    private Document(ObjectValue root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Wraps an already immutable object value. */
    public static Document of(ObjectValue root) {
        return new Document(root);
    }

    /** Creates a builder which rejects duplicate field names. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the immutable object root. */
    public ObjectValue root() {
        return root;
    }

    /** Distinguishes absent fields from fields containing NullValue. */
    public Optional<DocumentValue> get(String name) {
        return root.get(name);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Document document && root.equals(document.root);
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    @Override
    public String toString() {
        return "Document[fields=" + root.fields().size() + "]";
    }

    /** Mutable construction state; each build produces an independent immutable document. */
    public static final class Builder {
        private final Map<String, DocumentValue> fields = new LinkedHashMap<>();

        private Builder() {
        }

        /** Adds a non-null name/value, rejecting a repeated name before modifying state. */
        public Builder put(String name, DocumentValue value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (fields.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate document field.");
            }
            fields.put(name, value);
            return this;
        }

        /** Takes an immutable snapshot of the current fields. */
        public Document build() {
            return Document.of(ObjectValue.of(fields));
        }
    }
}
