// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.List;
import java.util.Objects;

/**
 * A portable function call expression (e.g.,
 * {@code starts_with(name, @prefix)}).
 *
 * @param function  the portable function being called
 * @param arguments the function arguments (FieldRef, Literal, or Parameter
 *                  instances)
 */
public record FunctionCallExpression(PortableFunction function, List<Object> arguments)
        implements Expression {

    public FunctionCallExpression {
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        arguments = List.copyOf(arguments); // defensive copy
    }
}
