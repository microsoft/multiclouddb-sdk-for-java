// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.Objects;

/**
 * An {@code @paramName} reference that is resolved from the query parameters
 * map.
 */
public record Parameter(String name) {

    public Parameter {
        Objects.requireNonNull(name, "Parameter name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Parameter name must not be blank");
        }
    }

    @Override
    public String toString() {
        return "@" + name;
    }
}
