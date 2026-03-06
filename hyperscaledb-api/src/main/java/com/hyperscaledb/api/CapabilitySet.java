package com.hyperscaledb.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
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
     * All capabilities.
     */
    public Collection<Capability> all() {
        return capabilities.values();
    }

    @Override
    public String toString() {
        return "CapabilitySet" + capabilities.values();
    }
}
