// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, API-normalized set of provider capabilities.
 *
 * <p>Normalization is opt-in per capability. This API supplies a backward-compatible
 * unsupported default for the core partial-update capability; it does not synthesize
 * entries for every well-known capability omitted by a provider.</p>
 */
public final class CapabilitySet {

    private static final Capability DEFAULT_PARTIAL_UPDATE =
            Capability.PARTIAL_UPDATE_UNSUPPORTED.withNotes(
                    "Not declared by this provider; unsupported by default for backward compatibility");

    private final Map<String, Capability> capabilities;

    public CapabilitySet(Collection<Capability> capabilities) {
        Map<String, Capability> map = new LinkedHashMap<>();
        map.put(Capability.PARTIAL_UPDATE, DEFAULT_PARTIAL_UPDATE);

        for (Capability c : capabilities) {
            map.put(c.name(), c);
        }
        this.capabilities = Collections.unmodifiableMap(map);
    }

    /**
     * Check if a named capability is supported.
     */
    public boolean isSupported(String capabilityName) {
        Capability cap = capabilities.get(capabilityName);
        return cap != null && cap.supported();
    }

    /**
     * Get a declared or API-supplied capability (supported or not).
     *
     * @return the effective capability, or {@code null} when the provider omitted the name
     *         and the API defines no backward-compatible default for it; a name may therefore
     *         be well-known to the API and still be absent from this set
     */
    public Capability get(String capabilityName) {
        return capabilities.get(capabilityName);
    }

    /**
     * Returns an unmodifiable snapshot of the provider declarations plus any
     * capability-specific API defaults.
     *
     * <p>The snapshot is not a registry of every well-known capability; omitted names
     * without an API default remain absent. Mutations throw
     * {@link UnsupportedOperationException}.</p>
     */
    public List<Capability> all() {
        return List.copyOf(capabilities.values());
    }

    @Override
    public String toString() {
        return "CapabilitySet" + capabilities.values();
    }
}
