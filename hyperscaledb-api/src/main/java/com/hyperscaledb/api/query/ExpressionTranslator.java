// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.Map;

/**
 * SPI interface for translating a portable expression AST into
 * a provider-specific query string (e.g., Cosmos SQL, DynamoDB PartiQL, Spanner
 * GoogleSQL).
 * <p>
 * Each provider module supplies its own implementation.
 */
public interface ExpressionTranslator {

    /**
     * Translate a portable expression AST into a provider-specific query.
     *
     * @param expression the parsed and validated expression AST
     * @param parameters the parameter map (name → value)
     * @param container  the container/table name for the query
     * @return the translated query with provider-native syntax
     */
    TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container);
}
