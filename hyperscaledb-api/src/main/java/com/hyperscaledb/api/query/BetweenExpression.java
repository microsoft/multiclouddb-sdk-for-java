package com.hyperscaledb.api.query;

import java.util.Objects;

/**
 * A BETWEEN expression: {@code field BETWEEN low AND high}.
 *
 * @param field the field reference
 * @param low   the lower bound (Literal or Parameter)
 * @param high  the upper bound (Literal or Parameter)
 */
public record BetweenExpression(FieldRef field, Object low, Object high) implements Expression {

    public BetweenExpression {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(low, "low must not be null");
        Objects.requireNonNull(high, "high must not be null");
    }
}
