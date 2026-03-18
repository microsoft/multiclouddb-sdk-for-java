// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.List;

/**
 * Exception thrown when expression validation fails.
 */
public class ExpressionValidationException extends RuntimeException {

    private final List<String> errors;

    /**
     * Create a new validation exception with the list of validation errors.
     *
     * @param errors the validation errors
     */
    public ExpressionValidationException(List<String> errors) {
        super("Expression validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /**
     * @return unmodifiable list of validation error messages
     */
    public List<String> getErrors() {
        return errors;
    }
}
