// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.Objects;

/**
 * A NOT expression wrapping a child expression.
 *
 * @param child the expression to negate
 */
public record NotExpression(Expression child) implements Expression {

    public NotExpression {
        Objects.requireNonNull(child, "child must not be null");
    }
}
