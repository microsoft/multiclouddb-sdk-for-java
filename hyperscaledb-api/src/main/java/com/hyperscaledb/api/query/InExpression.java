package com.hyperscaledb.api.query;

import java.util.List;
import java.util.Objects;

/**
 * An IN expression: {@code field IN (value1, value2, ...)}.
 *
 * @param field  the field reference
 * @param values the list of values to check against (Literal or Parameter
 *               instances)
 */
public record InExpression(FieldRef field, List<Object> values) implements Expression {

    public InExpression {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("IN values must not be empty");
        }
        values = List.copyOf(values); // defensive copy
    }
}
