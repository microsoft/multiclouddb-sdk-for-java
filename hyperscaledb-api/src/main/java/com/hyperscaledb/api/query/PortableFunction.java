// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

/**
 * Portable functions available on all providers. Each function has a canonical
 * name used in portable expressions.
 */
public enum PortableFunction {
    /** Check if a string field starts with a prefix. */
    STARTS_WITH("starts_with"),
    /** Check if a string field contains a substring. */
    CONTAINS("contains"),
    /** Check if a field exists (is defined / not null). */
    FIELD_EXISTS("field_exists"),
    /** Get the length of a string field. */
    STRING_LENGTH("string_length"),
    /** Get the size of an array/collection field. */
    COLLECTION_SIZE("collection_size");

    private final String functionName;

    PortableFunction(String functionName) {
        this.functionName = functionName;
    }

    public String functionName() {
        return functionName;
    }

    /**
     * Resolve a PortableFunction from its canonical name (case-insensitive).
     *
     * @return the function, or null if not recognized
     */
    public static PortableFunction fromName(String name) {
        for (PortableFunction f : values()) {
            if (f.functionName.equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }
}
