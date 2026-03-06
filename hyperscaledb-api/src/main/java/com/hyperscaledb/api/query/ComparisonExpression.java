package com.hyperscaledb.api.query;

import java.util.Objects;

/**
 * A comparison expression: {@code field op operand}.
 * <p>
 * The operand can be a {@link Literal} or a {@link Parameter}.
 *
 * @param field    the field reference
 * @param op       the comparison operator
 * @param operand  the value to compare against (Literal or Parameter)
 */
public record ComparisonExpression(FieldRef field, ComparisonOp op, Object operand)
        implements Expression {

    public ComparisonExpression {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(op, "op must not be null");
        Objects.requireNonNull(operand, "operand must not be null");
        if (!(operand instanceof Literal) && !(operand instanceof Parameter)) {
            throw new IllegalArgumentException(
                    "operand must be a Literal or Parameter, got: " + operand.getClass().getSimpleName());
        }
    }
}
