package com.hyperscaledb.provider.cosmos;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.PortabilityWarning;
import com.hyperscaledb.api.ProviderId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cosmos DB provider-specific extensions and opt-ins.
 * Produces portability warnings when non-portable features are enabled.
 */
public final class CosmosExtensions {

    /** Feature flag: enable session-level consistency guarantee tracking */
    public static final String FLAG_SESSION_CONSISTENCY = "cosmos.sessionConsistency";

    /** Feature flag: enable cross-partition queries by default */
    public static final String FLAG_CROSS_PARTITION_QUERY = "cosmos.crossPartitionQuery";

    private CosmosExtensions() {
    }

    /**
     * Check feature flags in config and return any portability warnings.
     */
    public static List<PortabilityWarning> checkFeatureFlags(HyperscaleDbClientConfig config) {
        List<PortabilityWarning> warnings = new ArrayList<>();
        Map<String, String> flags = config.featureFlags();

        if ("true".equalsIgnoreCase(flags.get(FLAG_SESSION_CONSISTENCY))) {
            warnings.add(new PortabilityWarning(
                    "COSMOS_SESSION_CONSISTENCY",
                    "Session consistency tracking is a Cosmos DB-specific feature and may not be available on other providers.",
                    "client",
                    ProviderId.COSMOS));
        }

        if ("true".equalsIgnoreCase(flags.get(FLAG_CROSS_PARTITION_QUERY))) {
            warnings.add(new PortabilityWarning(
                    "COSMOS_CROSS_PARTITION_QUERY",
                    "Cross-partition queries may have different performance and cost characteristics on other providers.",
                    "query",
                    ProviderId.COSMOS));
        }

        return warnings;
    }
}
