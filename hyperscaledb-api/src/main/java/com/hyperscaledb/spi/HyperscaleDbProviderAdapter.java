// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.spi;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.PortabilityWarning;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.query.ExpressionTranslator;

import java.util.Collections;
import java.util.List;

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
     * @param config client configuration (connection, auth, options, feature flags)
     * @return a new provider client instance
     */
    HyperscaleDbProviderClient createClient(HyperscaleDbClientConfig config);

    /**
     * Create an expression translator for portable query support.
     * Providers that support portable query expressions should override this
     * method.
     *
     * @return the expression translator, or null if portable expressions are not
     *         supported
     */
    default ExpressionTranslator createExpressionTranslator() {
        return null;
    }

    /**
     * Check provider-specific feature flags and return portability warnings.
     * Providers that define extension flags should override this method.
     *
     * @param config client configuration containing feature flags
     * @return a list of portability warnings (empty if none)
     */
    default List<PortabilityWarning> checkFeatureFlags(HyperscaleDbClientConfig config) {
        return Collections.emptyList();
    }
}
