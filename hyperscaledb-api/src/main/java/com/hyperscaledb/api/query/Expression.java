package com.hyperscaledb.api.query;

/**
 * Sealed interface representing a portable query expression AST node.
 * <p>
 * All expression types are records that implement this interface:
 * <ul>
 * <li>{@link ComparisonExpression} — field op value/param</li>
 * <li>{@link LogicalExpression} — left AND/OR right</li>
 * <li>{@link NotExpression} — NOT child</li>
 * <li>{@link FunctionCallExpression} — portable function with arguments</li>
 * <li>{@link InExpression} — field IN (values)</li>
 * <li>{@link BetweenExpression} — field BETWEEN low AND high</li>
 * </ul>
 */
public sealed interface Expression
        permits ComparisonExpression, LogicalExpression, NotExpression,
        FunctionCallExpression, InExpression, BetweenExpression {
}
