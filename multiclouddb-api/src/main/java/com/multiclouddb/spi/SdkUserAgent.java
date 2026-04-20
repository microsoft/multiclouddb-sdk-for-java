// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.spi;

import com.multiclouddb.api.MulticloudDbClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Builds the SDK user agent string sent with every request.
 * <p>
 * The base format is {@code multiclouddb-sdk-java/<version>}. When the
 * customer supplies a {@linkplain MulticloudDbClientConfig#userAgentSuffix()
 * user agent suffix}, it is appended with a space separator:
 * {@code multiclouddb-sdk-java/<version> <suffix>}.
 * <p>
 * The SDK version is read at class-load time from
 * {@code multiclouddb-version.properties} on the classpath (populated via
 * Maven resource filtering). If the resource is unavailable, {@code "unknown"}
 * is used as a fallback.
 */
public final class SdkUserAgent {

    private static final Logger LOG = LoggerFactory.getLogger(SdkUserAgent.class);

    private static final String VERSION_RESOURCE = "multiclouddb-version.properties";
    private static final String SDK_NAME = "multiclouddb-sdk-java";
    private static final String UNKNOWN_VERSION = "unknown";

    private static final String SDK_VERSION;

    static {
        String version = UNKNOWN_VERSION;
        try (InputStream is = SdkUserAgent.class.getClassLoader().getResourceAsStream(VERSION_RESOURCE)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank() && !v.equals("${project.version}")) {
                    version = v.trim();
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read SDK version from {}", VERSION_RESOURCE, e);
        }
        SDK_VERSION = version;
    }

    private SdkUserAgent() {
        // utility class
    }

    /**
     * Returns the SDK user agent string for the given configuration.
     *
     * @param config the client configuration (may contain a customer suffix)
     * @return the full user agent string, never {@code null}
     */
    public static String userAgent(MulticloudDbClientConfig config) {
        String base = userAgentBase();
        if (config != null && config.userAgentSuffix() != null && !config.userAgentSuffix().isEmpty()) {
            return base + " " + config.userAgentSuffix();
        }
        return base;
    }

    /**
     * Returns the SDK user agent base string without any customer suffix.
     *
     * @return the base user agent string, e.g. {@code "multiclouddb-sdk-java/0.1.0-beta.2"}
     */
    public static String userAgentBase() {
        return SDK_NAME + "/" + SDK_VERSION;
    }

    /**
     * Returns the SDK version loaded from the properties resource.
     *
     * <p>Package-private: intended for internal use and same-package unit tests only.
     * Not part of the public SPI contract — consumers should use {@link #userAgent(MulticloudDbClientConfig)}
     * or {@link #userAgentBase()} instead.
     *
     * @return the version string, or {@code "unknown"} if unavailable
     */
    static String sdkVersion() {
        return SDK_VERSION;
    }
}
