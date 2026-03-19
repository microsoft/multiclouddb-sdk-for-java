// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderIdTest {

    // ── Well-known constants ──────────────────────────────────────────────────

    @Test
    @DisplayName("Well-known constant ids are lowercase")
    void wellKnownIds() {
        assertEquals("cosmos",  ProviderId.COSMOS.id());
        assertEquals("dynamo",  ProviderId.DYNAMO.id());
        assertEquals("spanner", ProviderId.SPANNER.id());
    }

    @Test
    @DisplayName("Well-known constants have human-readable display names")
    void wellKnownDisplayNames() {
        assertEquals("Azure Cosmos DB",       ProviderId.COSMOS.displayName());
        assertEquals("AWS DynamoDB",          ProviderId.DYNAMO.displayName());
        assertEquals("Google Cloud Spanner",  ProviderId.SPANNER.displayName());
    }

    // ── fromId — known providers ──────────────────────────────────────────────

    @Test
    @DisplayName("fromId is case-insensitive and returns the interned constant")
    void fromIdCaseInsensitive() {
        assertSame(ProviderId.COSMOS, ProviderId.fromId("cosmos"));
        assertSame(ProviderId.COSMOS, ProviderId.fromId("COSMOS"));
        assertSame(ProviderId.COSMOS, ProviderId.fromId("Cosmos"));
    }

    @Test
    @DisplayName("fromId returns DYNAMO constant")
    void fromIdDynamo() {
        assertSame(ProviderId.DYNAMO, ProviderId.fromId("dynamo"));
    }

    @Test
    @DisplayName("fromId returns SPANNER constant")
    void fromIdSpanner() {
        assertSame(ProviderId.SPANNER, ProviderId.fromId("spanner"));
    }

    // ── fromId — unknown / third-party providers ──────────────────────────────

    @Test
    @DisplayName("fromId with an unknown id creates and interns a new ProviderId")
    void fromIdUnknownCreatesNew() {
        ProviderId custom = ProviderId.fromId("my-custom-db");
        assertNotNull(custom);
        assertEquals("my-custom-db", custom.id());
    }

    @Test
    @DisplayName("fromId is idempotent — same instance returned on repeated calls")
    void fromIdIdempotent() {
        ProviderId a = ProviderId.fromId("third-party-db");
        ProviderId b = ProviderId.fromId("third-party-db");
        assertSame(a, b, "fromId must return the same interned instance");
    }

    @Test
    @DisplayName("fromId with null throws IllegalArgumentException")
    void fromIdNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProviderId.fromId(null));
    }

    @Test
    @DisplayName("fromId with blank throws IllegalArgumentException")
    void fromIdBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProviderId.fromId("  "));
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("register returns an interned instance with the given display name")
    void registerNewProvider() {
        ProviderId p = ProviderId.register("acme-db", "ACME Cloud DB");
        assertEquals("acme-db", p.id());
        assertEquals("ACME Cloud DB", p.displayName());
    }

    @Test
    @DisplayName("register is idempotent — first display name wins")
    void registerIdempotentFirstDisplayNameWins() {
        ProviderId first  = ProviderId.register("my-new-db", "First Name");
        ProviderId second = ProviderId.register("my-new-db", "Second Name");
        assertSame(first, second);
        assertEquals("First Name", second.displayName(),
                "First registration's display name must win");
    }

    @Test
    @DisplayName("register with null id throws IllegalArgumentException")
    void registerNullIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProviderId.register(null, "name"));
    }

    // ── values ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("values() contains all three well-known constants")
    void valuesContainsWellKnown() {
        var all = ProviderId.values();
        assertTrue(all.contains(ProviderId.COSMOS));
        assertTrue(all.contains(ProviderId.DYNAMO));
        assertTrue(all.contains(ProviderId.SPANNER));
    }

    @Test
    @DisplayName("values() is unmodifiable")
    void valuesIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> ProviderId.values().clear());
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Test
    @DisplayName("equals is based on id string")
    void equalsById() {
        ProviderId a = ProviderId.fromId("equals-test-db");
        ProviderId b = ProviderId.fromId("equals-test-db");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("toString returns the id string")
    void toStringReturnsId() {
        assertEquals("cosmos",  ProviderId.COSMOS.toString());
        assertEquals("dynamo",  ProviderId.DYNAMO.toString());
        assertEquals("spanner", ProviderId.SPANNER.toString());
    }

    @Test
    @DisplayName("COSMOS != DYNAMO != SPANNER")
    void wellKnownConstantsAreDistinct() {
        assertNotEquals(ProviderId.COSMOS,  ProviderId.DYNAMO);
        assertNotEquals(ProviderId.COSMOS,  ProviderId.SPANNER);
        assertNotEquals(ProviderId.DYNAMO,  ProviderId.SPANNER);
    }
}
