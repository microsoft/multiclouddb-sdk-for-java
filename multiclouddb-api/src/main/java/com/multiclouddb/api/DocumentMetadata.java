// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.time.Instant;

/**
 * Immutable write-metadata returned alongside a document when
 * {@link OperationOptions#includeMetadata()} is {@code true}.
 * <p>
 * Fields that the provider cannot supply are returned as {@code null}.
 *
 * <ul>
 *   <li>{@code lastModified} — commit/update timestamp set by the provider (FR-058).</li>
 *   <li>{@code ttlExpiry} — the absolute time at which the document will be deleted,
 *       or {@code null} if no TTL is set (FR-058).</li>
 *   <li>{@code version} — provider-native ETag/version string, or {@code null}
 *       if not available (FR-058).</li>
 * </ul>
 */
public final class DocumentMetadata {

    private final Instant lastModified;
    private final Instant ttlExpiry;
    private final String version;

    private DocumentMetadata(Instant lastModified, Instant ttlExpiry, String version) {
        this.lastModified = lastModified;
        this.ttlExpiry = ttlExpiry;
        this.version = version;
    }

    /** The time at which the document was last written, or {@code null} if unavailable. */
    public Instant lastModified() {
        return lastModified;
    }

    /**
     * The absolute expiry timestamp for this document, or {@code null} if no TTL is set
     * or the provider does not expose it on read.
     */
    public Instant ttlExpiry() {
        return ttlExpiry;
    }

    /** Provider-native version/ETag string, or {@code null} if unavailable. */
    public String version() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Instant lastModified;
        private Instant ttlExpiry;
        private String version;

        private Builder() {
        }

        public Builder lastModified(Instant lastModified) {
            this.lastModified = lastModified;
            return this;
        }

        public Builder ttlExpiry(Instant ttlExpiry) {
            this.ttlExpiry = ttlExpiry;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public DocumentMetadata build() {
            return new DocumentMetadata(lastModified, ttlExpiry, version);
        }
    }

    @Override
    public String toString() {
        return "DocumentMetadata{lastModified=" + lastModified
                + ", ttlExpiry=" + ttlExpiry
                + ", version=" + version + "}";
    }
}
