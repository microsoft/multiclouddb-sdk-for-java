package com.hyperscaledb.api;

import com.hyperscaledb.api.internal.DefaultHyperscaleDbClient;
import com.hyperscaledb.api.query.ExpressionTranslator;
import com.hyperscaledb.spi.HyperscaleDbProviderAdapter;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Factory that creates {@link HyperscaleDbClient} instances by discovering provider
 * adapters
 * via {@link ServiceLoader}.
 */
public final class HyperscaleDbClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(HyperscaleDbClientFactory.class);

    private HyperscaleDbClientFactory() {
    }

    /**
     * Create a HyperscaleDbClient from configuration.
     * Discovers the matching provider adapter via ServiceLoader and delegates
     * client creation.
     *
     * @param config client configuration specifying provider and connection details
     * @return a fully configured HyperscaleDbClient
     * @throws HyperscaleDbException if no adapter is found for the requested provider
     */
    public static HyperscaleDbClient create(HyperscaleDbClientConfig config) {
        ProviderId requestedProvider = config.provider();
        LOG.info("Creating Hyperscale DB client for provider: {}", requestedProvider.id());

        ServiceLoader<HyperscaleDbProviderAdapter> loader = ServiceLoader.load(HyperscaleDbProviderAdapter.class);

        for (HyperscaleDbProviderAdapter adapter : loader) {
            if (adapter.providerId() == requestedProvider) {
                LOG.info("Found adapter: {} for provider: {}", adapter.getClass().getName(), requestedProvider.id());
                HyperscaleDbProviderClient providerClient = adapter.createClient(config);
                DefaultHyperscaleDbClient client = new DefaultHyperscaleDbClient(providerClient, config);

                // Wire portable expression translator if the provider supports it
                ExpressionTranslator translator = adapter.createExpressionTranslator();
                if (translator != null) {
                    client.setExpressionTranslator(translator);
                    LOG.info("Expression translator wired for provider: {}", requestedProvider.id());
                }

                // Check feature flags and propagate portability warnings (T088)
                List<PortabilityWarning> warnings = adapter.checkFeatureFlags(config);
                for (PortabilityWarning warning : warnings) {
                    LOG.warn("Portability warning [{}]: {} (scope={}, provider={})",
                            warning.code(), warning.message(), warning.scope(), warning.provider().id());
                }

                return client;
            }
        }

        throw new HyperscaleDbException(new HyperscaleDbError(
                HyperscaleDbErrorCategory.INVALID_REQUEST,
                "No provider adapter found for: " + requestedProvider.id()
                        + ". Ensure the provider module is on the classpath.",
                requestedProvider,
                "create",
                false,
                null));
    }
}
