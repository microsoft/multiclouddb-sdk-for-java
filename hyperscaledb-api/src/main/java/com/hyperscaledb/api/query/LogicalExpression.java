// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.Objects;

/**
 * A logical expression combining two sub-expressions with AND or OR.
 *
 * @param left  the left sub-expression
 * @param op    the logical operator (AND or OR)
 * @param right the right sub-expression
 */
public record LogicalExpression(Expression left, LogicalOp op, Expression right)
        implements Expression {

    public LogicalExpression {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(op, "op must not be null");
        Objects.requireNonNull(right, "right must not be null");
    }
}
