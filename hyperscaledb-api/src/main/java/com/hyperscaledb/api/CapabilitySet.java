// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable set of capabilities reported by a provider.
 */
public final class CapabilitySet {

    private final Map<String, Capability> capabilities;

    public CapabilitySet(Collection<Capability> capabilities) {
        Map<String, Capability> map = new LinkedHashMap<>();
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
     * Get a specific capability (supported or not). Returns null if unknown.
     */
    public Capability get(String capabilityName) {
        return capabilities.get(capabilityName);
    }

    /**
     * Returns an unmodifiable snapshot of all capabilities declared by this
     * provider.
     * <p>
     * The returned list is unmodifiable; mutations throw
     * {@link UnsupportedOperationException}.
     */
    public List<Capability> all() {
        return List.copyOf(capabilities.values());
    }

    @Override
    public String toString() {
        return "CapabilitySet" + capabilities.values();
    }
}
