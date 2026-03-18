// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.*;
import com.hyperscaledb.conformance.ConformanceConfig;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance test: native client access (escape hatch) returns the correct
 * provider-specific client type.
 * <p>
 * Concrete subclasses specify the expected native client class.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class NativeClientAccessConformanceTest {

    /** Provider id for this test run. */
    protected abstract ProviderId providerId();

    /** The expected native client class type. */
    protected abstract Class<?> expectedNativeClientType();

    private HyperscaleDbClient client;

    @BeforeEach
    void setUp() {
        HyperscaleDbClientConfig config = ConformanceConfig.forProvider(providerId());
        client = HyperscaleDbClientFactory.create(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("nativeClient returns correct type")
    void nativeClientReturnsCorrectType() {
        Object nativeClient = client.nativeClient(expectedNativeClientType());
        assertNotNull(nativeClient, "nativeClient() should return non-null for the correct type");
        assertInstanceOf(expectedNativeClientType(), nativeClient,
                "nativeClient() should return an instance of " + expectedNativeClientType().getSimpleName());
    }

    @Test
    @Order(2)
    @DisplayName("nativeClient with wrong type returns null")
    void nativeClientWithWrongTypeReturnsNull() {
        // Request a type that no provider would return
        Object nativeClient = client.nativeClient(StringBuilder.class);
        assertNull(nativeClient,
                "nativeClient() should return null for an unsupported type");
    }

    @Test
    @Order(3)
    @DisplayName("nativeClient type is usable")
    void nativeClientIsUsable() {
        Object nativeClient = client.nativeClient(expectedNativeClientType());
        assertNotNull(nativeClient);
        // Just verify it's a live instance (not closed / not a stub)
        assertNotNull(nativeClient.toString(), "Native client should be a live, usable instance");
    }
}
