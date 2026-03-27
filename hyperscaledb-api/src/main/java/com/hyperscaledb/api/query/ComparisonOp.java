// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

/**
 * Comparison operators for portable expressions.
 */
public enum ComparisonOp {
    EQ("="),
    NE("<>"),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">=");

    private final String symbol;

    ComparisonOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * Parse a comparison operator from its SQL symbol.
     *
     * @throws IllegalArgumentException if the symbol is not recognized
     */
    public static ComparisonOp fromSymbol(String symbol) {
        for (ComparisonOp op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        // Also support != as an alias for <>
        if ("!=".equals(symbol)) {
            return NE;
        }
        throw new IllegalArgumentException("Unknown comparison operator: " + symbol);
    }
}
