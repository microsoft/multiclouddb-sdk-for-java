// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Portable diagnostics for an operation.
 * <p>
 * Results completed by {@link MulticloudDbClient} have wrapper start/end times,
 * monotonic duration and a logical outcome. Provider metadata and objects
 * constructed outside that boundary may have unavailable fields. Point/query
 * responses expose Cosmos RU or DynamoDB capacity when supplied; Cosmos feed
 * pages also expose observed RU. DynamoDB Streams has no capacity value. Spanner
 * has no per-request consumption value. These units are not monetary cost.
 * Status codes require their domain; gRPC codes are not HTTP codes.
 * Native identifiers/tokens can be sensitive and should not be logged blindly.
 */
public final class OperationDiagnostics {

    private final ProviderId provider;
    private final String operation;
    private final Duration duration;
    private final Instant startTime;
    private final Instant endTime;
    private final OperationOutcome outcome;
    private final StatusCodeDomain statusCodeDomain;
    private final ResourceConsumption consumption;
    private final String requestId;

    /** Native status code; null if not available. */
    private final Integer statusCode;

    /**
     * Provider-specific cost of the operation (Cosmos RU, DynamoDB consumed
     * capacity units); 0.0 if not available or not opted-in.
     */
    private final double requestCharge;

    /**
     * ETag / optimistic-concurrency token returned by the provider; null if not
     * available. A Spanner commit timestamp is not an ETag.
     */
    private final String etag;

    /**
     * Cosmos session-consistency token; null for all other providers.
     */
    private final String sessionToken;

    /**
     * Number of items/events in a query/change-feed page; 0 for point operations.
     */
    private final int itemCount;

    private OperationDiagnostics(Builder builder) {
        this.provider = builder.provider;
        this.operation = builder.operation;
        this.duration = builder.duration;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.outcome = builder.outcome;
        this.statusCodeDomain = builder.statusCode == null ? null : builder.statusCodeDomain;
        this.consumption = builder.consumption;
        this.requestId = builder.requestId;
        this.statusCode = builder.statusCode;
        this.requestCharge = builder.consumption != null
                ? builder.consumption.amount() : builder.requestCharge;
        this.etag = builder.etag;
        this.sessionToken = builder.sessionToken;
        this.itemCount = builder.itemCount;
    }

    /**
     * Convenience constructor for simple diagnostics (failure paths and tests).
     * Use {@link #builder(ProviderId, String, Duration)} when richer fields are available.
     */
    public OperationDiagnostics(ProviderId provider, String operation, Duration duration, String requestId) {
        this.provider = provider;
        this.operation = operation;
        this.duration = duration;
        this.startTime = null;
        this.endTime = null;
        this.outcome = null;
        this.statusCodeDomain = null;
        this.consumption = null;
        this.requestId = requestId;
        this.statusCode = null;
        this.requestCharge = 0.0;
        this.etag = null;
        this.sessionToken = null;
        this.itemCount = 0;
    }

    public static Builder builder(ProviderId provider, String operation, Duration duration) {
        return builder(provider, operation).duration(duration);
    }

    /**
     * Builds provider metadata before the public client adds wrapper timing.
     * Unavailable timing is null, never a fabricated zero duration.
     */
    public static Builder builder(ProviderId provider, String operation) {
        return new Builder(Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(operation, "operation"), null);
    }

    /** Copy all observations, optionally replacing wrapper identity and timing. */
    public Builder toBuilder() {
        return new Builder(provider, operation, duration)
                .startTime(startTime).endTime(endTime).outcome(outcome)
                .requestId(requestId).statusCode(statusCode).statusCodeDomain(statusCodeDomain)
                .requestCharge(requestCharge).consumption(consumption)
                .etag(etag).sessionToken(sessionToken).itemCount(itemCount);
    }

    public ProviderId provider() { return provider; }
    public String operation()   { return operation; }
    public Duration duration()  { return duration; }
    /** Wall-clock invocation start, or null before public-wrapper completion. */
    public Instant startTime() { return startTime; }
    /** Wall-clock completion; may precede startTime after a clock adjustment. */
    public Instant endTime() { return endTime; }
    public OperationOutcome outcome() { return outcome; }
    public StatusCodeDomain statusCodeDomain() { return statusCodeDomain; }

