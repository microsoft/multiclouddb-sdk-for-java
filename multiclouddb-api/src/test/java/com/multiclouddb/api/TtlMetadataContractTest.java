// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TtlMetadataContractTest {
    @Test
    void defaultsAndTimeoutShortcutPreserveOptInBehavior() {
        assertSame(OperationOptions.defaults(), OperationOptions.defaults());
        for (OperationOptions options : new OperationOptions[] {
                OperationOptions.defaults(), OperationOptions.builder().build(),
                OperationOptions.withTimeout(Duration.ofSeconds(3))}) {
            assertNull(options.ttlSeconds());
            assertFalse(options.includeMetadata());
        }
        assertNull(OperationOptions.defaults().timeout());
        assertEquals(Duration.ofSeconds(3),
                OperationOptions.withTimeout(Duration.ofSeconds(3)).timeout());
    }

    @Test
    void builderCombinesTimeoutTtlAndMetadata() {
        OperationOptions options = OperationOptions.builder()
                .timeout(Duration.ofSeconds(2)).ttlSeconds(3600).includeMetadata(true).build();
        assertEquals(Duration.ofSeconds(2), options.timeout());
        assertEquals(3600, options.ttlSeconds());
        assertTrue(options.includeMetadata());
        assertEquals(1, OperationOptions.builder().ttlSeconds(1).build().ttlSeconds());
        for (int invalid : new int[] {0, -1}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OperationOptions.builder().ttlSeconds(invalid));
            assertEquals("ttlSeconds must be >= 1", error.getMessage());
        }
    }

    @Test
    void metadataFieldsAndBothResultConstructorsRemainAvailable() {
        Instant modified = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiry = modified.plusSeconds(3600);
        DocumentMetadata metadata = DocumentMetadata.builder()
                .lastModified(modified).ttlExpiry(expiry).version("etag").build();
        var doc = JsonNodeFactory.instance.objectNode().put("value", 1);
        DocumentResult result = new DocumentResult(doc, metadata);
        assertSame(doc, result.document());
        assertSame(metadata, result.metadata());
        assertEquals(modified, result.metadata().lastModified());
        assertEquals(expiry, result.metadata().ttlExpiry());
        assertEquals("etag", result.metadata().version());
        assertNull(new DocumentResult(doc).metadata());
        assertEquals(new DocumentResult(doc), new DocumentResult(doc, null));
        assertEquals(result, new DocumentResult(doc, metadata));
        assertEquals(result.hashCode(), new DocumentResult(doc, metadata).hashCode());
        assertNotEquals(result, new DocumentResult(doc));
        assertThrows(NullPointerException.class, () -> new DocumentResult(null, metadata));
        DocumentMetadata empty = DocumentMetadata.builder().build();
        assertNull(empty.lastModified());
        assertNull(empty.ttlExpiry());
        assertNull(empty.version());
    }
}
