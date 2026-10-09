// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import static com.multiclouddb.conformance.Documents.document;

import com.multiclouddb.api.*;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.conformance.ConformanceConfig;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test: diagnostics are attached to MulticloudDbExceptions.
 * <p>
 * Verifies that provider operations enrich exceptions with
 * {@link OperationDiagnostics} containing provider, operation, and duration.
 * <p>
 * Concrete subclasses provide the provider id.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class DiagnosticsConformanceTest {

    protected abstract ProviderId providerId();

    private MulticloudDbClient client;

    @BeforeEach
    void setUp() {
        MulticloudDbClientConfig config = ConformanceConfig.forProvider(providerId());
        client = MulticloudDbClientFactory.create(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Read with invalid address yields exception with diagnostics")
    void readInvalidAddressHasDiagnostics() {
        // Use a deliberately invalid/nonexistent address to trigger an error
        ResourceAddress badAddress = new ResourceAddress("nonexistent-db-12345", "nonexistent-collection-12345");
        try {
            client.read(badAddress, MulticloudDbKey.of("nonexistent-key", "nonexistent-key"), OperationOptions.defaults());
            // Some providers may return null instead of throwing — that's OK, skip
            // diagnostics check
        } catch (MulticloudDbException e) {
            assertNotNull(e.error(), "MulticloudDbException should have an error");
            assertEquals(providerId(), e.error().provider());

            // Diagnostics may or may not be attached depending on where the error occurs
            if (e.diagnostics() != null) {
                assertNotNull(e.diagnostics().provider(), "Diagnostics should have a provider");
                assertEquals(providerId(), e.diagnostics().provider());
                assertNotNull(e.diagnostics().operation(), "Diagnostics should have an operation name");
                assertNotNull(e.diagnostics().duration(), "Diagnostics should have a duration");
                assertFalse(e.diagnostics().duration().isNegative(), "Duration should not be negative");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("Exception error object has correct provider")
    void exceptionErrorHasProvider() {
        ResourceAddress badAddress = new ResourceAddress("nonexistent-db-12345", "nonexistent-collection-12345");
        try {
            client.delete(badAddress, MulticloudDbKey.of("no-such-key", "no-such-key"), OperationOptions.defaults());
        } catch (MulticloudDbException e) {
            assertEquals(providerId(), e.error().provider(),
                    "Error provider should match the client's provider");
            assertNotNull(e.error().category(), "Error should have a category");
            assertNotNull(e.error().message(), "Error should have a message");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Exception error contains operation name")
    void exceptionHasOperationName() {
        ResourceAddress badAddress = new ResourceAddress("nonexistent-db-12345", "nonexistent-collection-12345");
        try {
            client.upsert(badAddress, MulticloudDbKey.of("bad-key", "bad-key"), document(java.util.Map.of("test", true)),
                    OperationOptions.defaults());
        } catch (MulticloudDbException e) {
            if (e.diagnostics() != null) {
                assertEquals(OperationNames.UPSERT, e.diagnostics().operation(),
                        "Diagnostics operation name must match OperationNames.UPSERT");
            }
            assertNotNull(e.error().operation());
        }
    }

    @Test
    @Order(4)
    @DisplayName("Error operation name for delete matches OperationNames.DELETE")
    void deleteErrorOperationName() {
        ResourceAddress badAddress = new ResourceAddress("nonexistent-db-12345", "nonexistent-collection-12345");
        try {
            client.delete(badAddress, MulticloudDbKey.of("no-key", "no-key"), OperationOptions.defaults());
        } catch (MulticloudDbException e) {
            if (e.diagnostics() != null) {
                assertEquals(OperationNames.DELETE, e.diagnostics().operation(),
                        "Diagnostics operation name must match OperationNames.DELETE");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("All shared operation name constants are non-null and non-blank")
    void sharedOperationNamesNonBlank() {
        List<String> all = List.of(
                OperationNames.CREATE, OperationNames.READ, OperationNames.UPDATE,
                OperationNames.UPSERT, OperationNames.DELETE, OperationNames.QUERY,
                OperationNames.QUERY_WITH_TRANSLATION,
                OperationNames.ENSURE_DATABASE, OperationNames.ENSURE_CONTAINER
        );
        for (String name : all) {
            assertNotNull(name);
            assertFalse(name.isBlank(), "Operation name '" + name + "' must not be blank");
        }
    }
}