    /** Observed resource usage with units/coverage, or null when unavailable. */
    public ResourceConsumption consumption() { return consumption; }

    /** Provider-supplied correlation/request identifier, or null if not available. */
    public String requestId()   { return requestId; }

    /** Native status code in {@link #statusCodeDomain()}, or null if unavailable. */
    public Integer statusCode() { return statusCode; }

    /**
     * Cost of the operation in provider-specific units (Cosmos RUs, DynamoDB
     * capacity units). Returns 0.0 when not available or not opted-in.
     * Prefer {@link #consumption()} to distinguish unavailable from measured zero.
     * Neither accessor represents a monetary bill.
     */
    public double requestCharge() { return requestCharge; }

    /** ETag / optimistic-concurrency token, or null if not available. */
    public String etag()         { return etag; }

    /** Cosmos session-consistency token, or null. */
    public String sessionToken() { return sessionToken; }

    /** Number of items/events returned by a query/change-feed page; 0 for point operations. */
    public int itemCount()       { return itemCount; }

    @Override
    public String toString() {
        return "OperationDiagnostics{provider=" + (provider != null ? provider.id() : "null")
                + ", operation=" + operation + ", duration=" + duration
                + ", requestId=" + requestId
                + ", statusCode=" + statusCode
                + ", requestCharge=" + requestCharge
                + ", etag=" + etag
                + ", sessionToken=" + (sessionToken != null ? "<present>" : "null")
                + ", itemCount=" + itemCount + "}";
    }

    /**
     * Builder for constructing enriched {@link OperationDiagnostics} objects.
     * <p>
     * This class is {@code public} because provider adapter implementations in
     * separate modules (e.g. {@code multiclouddb-provider-cosmos}) must be able to
     * construct diagnostics objects. Application code should treat
     * {@code OperationDiagnostics} as a <em>read-only result type</em> — there is
     * no supported use-case for applications constructing diagnostics directly.
     * Module-level visibility constraints (tracked in PR #21) will formalize this
     * boundary once the module graph is in place.
     */
    public static final class Builder {
        private ProviderId provider;
        private String operation;
        private Duration duration;
        private Instant startTime;
        private Instant endTime;
        private OperationOutcome outcome;
        private StatusCodeDomain statusCodeDomain;
        private ResourceConsumption consumption;
        private String requestId;
        private Integer statusCode;
        private double requestCharge;
        private String etag;
        private String sessionToken;
        private int itemCount;

        private Builder(ProviderId provider, String operation, Duration duration) {
            this.provider = provider;
            this.operation = operation;
            this.duration = duration;
        }

        public Builder provider(ProviderId provider) { this.provider = Objects.requireNonNull(provider, "provider"); return this; }
        public Builder operation(String operation) { this.operation = Objects.requireNonNull(operation, "operation"); return this; }
        public Builder duration(Duration duration) { this.duration = Objects.requireNonNull(duration, "duration"); return this; }
        public Builder startTime(Instant startTime) { this.startTime = startTime; return this; }
        public Builder endTime(Instant endTime) { this.endTime = endTime; return this; }
        public Builder outcome(OperationOutcome outcome) { this.outcome = outcome; return this; }
        public Builder statusCodeDomain(StatusCodeDomain domain) { this.statusCodeDomain = domain; return this; }
        /** Non-null consumption determines requestCharge at build time regardless of setter order. */
        public Builder consumption(ResourceConsumption consumption) { this.consumption = consumption; return this; }
        public Builder requestId(String requestId)       { this.requestId = requestId; return this; }
        public Builder statusCode(Integer statusCode)    { this.statusCode = statusCode; return this; }
        /** Legacy-only charge; overridden by a non-null consumption value at build time. */
        public Builder requestCharge(double charge)      { this.requestCharge = charge; return this; }
        public Builder etag(String etag)                 { this.etag = etag; return this; }
        public Builder sessionToken(String sessionToken) { this.sessionToken = sessionToken; return this; }
        public Builder itemCount(int itemCount)          { this.itemCount = itemCount; return this; }

        public OperationDiagnostics build() { return new OperationDiagnostics(this); }
    }
}
