package com.hyperscaledb.api.query;

/**
 * Logical operators for combining expressions.
 */
public enum LogicalOp {
    AND,
    OR;

    /**
     * Parse a logical operator from its keyword (case-insensitive).
     *
     * @throws IllegalArgumentException if the keyword is not recognized
     */
    public static LogicalOp fromKeyword(String keyword) {
        return switch (keyword.toUpperCase()) {
            case "AND" -> AND;
            case "OR" -> OR;
            default -> throw new IllegalArgumentException("Unknown logical operator: " + keyword);
        };
    }
}
