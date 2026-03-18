// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.PortabilityWarning;
import com.hyperscaledb.api.ProviderId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spanner provider-specific extensions and opt-ins.
 * Produces portability warnings when non-portable features are enabled.
 */
public final class SpannerExtensions {

    /** Feature flag: enable read-only transactions for query operations */
    public static final String FLAG_READ_ONLY_TRANSACTIONS = "spanner.readOnlyTransactions";

    /** Feature flag: use stale reads with a specified staleness bound */
    public static final String FLAG_STALE_READS = "spanner.staleReads";

    private SpannerExtensions() {
    }

    /**
     * Check feature flags in config and return any portability warnings.
     */
    public static List<PortabilityWarning> checkFeatureFlags(HyperscaleDbClientConfig config) {
        List<PortabilityWarning> warnings = new ArrayList<>();
        Map<String, String> flags = config.featureFlags();

        if ("true".equalsIgnoreCase(flags.get(FLAG_READ_ONLY_TRANSACTIONS))) {
            warnings.add(new PortabilityWarning(
                    "SPANNER_READ_ONLY_TRANSACTIONS",
                    "Read-only transactions are a Spanner-specific isolation feature that may not be available on other providers.",
                    "query",
                    ProviderId.SPANNER));
        }

        if ("true".equalsIgnoreCase(flags.get(FLAG_STALE_READS))) {
            warnings.add(new PortabilityWarning(
                    "SPANNER_STALE_READS",
                    "Stale reads with configurable staleness bounds are a Spanner-specific feature.",
                    "get",
                    ProviderId.SPANNER));
        }

        return warnings;
    }
}
