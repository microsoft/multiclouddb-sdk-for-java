// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

/**
 * A typed literal value: string, number, boolean, or null.
 */
public record Literal(Object value) {

    /**
     * The type of this literal.
     */
    public LiteralType type() {
        if (value == null)
            return LiteralType.NULL;
        if (value instanceof String)
            return LiteralType.STRING;
        if (value instanceof Number)
            return LiteralType.NUMBER;
        if (value instanceof Boolean)
            return LiteralType.BOOLEAN;
        return LiteralType.STRING; // fallback
    }

    public enum LiteralType {
        STRING, NUMBER, BOOLEAN, NULL
    }

    @Override
    public String toString() {
        if (value == null)
            return "null";
        if (value instanceof String s)
            return "'" + s + "'";
        return value.toString();
    }
}
