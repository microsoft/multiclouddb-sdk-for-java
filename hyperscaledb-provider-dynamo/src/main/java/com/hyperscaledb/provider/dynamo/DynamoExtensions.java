// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.PortabilityWarning;
import com.hyperscaledb.api.ProviderId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB provider-specific extensions and opt-ins.
 * Produces portability warnings when non-portable features are enabled.
 */
public final class DynamoExtensions {

    /** Feature flag: force strongly consistent reads for all get operations */
    public static final String FLAG_STRONG_CONSISTENT_READS = "dynamo.strongConsistentReads";

    /** Feature flag: use on-demand billing mode for created tables */
    public static final String FLAG_ON_DEMAND_BILLING = "dynamo.onDemandBilling";

    private DynamoExtensions() {
    }

    /**
     * Check feature flags in config and return any portability warnings.
     */
    public static List<PortabilityWarning> checkFeatureFlags(HyperscaleDbClientConfig config) {
        List<PortabilityWarning> warnings = new ArrayList<>();
        Map<String, String> flags = config.featureFlags();

        if ("true".equalsIgnoreCase(flags.get(FLAG_STRONG_CONSISTENT_READS))) {
            warnings.add(new PortabilityWarning(
                    "DYNAMO_STRONG_CONSISTENT_READS",
                    "Strongly consistent reads are a DynamoDB-specific feature that may not work identically on other providers.",
                    "get",
                    ProviderId.DYNAMO));
        }

        if ("true".equalsIgnoreCase(flags.get(FLAG_ON_DEMAND_BILLING))) {
            warnings.add(new PortabilityWarning(
                    "DYNAMO_ON_DEMAND_BILLING",
                    "On-demand billing mode is a DynamoDB-specific capacity configuration.",
                    "table",
                    ProviderId.DYNAMO));
        }

        return warnings;
    }
}
