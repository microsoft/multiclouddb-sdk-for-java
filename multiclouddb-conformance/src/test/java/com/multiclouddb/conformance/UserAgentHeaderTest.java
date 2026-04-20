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
 */
@Tag("user-agent")
class UserAgentHeaderTest {

    private static final String CUSTOMER_SUFFIX = "conformance-test-app";

    /** Cosmos emulator master key — a valid Base64 string needed for HMAC auth header generation. */
    private static final String COSMOS_DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

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
    // Cosmos DB
    // ------------------------------------------------------------------
    //
    // The Cosmos SDK forces HTTPS even when an http:// endpoint is provided,
    // so a plain JDK HttpServer cannot intercept its requests. Instead, we
    // verify the user agent from the SDK's diagnostics embedded in the error
    // message when initialization fails against a non-Cosmos endpoint.
    //

    @Test
    void cosmosClientIncludesSdkUserAgentInDiagnostics() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", "https://localhost:" + port)
                .connection("key", COSMOS_DUMMY_KEY)
                .connection("connectionMode", "gateway")
                .userAgentSuffix(CUSTOMER_SUFFIX)
                .build();

        String errorMessage = "";
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {
            client.read(
                    new ResourceAddress("testdb", "testcontainer"),
                    MulticloudDbKey.of("pk1", "pk1"));
        } catch (Exception e) {
            // Cosmos SDK includes userAgent in diagnostics within the error message
            errorMessage = extractFullMessage(e);
        }

        assertTrue(errorMessage.contains(SdkUserAgent.userAgentBase()),
                "Cosmos diagnostics should contain SDK base '" + SdkUserAgent.userAgentBase()
                        + "' in error message, but message was: "
                        + errorMessage.substring(0, Math.min(300, errorMessage.length())) + "...");
        assertTrue(errorMessage.contains(CUSTOMER_SUFFIX),
                "Cosmos diagnostics should contain customer suffix '" + CUSTOMER_SUFFIX
                        + "' in error message");
    }

    /** Walks the exception cause chain and concatenates all messages. */
    private static String extractFullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" ");
            }
            t = t.getCause();
        }
        return sb.toString();
    }

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
