// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.microsoft.multiclouddb.e2e.ConfigLoader;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Measures the TCP round-trip time to each provider's service endpoint.
 * <p>
 * Cross-cloud latency comparisons run from a single client are dominated by
 * network distance: the client cannot be colocated with both clouds at once.
 * Recording the endpoint RTT lets the report subtract it and present a
 * placement-independent <em>service time</em> alongside raw wall-clock latency,
 * so a provider is not penalised purely for being further from the test host.
 */
final class NetworkBaseline {

    private static final int PROBES = 7;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private NetworkBaseline() {
    }

    /**
     * Returns the median TCP handshake time in milliseconds to the provider's
     * data-plane endpoint, or {@code null} when the endpoint cannot be resolved
     * or probed. A failed probe is never fatal — the run continues and the
     * report simply omits service-time normalisation for that provider.
     */
    static Double probeRttMs(String providerId, ConfigLoader.AppConfig cfg) {
        Endpoint endpoint = resolveEndpoint(providerId, cfg);
        if (endpoint == null) {
            return null;
        }
        List<Double> samples = new ArrayList<>(PROBES);
        for (int i = 0; i < PROBES; i++) {
            Double sample = connectOnce(endpoint);
            if (sample != null) {
                samples.add(sample);
            }
        }
        if (samples.isEmpty()) {
            System.out.printf(Locale.ROOT,
                    "!! RTT probe failed for %s (%s:%d); reporting raw latency only%n",
                    providerId, endpoint.host(), endpoint.port());
            return null;
        }
        Collections.sort(samples);
        double median = samples.get(samples.size() / 2);
        System.out.printf(Locale.ROOT,
                "-- %s endpoint RTT: %.2f ms (median of %d TCP handshakes to %s:%d)%n",
                providerId, median, samples.size(), endpoint.host(), endpoint.port());
        return median;
    }

    private record Endpoint(String host, int port) {
    }

    private static Endpoint resolveEndpoint(String providerId, ConfigLoader.AppConfig cfg) {
        if ("cosmos".equals(providerId)) {
            return fromUri(cfg.get("multiclouddb.connection.endpoint", ""));
        }
        if ("dynamo".equals(providerId)) {
            String custom = cfg.get("multiclouddb.connection.endpoint", "");
            if (custom != null && !custom.isBlank()) {
                return fromUri(custom);
            }
            String region = cfg.get("multiclouddb.connection.region", "");
            if (region == null || region.isBlank()) {
                return null;
            }
            return new Endpoint("dynamodb." + region.trim() + ".amazonaws.com", 443);
        }
        if ("spanner".equals(providerId)) {
            return new Endpoint("spanner.googleapis.com", 443);
        }
        return null;
    }

    private static Endpoint fromUri(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
            return new Endpoint(host, port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Double connectOnce(Endpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            long start = System.nanoTime();
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MS);
            long elapsed = System.nanoTime() - start;
            return elapsed / 1_000_000.0;
        } catch (Exception e) {
            return null;
        }
    }
}
