// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RegionFairness {

    enum Policy {
        WARN, FAIL, IGNORE;

        static Policy parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return WARN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "warn" -> WARN;
                case "fail" -> FAIL;
                case "ignore" -> IGNORE;
                default -> throw new IllegalArgumentException(
                        "--region-policy must be warn, fail, or ignore (was '" + raw + "')");
            };
        }
    }

    record ProviderRegion(String provider, String configuredRegion,
                          String probedRegion, String comparisonRegion) {
    }

    record CheckResult(List<String> messages, boolean failed) {
    }

    private RegionFairness() {
    }

    static String effectiveComparisonRegion(String configuredComparisonRegion,
                                            String probedRegion,
                                            String configuredRegion) {
        if (configuredComparisonRegion != null && !configuredComparisonRegion.isBlank()) {
            return configuredComparisonRegion.trim();
        }
        if (probedRegion != null && !probedRegion.isBlank() && !"unknown".equalsIgnoreCase(probedRegion)) {
            return probedRegion.trim();
        }
        if (configuredRegion != null && !configuredRegion.isBlank()) {
            return configuredRegion.trim();
        }
        return "unknown";
    }

    static CheckResult validate(List<ProviderRegion> providers, Policy policy) {
        if (policy == Policy.IGNORE) {
            return new CheckResult(List.of(), false);
        }
        List<String> messages = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        for (ProviderRegion provider : providers) {
            String configuredNorm = normalize(provider.configuredRegion());
            String probedNorm = normalize(provider.probedRegion());
            if (configuredNorm != null && probedNorm != null && !configuredNorm.equals(probedNorm)) {
                messages.add(String.format(Locale.ROOT,
                        "provider %s configured region '%s' does not match probed region '%s'",
                        provider.provider(), provider.configuredRegion(), provider.probedRegion()));
            }
            labels.add(normalize(provider.comparisonRegion()) == null ? "unknown" : normalize(provider.comparisonRegion()));
        }
        if (providers.size() > 1 && labels.size() > 1) {
            StringBuilder sb = new StringBuilder("comparison regions differ:");
            for (ProviderRegion provider : providers) {
                sb.append(' ').append(provider.provider()).append('=')
                        .append(provider.comparisonRegion());
            }
            messages.add(sb.toString());
        }
        return new CheckResult(messages, policy == Policy.FAIL && !messages.isEmpty());
    }

    static String normalize(String value) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
    }
}
