// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us6;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hyperscaledb.api.*;
import com.hyperscaledb.conformance.ConformanceHarness;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance tests for User Story 6 — Document TTL and Write Metadata (FR-054–FR-060).
 * <p>
 * Verifies that:
 * <ul>
 *   <li>FR-054: {@link OperationOptions#ttlSeconds()} is accepted on {@code create()} without error.</li>
 *   <li>FR-055: {@link OperationOptions#ttlSeconds()} is accepted on {@code upsert()} without error.</li>
 *   <li>FR-056: {@link OperationOptions#ttlSeconds()} is accepted on {@code update()} without error
 *       (consistent TTL enforcement across all write operations).</li>
 *   <li>FR-057: {@link DocumentResult#metadata()} is populated when
 *       {@link OperationOptions#includeMetadata()} is {@code true}.</li>
 *   <li>FR-058: {@link DocumentMetadata#version()} is non-null for providers that support
 *       {@link Capability#WRITE_TIMESTAMP} or carry ETag/version metadata.</li>
 *   <li>FR-059: {@link DocumentMetadata#lastModified()} is non-null for providers that populate it.</li>
 *   <li>FR-060: Metadata is {@code null} when {@link OperationOptions#includeMetadata()} is
 *       {@code false} (default).</li>
 * </ul>
 * <p>
 * These tests run against a live provider via the conformance harness.
 * Subclass and implement {@link #createClient()}, {@link #getAddress()}, and
 * {@link #supportsLastModified()} to target a specific provider.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class TtlAndMetadataConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Override to return {@code true} if the provider populates {@code lastModified}. */
    protected abstract boolean supportsLastModified();

    protected abstract HyperscaleDbClient createClient();

    protected abstract ResourceAddress getAddress();

    private HyperscaleDbClient client;

    @BeforeEach
    void setUp() {
        client = createClient();
    }

    // -------------------------------------------- FR-054: TTL on create()

    @Test
    @Order(1)
    @DisplayName("FR-054: create() with ttlSeconds is accepted without error")
    void createWithTtlIsAccepted() {
        Key key = ConformanceHarness.uniqueKey("ttl-create");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("name", "ttl-create-test");

        OperationOptions opts = OperationOptions.builder().ttlSeconds(3600).build();
        assertDoesNotThrow(
                () -> client.create(getAddress(), key, doc, opts),
                "create() with ttlSeconds=3600 must not throw");
    }

    // -------------------------------------------- FR-055: TTL on upsert()

    @Test
    @Order(2)
    @DisplayName("FR-055: upsert() with ttlSeconds is accepted without error")
    void upsertWithTtlIsAccepted() {
        Key key = ConformanceHarness.uniqueKey("ttl-upsert");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("name", "ttl-upsert-test");

        OperationOptions opts = OperationOptions.builder().ttlSeconds(3600).build();
        assertDoesNotThrow(
                () -> client.upsert(getAddress(), key, doc, opts),
                "upsert() with ttlSeconds=3600 must not throw");
    }

    // -------------------------------------------- FR-056: TTL on update()

    @Test
    @Order(3)
    @DisplayName("FR-056: update() with ttlSeconds is accepted without error")
    void updateWithTtlIsAccepted() {
        Key key = ConformanceHarness.uniqueKey("ttl-update");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("name", "ttl-update-seed");

        // Create first so update() has something to replace
        client.create(getAddress(), key, doc, OperationOptions.defaults());

        ObjectNode updated = MAPPER.createObjectNode();
        updated.put("name", "ttl-update-test");
        OperationOptions opts = OperationOptions.builder().ttlSeconds(7200).build();
        assertDoesNotThrow(
                () -> client.update(getAddress(), key, updated, opts),
                "update() with ttlSeconds=7200 must not throw");
    }

    // -------------------------------------------- FR-057: metadata is populated

    @Test
    @Order(4)
    @DisplayName("FR-057: read() with includeMetadata=true returns non-null metadata")
    void readWithMetadataReturnsMetadata() {
        Key key = ConformanceHarness.uniqueKey("meta-test");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("value", 42);
        client.upsert(getAddress(), key, doc, OperationOptions.defaults());

        OperationOptions readOpts = OperationOptions.builder().includeMetadata(true).build();
        DocumentResult result = client.read(getAddress(), key, readOpts);

        assertNotNull(result, "read() must return a result");
        assertNotNull(result.metadata(),
                "metadata() must be non-null when includeMetadata=true");
    }

    // -------------------------------------------- FR-058: version is populated

    @Test
    @Order(5)
    @DisplayName("FR-058: metadata.version() is non-null for providers that support it")
    void metadataVersionIsPopulated() {
        Key key = ConformanceHarness.uniqueKey("version-test");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("value", 99);
        client.upsert(getAddress(), key, doc, OperationOptions.defaults());

        OperationOptions readOpts = OperationOptions.builder().includeMetadata(true).build();
        DocumentResult result = client.read(getAddress(), key, readOpts);

        assertNotNull(result, "read() must return a result");
        assertNotNull(result.metadata(), "metadata() must be non-null");
        // version (ETag/session token) must be present — providers that declare
        // WRITE_TIMESTAMP=true should always have a version string.
        assertNotNull(result.metadata().version(),
                "metadata.version() must be non-null for this provider");
    }

    // -------------------------------------------- FR-059: lastModified

    @Test
    @Order(6)
    @DisplayName("FR-059: metadata.lastModified() is non-null when provider supports it")
    void metadataLastModifiedIsPopulated() {
        assumeTrue(supportsLastModified(),
                "Skipped — provider does not populate lastModified");

        Key key = ConformanceHarness.uniqueKey("lastmod-test");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("value", 7);
        client.upsert(getAddress(), key, doc, OperationOptions.defaults());

        OperationOptions readOpts = OperationOptions.builder().includeMetadata(true).build();
        DocumentResult result = client.read(getAddress(), key, readOpts);

        assertNotNull(result, "read() must return a result");
        assertNotNull(result.metadata(), "metadata() must be non-null");
        assertNotNull(result.metadata().lastModified(),
                "metadata.lastModified() must be non-null for providers that support it");
    }

    // -------------------------------------------- FR-060: metadata absent by default

    @Test
    @Order(7)
    @DisplayName("FR-060: read() without includeMetadata returns null metadata")
    void readWithoutMetadataFlagReturnsNullMetadata() {
        Key key = ConformanceHarness.uniqueKey("no-meta-test");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("value", 1);
        client.upsert(getAddress(), key, doc, OperationOptions.defaults());

        // Default options — includeMetadata is false
        DocumentResult result = client.read(getAddress(), key, OperationOptions.defaults());

        assertNotNull(result, "read() must return a result");
        assertNull(result.metadata(),
                "metadata() must be null when includeMetadata=false (default)");
    }
}
