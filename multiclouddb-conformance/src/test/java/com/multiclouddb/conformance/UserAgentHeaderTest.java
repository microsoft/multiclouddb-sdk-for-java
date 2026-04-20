// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.spi.SdkUserAgent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance tests verifying that each provider sends the correct
 * {@code User-Agent} HTTP header containing the SDK identifier and any
 * customer-provided suffix.
 * <p>
 * A lightweight JDK {@link HttpServer} captures the first inbound request's
 * {@code User-Agent} header. Each provider client is configured to send
 * requests to this mock server, so the test runs without cloud credentials
 * or emulators.
 * <p>
 * <strong>Note on Spanner:</strong> The Google Cloud Spanner SDK communicates
 * via gRPC (HTTP/2), which the JDK {@code HttpServer} does not support.
 * Verifying the Spanner {@code user-agent} gRPC metadata requires the Spanner
 * emulator or a gRPC-level interceptor and is not covered here.
 * <p>
 * <strong>Note on Cosmos DB:</strong> The Cosmos SDK forces HTTPS even when
 * an {@code http://} endpoint is provided, so a plain JDK {@code HttpServer}
 * cannot intercept its requests. Cosmos user-agent configuration is verified
 * separately at the provider unit-test level — see
 * {@code com.multiclouddb.provider.cosmos.CosmosUserAgentTest} — which
 * intercepts {@code CosmosClientBuilder} construction and asserts the
 * {@code userAgentSuffix} value directly without relying on SDK diagnostic
 * output formatting.
 */
@Tag("user-agent")
class UserAgentHeaderTest {

    private static final String CUSTOMER_SUFFIX = "conformance-test-app";

    private HttpServer mockServer;
    private int port;
    private final AtomicReference<String> capturedUserAgent = new AtomicReference<>();

    @BeforeEach
    void startMockServer() throws Exception {
        capturedUserAgent.set(null);
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/", exchange -> {
            String ua = exchange.getRequestHeaders().getFirst("User-Agent");
            if (ua != null) {
                capturedUserAgent.compareAndSet(null, ua);
            }
            // Return 400 (non-retryable) so the SDK fails immediately without retries
            byte[] body = "{\"message\":\"mock\"}".getBytes();
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        mockServer.start();
        port = mockServer.getAddress().getPort();
    }

    @AfterEach
    void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // DynamoDB
    // ------------------------------------------------------------------

    @Test
    @Tag("dynamo")
    void dynamoRequestContainsSdkUserAgent() throws Exception {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", "http://localhost:" + port)
                .connection("region", "us-east-1")
                .auth("accessKeyId", "fakeKey")
                .auth("secretAccessKey", "fakeSecret")
                .userAgentSuffix(CUSTOMER_SUFFIX)
                .build();

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {
            try {
                client.read(
                        new ResourceAddress("testdb", "testtable"),
                        MulticloudDbKey.of("pk1", "pk1"));
            } catch (Exception ignored) {
                // Expected — mock server returns a non-DynamoDB response
            }
        }

        assertUserAgentHeader("DynamoDB");
    }

    @Test
    @Tag("dynamo")
    void dynamoRequestContainsSdkUserAgentWithoutCustomSuffix() throws Exception {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", "http://localhost:" + port)
                .connection("region", "us-east-1")
                .auth("accessKeyId", "fakeKey")
                .auth("secretAccessKey", "fakeSecret")
                .build();

        try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {
            try {
                client.read(
                        new ResourceAddress("testdb", "testtable"),
                        MulticloudDbKey.of("pk1", "pk1"));
            } catch (Exception ignored) {
                // Expected
            }
        }

        String userAgent = capturedUserAgent.get();
        assertNotNull(userAgent, "DynamoDB: User-Agent header should be present");
        assertTrue(userAgent.contains(SdkUserAgent.userAgentBase()),
                "DynamoDB: User-Agent should contain SDK base '" + SdkUserAgent.userAgentBase()
                        + "', but was: " + userAgent);
    }

    // ------------------------------------------------------------------
    // Cosmos DB — see CosmosUserAgentTest in multiclouddb-provider-cosmos
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private void assertUserAgentHeader(String providerLabel) {
        String userAgent = capturedUserAgent.get();
        assertNotNull(userAgent,
                providerLabel + ": User-Agent header should be present in the HTTP request");
        assertTrue(userAgent.contains(SdkUserAgent.userAgentBase()),
                providerLabel + ": User-Agent should contain SDK base '"
                        + SdkUserAgent.userAgentBase() + "', but was: " + userAgent);
        assertTrue(userAgent.contains(CUSTOMER_SUFFIX),
                providerLabel + ": User-Agent should contain customer suffix '"
                        + CUSTOMER_SUFFIX + "', but was: " + userAgent);
    }
}
