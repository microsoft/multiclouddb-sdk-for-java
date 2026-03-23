package com.hyperscaledb.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Diagnostics metadata for a completed operation.
 * <p>
 * All fields except {@code provider}, {@code operation}, and {@code duration}
 * are optional — providers populate what their SDK exposes and leave the rest
 * null/0. Does not contain secrets.
 *
 * <table border="1">
 * <caption>Provider field availability</caption>
 * <tr><th>Field</th><th>Cosmos</th><th>DynamoDB</th><th>Spanner</th></tr>
 * <tr><td>requestId</td><td>activityId</td><td>x-amzn-RequestId</td><td>-</td></tr>
 * <tr><td>statusCode</td><td>HTTP status</td><td>HTTP status</td><td>gRPC code</td></tr>
 * <tr><td>requestCharge</td><td>RU cost</td><td>consumed capacity</td><td>-</td></tr>
 * <tr><td>etag</td><td>_etag</td><td>-</td><td>commit timestamp</td></tr>
 * <tr><td>sessionToken</td><td>session token</td><td>-</td><td>-</td></tr>
 * <tr><td>itemCount</td><td>query results</td><td>query results</td><td>query results</td></tr>
 * </table>
 */
public final class OperationDiagnostics {

    private final ProviderId provider;
    private final String operation;
    private final Duration duration;
    private final String requestId;

    /** HTTP-like status code; null if not available. */
    private final Integer statusCode;

    /**
     * Provider-specific cost of the operation (Cosmos RU, DynamoDB consumed
     * capacity units); 0.0 if not available or not opted-in.
     */
    private final double requestCharge;

    /**
     * ETag / optimistic-concurrency token returned by the provider; null if not
     * available. Cosmos returns {@code _etag}; Spanner returns the commit
     * timestamp as a string on writes.
     */
    private final String etag;

    /**
     * Cosmos session-consistency token; null for all other providers.
     */
    private final String sessionToken;

    /**
     * Number of items in the page for query operations; 0 for point operations.
     */
    private final int itemCount;

    private OperationDiagnostics(Builder builder) {
        this.provider = builder.provider;
        this.operation = builder.operation;
        this.duration = builder.duration;
        this.requestId = builder.requestId;
        this.statusCode = builder.statusCode;
        this.requestCharge = builder.requestCharge;
        this.etag = builder.etag;
        this.sessionToken = builder.sessionToken;
        this.itemCount = builder.itemCount;
    }

    /** @deprecated Use {@link #builder(ProviderId, String, Duration)} instead. */
    @Deprecated
    public OperationDiagnostics(ProviderId provider, String operation, Duration duration, String requestId) {
        this.provider = provider;
        this.operation = operation;
        this.duration = duration;
        this.requestId = requestId;
        this.statusCode = null;
        this.requestCharge = 0.0;
        this.etag = null;
        this.sessionToken = null;
        this.itemCount = 0;
    }

    public static Builder builder(ProviderId provider, String operation, Duration duration) {
        return new Builder(provider, operation, duration);
    }

    public ProviderId provider() { return provider; }
    public String operation()   { return operation; }
    public Duration duration()  { return duration; }

    /** Provider-supplied correlation/request identifier, or null if not available. */
    public String requestId()   { return requestId; }

    /** HTTP-like status code, or null if not available. */
    public Integer statusCode() { return statusCode; }

    /**
     * Cost of the operation in provider-specific units (Cosmos RUs, DynamoDB
     * capacity units). Returns 0.0 when not available or not opted-in.
     */
    public double requestCharge() { return requestCharge; }

    /** ETag / optimistic-concurrency token, or null if not available. */
    public String etag()         { return etag; }

    /** Cosmos session-consistency token, or null. */
    public String sessionToken() { return sessionToken; }

    /** Number of items returned by a query page; 0 for point operations. */
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

    public static final class Builder {
        private final ProviderId provider;
        private final String operation;
        private final Duration duration;
        private String requestId;
        private Integer statusCode;
        private double requestCharge;
        private String etag;
        private String sessionToken;
        private int itemCount;

        private Builder(ProviderId provider, String operation, Duration duration) {
            this.provider = Objects.requireNonNull(provider, "provider");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.duration = Objects.requireNonNull(duration, "duration");
        }

        public Builder requestId(String requestId)       { this.requestId = requestId; return this; }
        public Builder statusCode(Integer statusCode)    { this.statusCode = statusCode; return this; }
        public Builder requestCharge(double charge)      { this.requestCharge = charge; return this; }
        public Builder etag(String etag)                 { this.etag = etag; return this; }
        public Builder sessionToken(String sessionToken) { this.sessionToken = sessionToken; return this; }
        public Builder itemCount(int itemCount)          { this.itemCount = itemCount; return this; }

        public OperationDiagnostics build() { return new OperationDiagnostics(this); }
    }
}
