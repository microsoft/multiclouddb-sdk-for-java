// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.internal.DefaultMulticloudDbClient;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Factory that creates {@link MulticloudDbClient} instances by discovering
 * provider adapters via {@link ServiceLoader}.
 */
public final class MulticloudDbClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MulticloudDbClientFactory.class);

    private MulticloudDbClientFactory() {
    }

    /**
     * Create a {@link MulticloudDbClient} from configuration.
     * Discovers the matching provider adapter via {@link ServiceLoader} and
     * delegates client creation.
     *
     * @param config client configuration specifying provider, connection, and auth
     * @return a fully configured {@link MulticloudDbClient}
     * @throws MulticloudDbException if no adapter is found for the requested provider
     */
    public static MulticloudDbClient create(MulticloudDbClientConfig config) {
        ProviderId requestedProvider = config.provider();
        LOG.info("Creating Multicloud DB client for provider: {}", requestedProvider.id());

        ServiceLoader<MulticloudDbProviderAdapter> loader =
                ServiceLoader.load(MulticloudDbProviderAdapter.class);

        for (MulticloudDbProviderAdapter adapter : loader) {
            if (adapter.providerId() == requestedProvider) {
                LOG.info("Found adapter: {} for provider: {}",
                        adapter.getClass().getName(), requestedProvider.id());

                MulticloudDbProviderClient providerClient = adapter.createClient(config);

                // Build-time capability gate (T080-style fail-fast): if the user
                // requested extended change-feed retention via
                // ChangeFeedConfig.extendedRetention(...) but this provider does
                // not declare EXTENDED_CHANGE_FEED_HISTORY, surface a clean
                // UNSUPPORTED_CAPABILITY before any change-feed-substrate I/O
                // is issued. Misconfiguration must not lurk until the first
                // ensureContainer / listCursors / readChanges call.
                //
                // We have already paid for adapter.createClient(...) at this
                // point (some adapters open a control-plane gRPC channel /
                // refresh an auth token in their constructor), so we MUST
                // close the providerClient on a gate-throw to release pooled
                // HTTP connections, worker threads, and gRPC channels.
                try {
                    if (config.changeFeed().hasExtendedRetention()) {
                        CapabilitySet caps = providerClient.capabilities();
                        if (!caps.isSupported(Capability.EXTENDED_CHANGE_FEED_HISTORY)) {
                            Duration requested = config.changeFeed().extendedRetention().orElseThrow();
                            String providerNote = caps.get(Capability.EXTENDED_CHANGE_FEED_HISTORY) != null
                                    ? caps.get(Capability.EXTENDED_CHANGE_FEED_HISTORY).notes()
                                    : null;
                            String msg = "Provider " + requestedProvider.id()
                                    + " does not support Capability.EXTENDED_CHANGE_FEED_HISTORY — "
                                    + "extended change-feed retention (requested " + requested
                                    + ") is unavailable on this provider. "
                                    + "See docs/guide.md → 'Extending change-feed history beyond 24 hours' "
                                    + "for the per-provider capability matrix and supported escape hatches."
                                    + (providerNote != null ? " Provider note: " + providerNote : "");
                            throw new MulticloudDbException(new MulticloudDbError(
                                    MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                                    msg,
                                    requestedProvider,
                                    "create",
                                    false,
                                    Map.of(
                                            "reason", "extended_retention_unavailable",
                                            "capability", Capability.EXTENDED_CHANGE_FEED_HISTORY,
                                            "requestedRetention", requested.toString())));
                        }
                    }
                } catch (RuntimeException rethrow) {
                    try { providerClient.close(); } catch (Exception ignored) { /* best-effort */ }
                    throw rethrow;
                }

                DefaultMulticloudDbClient client = new DefaultMulticloudDbClient(providerClient, config);

                ExpressionTranslator translator = adapter.createExpressionTranslator();
                if (translator != null) {
                    client.setExpressionTranslator(translator);
                    LOG.info("Expression translator wired for provider: {}", requestedProvider.id());
                }

                return client;
            }
        }

        throw new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                "No provider adapter found for: " + requestedProvider.id()
                        + ". Ensure the provider module is on the classpath.",
                requestedProvider,
                "create",
                false,
                null));
    }
}
