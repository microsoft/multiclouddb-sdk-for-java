// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityTest {

    @Test
    @DisplayName("Missing partial-update capability defaults to unsupported")
    void missingPartialUpdateDefaultsToUnsupported() {
        CapabilitySet legacyProviderCapabilities = new CapabilitySet(
                List.of(Capability.TRANSACTIONS_CAP));

        Capability partialUpdate = legacyProviderCapabilities.get(Capability.PARTIAL_UPDATE);
        assertNotNull(partialUpdate);
        assertFalse(partialUpdate.supported());
        assertFalse(legacyProviderCapabilities.isSupported(Capability.PARTIAL_UPDATE));
        assertTrue(partialUpdate.notes().contains("backward compatibility"));
    }

    @Test
    @DisplayName("Normalization adds only the core partial-update default")
    void normalizationDoesNotSynthesizeUnrelatedKnownCapabilities() {
        CapabilitySet legacyProviderCapabilities = new CapabilitySet(
                List.of(Capability.TRANSACTIONS_CAP));

        assertEquals(2, legacyProviderCapabilities.all().size());
        assertNull(legacyProviderCapabilities.get(Capability.CROSS_PARTITION_QUERY),
                "An omitted known capability without an API default must remain absent");
        assertFalse(legacyProviderCapabilities.isSupported(Capability.CROSS_PARTITION_QUERY));
    }

    @Test
    @DisplayName("Explicit partial-update support overrides the API default")
    void explicitPartialUpdateSupportOverridesDefault() {
        CapabilitySet capabilities = new CapabilitySet(
                List.of(Capability.PARTIAL_UPDATE_CAP));

        assertTrue(capabilities.isSupported(Capability.PARTIAL_UPDATE));
        assertSame(Capability.PARTIAL_UPDATE_CAP,
                capabilities.get(Capability.PARTIAL_UPDATE));
    }

    @Test
    @DisplayName("Well-known supported singletons are the same instance via of() and the constant")
    void wellKnownSupportedSingleton() {
        assertSame(Capability.TRANSACTIONS_CAP,
                Capability.of(Capability.TRANSACTIONS, true),
                "of() must return the pre-built singleton");
    }

    @Test
    @DisplayName("Well-known unsupported singletons are the same instance via of() and the constant")
    void wellKnownUnsupportedSingleton() {
        assertSame(Capability.CROSS_PARTITION_QUERY_UNSUPPORTED,
                Capability.of(Capability.CROSS_PARTITION_QUERY, false));
    }

    @Test
    @DisplayName("Supported and unsupported singletons are distinct instances")
    void supportedAndUnsupportedAreDistinct() {
        assertNotSame(Capability.TRANSACTIONS_CAP, Capability.TRANSACTIONS_UNSUPPORTED);
        assertTrue(Capability.TRANSACTIONS_CAP.supported());
        assertFalse(Capability.TRANSACTIONS_UNSUPPORTED.supported());
    }

    @Test
    @DisplayName("withNotes() returns a new instance — not the singleton")
    void withNotesReturnsNewInstance() {
        Capability withNotes = Capability.TRANSACTIONS_CAP.withNotes("up to 100 items");
        assertNotSame(Capability.TRANSACTIONS_CAP, withNotes,
                "withNotes() must not mutate or return the singleton");
        assertEquals(Capability.TRANSACTIONS, withNotes.name());
        assertTrue(withNotes.supported());
        assertEquals("up to 100 items", withNotes.notes());
    }

    @Test
    @DisplayName("withNotes(null) returns the singleton")
    void withNotesNullReturnsSingleton() {
        assertSame(Capability.TRANSACTIONS_CAP,
                Capability.TRANSACTIONS_CAP.withNotes(null));
    }

    @Test
    @DisplayName("withNotes(blank) returns the singleton")
    void withNotesBlankReturnsSingleton() {
        assertSame(Capability.TRANSACTIONS_CAP,
                Capability.TRANSACTIONS_CAP.withNotes("   "));
    }

    @Test
    @DisplayName("equals is based on name + supported, ignoring notes")
    void equalsIgnoresNotes() {
        Capability withNotes = Capability.TRANSACTIONS_CAP.withNotes("some detail");
        assertEquals(Capability.TRANSACTIONS_CAP, withNotes,
                "capabilities with the same name/supported must be equal regardless of notes");
    }

    @Test
    @DisplayName("hashCode is consistent with equals")
    void hashCodeConsistentWithEquals() {
        Capability withNotes = Capability.TRANSACTIONS_CAP.withNotes("some detail");
        assertEquals(Capability.TRANSACTIONS_CAP.hashCode(), withNotes.hashCode());
    }

    @Test
    @DisplayName("of() for an unknown name interns the instance")
    void unknownNameIsInterned() {
        Capability a = Capability.of("custom_cap", true);
        Capability b = Capability.of("custom_cap", true);
        assertSame(a, b, "repeated of() calls for the same name/supported must return the same instance");
    }

    @Test
    @DisplayName("Partial-update singletons appear in registeredValues()")
    void registeredValuesContainsWellKnownSingletons() {
        var registered = Capability.registeredValues();
        assertTrue(registered.contains(Capability.TRANSACTIONS_CAP));
        assertTrue(registered.contains(Capability.TRANSACTIONS_UNSUPPORTED));
        assertTrue(registered.contains(Capability.CROSS_PARTITION_QUERY_CAP));
        assertTrue(registered.contains(Capability.CROSS_PARTITION_QUERY_UNSUPPORTED));
        assertTrue(registered.contains(Capability.PARTIAL_UPDATE_CAP));
        assertTrue(registered.contains(Capability.PARTIAL_UPDATE_UNSUPPORTED));
        // 15 pre-built names x supported/unsupported.
        assertTrue(registered.size() >= 30,
                "expected at least 30 entries (15 x 2), got " + registered.size());
    }

    @Test
    @DisplayName("toString includes name, supported state, and notes when present")
    void toStringFormat() {
        String s = Capability.TRANSACTIONS_CAP.withNotes("detail").toString();
        assertTrue(s.contains("transactions"));
        assertTrue(s.contains("supported"));
        assertTrue(s.contains("detail"));
    }
}
