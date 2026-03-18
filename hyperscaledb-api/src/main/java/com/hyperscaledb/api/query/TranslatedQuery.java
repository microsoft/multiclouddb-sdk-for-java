// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of translating a portable expression into a provider-specific query.
 *
 * @param queryString     the full provider-native query string (e.g., SQL,
 *                        PartiQL)
 * @param whereClause     the translated WHERE clause portion only (without
 *                        SELECT/FROM prefix)
 * @param boundParameters the parameters in provider-native format (ordered list
 *                        for positional,
 *                        or named map for named-parameter providers)
 */
public record TranslatedQuery(
        String queryString,
        String whereClause,
        Map<String, Object> namedParameters,
        List<Object> positionalParameters) {
    public TranslatedQuery {
        Objects.requireNonNull(queryString, "queryString must not be null");
        Objects.requireNonNull(whereClause, "whereClause must not be null");
        namedParameters = namedParameters != null ? Map.copyOf(namedParameters) : Map.of();
        positionalParameters = positionalParameters != null ? List.copyOf(positionalParameters) : List.of();
    }

    /**
     * Create a TranslatedQuery with named parameters (e.g., Cosmos, Spanner).
     */
    public static TranslatedQuery withNamedParameters(String queryString, String whereClause,
            Map<String, Object> namedParameters) {
        return new TranslatedQuery(queryString, whereClause, namedParameters, List.of());
    }

    /**
     * Create a TranslatedQuery with positional parameters (e.g., DynamoDB PartiQL).
     */
    public static TranslatedQuery withPositionalParameters(String queryString, String whereClause,
            List<Object> positionalParameters) {
        return new TranslatedQuery(queryString, whereClause, Map.of(), positionalParameters);
    }
}
