// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.spi;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.query.ExpressionTranslator;

/**
 * SPI contract for provider adapter discovery via
 * {@link java.util.ServiceLoader}.
 * <p>
 * Each provider module registers an implementation of this interface in
 * {@code META-INF/services/com.hyperscaledb.spi.HyperscaleDbProviderAdapter}.
 */
public interface HyperscaleDbProviderAdapter {

    /**
     * The provider this adapter handles.
     */
    ProviderId providerId();

    /**
     * Create a provider client for the given configuration.
     *
     * @param config client configuration (connection, auth, options)
     * @return a new provider client instance
     */
    HyperscaleDbProviderClient createClient(HyperscaleDbClientConfig config);

    /**
     * Create an expression translator for portable query support.
     * Providers that support portable query expressions should override this
     * method.
     *
     * @return the expression translator, or {@code null} if portable expressions
     *         are not supported
     */
    default ExpressionTranslator createExpressionTranslator() {
        return null;
    }
}
