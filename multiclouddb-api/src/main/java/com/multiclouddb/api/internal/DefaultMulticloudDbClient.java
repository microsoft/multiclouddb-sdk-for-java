// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.OperationOutcome;
import com.multiclouddb.api.OperationResponse;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.StatusCodeDomain;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.CursorTokenCodec;
import com.multiclouddb.api.query.Expression;
import com.multiclouddb.api.query.ExpressionParseException;
import com.multiclouddb.api.query.ExpressionParser;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.api.query.ExpressionValidationException;
import com.multiclouddb.api.query.ExpressionValidator;
import com.multiclouddb.api.query.LogicalExpression;
import com.multiclouddb.api.query.NotExpression;
import com.multiclouddb.api.query.TranslatedQuery;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Default implementation of {@link MulticloudDbClient} that delegates to a provider
 * adapter client.
 * Adds diagnostics, capability fail-fast checks, and portability warning
 * propagation.
 */
public final class DefaultMulticloudDbClient implements MulticloudDbClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMulticloudDbClient.class);

    private final MulticloudDbProviderClient providerClient;
    private final MulticloudDbClientConfig config;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private volatile ExpressionTranslator expressionTranslator;
    /**
     * Lifecycle flag flipped to {@code true} by {@link #close()}. All public
     * entry points consult {@link #checkOpen(String)} first so a post-close
     * call always surfaces {@link MulticloudDbErrorCategory#CLIENT_CLOSED}
     * before any other validation (e.g. {@link DocumentValidator}) runs.
     * Declared {@code volatile} so a thread that races with {@link #close()}
     * sees the flip without locking. See also the matching provider-level
     * guards in {@code SpannerProviderClient}, {@code CosmosProviderClient},
     * and {@code DynamoProviderClient}.
     */
    private volatile boolean closed = false;

    public DefaultMulticloudDbClient(MulticloudDbProviderClient providerClient, MulticloudDbClientConfig config) {
        this(providerClient, config, Clock.systemUTC(), System::nanoTime);
    }

    DefaultMulticloudDbClient(MulticloudDbProviderClient providerClient, MulticloudDbClientConfig config,
                             Clock clock, LongSupplier nanoTime) {
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Set the expression translator for portable query support.
     */
    public void setExpressionTranslator(ExpressionTranslator translator) {
        this.expressionTranslator = translator;
    }

    @Override
    public OperationResponse<Void> create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        return observe(OperationNames.CREATE, start -> {
            checkOpen(OperationNames.CREATE);
            try {
                DocumentValidator.validate(document, OperationNames.CREATE);
                OperationResponse<Void> result = providerClient.create(address, key, document, options);
                LOG.debug("create completed: address={}, key={}, duration={}ms",
                        address, key, start.elapsed().toMillis());
                return complete(result, OperationNames.CREATE, OperationOutcome.SUCCESS, start);
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "create");
            }
        });
    }

    @Override
    public OperationResponse<DocumentResult> read(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        return observe(OperationNames.READ, start -> {
            checkOpen(OperationNames.READ);
            try {
                OperationResponse<DocumentResult> result = providerClient.read(address, key, options);
                LOG.debug("read completed: address={}, key={}, found={}, duration={}ms",
                        address, key, result.result() != null, start.elapsed().toMillis());
                return complete(result, OperationNames.READ,
                        result.result() == null ? OperationOutcome.NOT_FOUND : OperationOutcome.SUCCESS, start);
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "read");
            }
        });
    }

    @Override
    public OperationResponse<Void> update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        return observe(OperationNames.UPDATE, start -> {
            checkOpen(OperationNames.UPDATE);
            try {
                DocumentValidator.validate(document, OperationNames.UPDATE);
                OperationResponse<Void> result = providerClient.update(address, key, document, options);
                LOG.debug("update completed: address={}, key={}, duration={}ms",
                        address, key, start.elapsed().toMillis());
                return complete(result, OperationNames.UPDATE, OperationOutcome.SUCCESS, start);
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "update");
            }
        });
    }

    @Override
    public OperationResponse<Void> upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        return observe(OperationNames.UPSERT, start -> {
            checkOpen(OperationNames.UPSERT);
            try {
                DocumentValidator.validate(document, OperationNames.UPSERT);
                OperationResponse<Void> result = providerClient.upsert(address, key, document, options);
                LOG.debug("upsert completed: address={}, key={}, duration={}ms",
                        address, key, start.elapsed().toMillis());
                return complete(result, OperationNames.UPSERT, OperationOutcome.SUCCESS, start);
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "upsert");
            }
        });
    }

    @Override
    public OperationResponse<Void> delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        return observe(OperationNames.DELETE, start -> {
            checkOpen(OperationNames.DELETE);
            try {
                OperationResponse<Void> result = providerClient.delete(address, key, options);
                LOG.debug("delete completed: address={}, key={}, duration={}ms",
                        address, key, start.elapsed().toMillis());
                return complete(result, OperationNames.DELETE, OperationOutcome.SUCCESS, start);
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "delete");
            }
        });
    }

    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        return observe(OperationNames.QUERY, start -> {
            checkOpen(OperationNames.QUERY);
            try {
                QueryPage page;

                // Native expression passthrough (T069)
                if (query.nativeExpression() != null && !query.nativeExpression().isBlank()) {
                    LOG.debug("query using native expression passthrough: address={}", address);
                    page = providerClient.query(address, query, options);
                }
                // Portable expression pipeline: parse → validate → translate → execute
                else if (query.expression() != null && !query.expression().isBlank()
                        && expressionTranslator != null
                        && !isLegacyExpression(query.expression())) {
                    // Fail-fast: check portable query capability (T080)
                    checkCapability(Capability.PORTABLE_QUERY_EXPRESSION,
                            OperationNames.QUERY,
                            "Portable query expressions are not supported by provider " + config.provider().id());

                    Expression ast = ExpressionParser.parse(query.expression());
                    ExpressionValidator.validate(ast, query.parameters());

                    // Fail-fast: check capability-gated features in the AST (T080)
                    checkExpressionCapabilities(ast);

                    TranslatedQuery translated = expressionTranslator.translate(
                            ast, query.parameters(), address.collection());
                    LOG.debug("query translated: address={}, native={}", address, translated.queryString());
                    page = providerClient.queryWithTranslation(address, translated, query, options);
                }
                // Legacy/opaque expression or no translator — pass through directly
                else {
                    page = providerClient.query(address, query, options);
                }

                LOG.debug("query completed: address={}, items={}, hasMore={}, duration={}ms",
                        address, page.items().size(), page.continuationToken() != null,
                        start.elapsed().toMillis());
                return new QueryPage(page.items(), page.continuationToken(),
                        completeDiagnostics(page.diagnostics(), OperationNames.QUERY, OperationOutcome.SUCCESS, start)
                                .itemCount(page.items().size()).build());
            } catch (ExpressionParseException | ExpressionValidationException e) {
                throw new MulticloudDbException(
                        new MulticloudDbError(
                                MulticloudDbErrorCategory.INVALID_REQUEST,
                                e.getMessage(),
                                config.provider(),
                                "query",
                                false,
                                Map.of()),
                        e);
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "query");
            }
        });
    }

    /**
     * Check if an expression looks like a legacy opaque expression
     * (e.g., "SELECT * FROM c" or contains provider-specific syntax like ":param").
     */
    private boolean isLegacyExpression(String expression) {
        String trimmed = expression.trim();
        // Legacy patterns: full SQL statements or DynamoDB filter expressions with
        // :param
        return trimmed.toUpperCase().startsWith("SELECT ")
                || trimmed.contains(":")
                || trimmed.startsWith("#");
    }

    @Override
    public CapabilitySet capabilities() {
        return providerClient.capabilities();
    }

    @Override
    public OperationResponse<Void> ensureDatabase(String database) {
        return observe(OperationNames.ENSURE_DATABASE, start -> {
            checkOpen(OperationNames.ENSURE_DATABASE);
            try {
                providerClient.ensureDatabase(database);
                LOG.debug("ensureDatabase completed: database={}, duration={}ms",
                        database, start.elapsed().toMillis());
                return new OperationResponse<>(null,
                        completeDiagnostics(null, OperationNames.ENSURE_DATABASE, OperationOutcome.SUCCESS, start).build());
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "ensureDatabase");
            }
        });
    }

    @Override
    public OperationResponse<Void> ensureContainer(ResourceAddress address) {
        return observe(OperationNames.ENSURE_CONTAINER, start -> {
            checkOpen(OperationNames.ENSURE_CONTAINER);
            try {
                providerClient.ensureContainer(address);
                LOG.debug("ensureContainer completed: address={}, duration={}ms",
                        address, start.elapsed().toMillis());
                return new OperationResponse<>(null,
                        completeDiagnostics(null, OperationNames.ENSURE_CONTAINER, OperationOutcome.SUCCESS, start).build());
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, "ensureContainer");
            }
        });
    }

    @Override
    public OperationResponse<Void> provisionSchema(Map<String, java.util.List<String>> schema) {
        return observe(OperationNames.PROVISION_SCHEMA, start -> {
            checkOpen(OperationNames.PROVISION_SCHEMA);
            try {
                providerClient.provisionSchema(schema);
                LOG.debug("provisionSchema completed: databases={}, duration={}ms",
                        schema.size(), start.elapsed().toMillis());
                return new OperationResponse<>(null,
                        completeDiagnostics(null, OperationNames.PROVISION_SCHEMA, OperationOutcome.SUCCESS, start).build());
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                // A provider's provisionSchema may run tasks via CompletableFuture internally.
                // Peel all CompletionException layers so a MulticloudDbException thrown
                // inside an async task is not misclassified as PROVIDER_ERROR.
                Throwable cause = e;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof MulticloudDbException) {
                    throw (MulticloudDbException) cause;
                }
                throw wrapUnexpected(cause instanceof Exception ? (Exception) cause : e, "provisionSchema");
            }
        });
    }

    @Override
    public ProviderId providerId() {
        return config.provider();
    }

    @Override
    public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        providerClient.close();
    }

    /**
     * Guards public {@link MulticloudDbClient} entry points against use after
     * {@link #close()}.
     * <p>
     * Throws a typed {@link MulticloudDbException} with category
     * {@link MulticloudDbErrorCategory#CLIENT_CLOSED} <em>before</em> any
     * other validation (size checks, expression parsing, capability checks)
     * runs, so callers can branch on {@code e.error().category()} without
     * string-matching the message. Provider-level adapters have matching
     * guards as a defense-in-depth layer for callers that talk to the SPI
     * directly.
     *
     * @param operation the caller's operation name from {@link OperationNames}
     */
    private void checkOpen(String operation) {
        if (closed) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CLIENT_CLOSED,
                    "MulticloudDbClient has been closed",
                    config.provider(), operation, false, Map.of()));
        }
    }

    // ── Change Feed ────────────────────────────────────────────────────────────

    @Override
    public OperationResponse<List<ChangeFeedCursor>> listCursors(ResourceAddress address) {
        return observe(OperationNames.LIST_CURSORS, start -> {
            checkOpen(OperationNames.LIST_CURSORS);
            Objects.requireNonNull(address, "address");
            checkCapability(Capability.CHANGE_FEED,
                    OperationNames.LIST_CURSORS,
                    "Change feed is not supported by provider " + config.provider().id());
            try {
                List<ChangeFeedCursor> cursors = providerClient.listCursors(address);
                LOG.debug("listCursors completed: address={}, cursors={}, duration={}ms",
                        address, cursors.size(), start.elapsed().toMillis());
                return new OperationResponse<>(cursors,
                        completeDiagnostics(null, OperationNames.LIST_CURSORS, OperationOutcome.SUCCESS, start).build());
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, OperationNames.LIST_CURSORS);
            }
        });
    }

    @Override
    public ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor) {
        return readChanges(address, cursor, OperationOptions.defaults());
    }

    /**
     * One-shot guard so the per-call WARN about an unenforced
     * {@link OperationOptions#timeout()} on the change-feed path fires once
     * per JVM, not on every call. See the {@code readChanges} body below for
     * the rationale.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean
            READ_CHANGES_TIMEOUT_WARNED = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor,
                                      OperationOptions options) {
        return observe(OperationNames.READ_CHANGES, start -> {
            checkOpen(OperationNames.READ_CHANGES);
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(options, "options");
            checkCapability(Capability.CHANGE_FEED,
                    OperationNames.READ_CHANGES,
                    "Change feed is not supported by provider " + config.provider().id());

            // Validate cursor binding before touching the provider. An unhydrated
            // sentinel (now()) is always accepted — the provider hydrates it on first read.
            CursorToken token = cursor.token();
            if (!cursor.isUnhydratedSentinel()) {
                CursorTokenCodec.validateProviderMatch(token, config.provider());
                CursorTokenCodec.validateResourceMatch(token, address, config.provider());
            }

            // v1 contract: no built-in provider honours OperationOptions on the
            // change-feed path. Surface a single WARN the first time a non-default
            // timeout is passed so a caller who expects readChanges to be bounded
            // by options.timeout() is told once, loudly, that the field is being
            // ignored. We deliberately do NOT throw — callers commonly share an
            // OperationOptions value across CRUD + change-feed and we must not
            // break that pattern. Tracked as T174 in specs/001-clouddb-sdk/tasks.md
            // for a future PR that honours the timeout per-call.
            if (options.timeout() != null
                    && READ_CHANGES_TIMEOUT_WARNED.compareAndSet(false, true)) {
                LOG.warn("readChanges: OperationOptions.timeout() is set ({}) but is not "
                        + "enforced by any v1 change-feed provider. Wall-clock of each call "
                        + "is bounded by the provider's own page-fetch behaviour (Cosmos: "
                        + "per-request; Dynamo: ~5s GetRecords; Spanner: 5s TVF window). "
                        + "This warning is logged once per JVM.", options.timeout());
            }

            try {
                ChangeFeedPage page = providerClient.readChanges(address, cursor, options);
                LOG.debug("readChanges completed: address={}, events={}, hasMore={}, terminal={}, duration={}ms",
                        address, page.events().size(), page.hasMore(), page.isTerminal(),
                        start.elapsed().toMillis());
                return new ChangeFeedPage(page.events(), page.nextCursor(), page.hasMore(), page.isTerminal(),
                        completeDiagnostics(page.diagnostics(), OperationNames.READ_CHANGES, OperationOutcome.SUCCESS, start)
                                .itemCount(page.events().size()).build());
            } catch (MulticloudDbException e) {
                throw e;
            } catch (Exception e) {
                throw wrapUnexpected(e, OperationNames.READ_CHANGES);
            }
        });
    }

    private <T> T observe(String operation, Function<Timing, T> action) {
        Timing timing = new Timing(clock.instant(), nanoTime.getAsLong());
        try {
            return action.apply(timing);
        } catch (MulticloudDbException e) {
            throw enrichException(e, operation, timing);
        }
    }

    private <T> OperationResponse<T> complete(OperationResponse<T> response, String operation,
                                              OperationOutcome outcome, Timing timing) {
        return new OperationResponse<>(response.result(),
                completeDiagnostics(response.diagnostics(), operation, outcome, timing).build());
    }

    private OperationDiagnostics.Builder completeDiagnostics(OperationDiagnostics metadata, String operation,
                                                              OperationOutcome outcome, Timing timing) {
        OperationDiagnostics.Builder builder = metadata == null
                ? OperationDiagnostics.builder(config.provider(), operation) : metadata.toBuilder();
        if (metadata != null && metadata.statusCode() != null && metadata.statusCodeDomain() == null) {
            if (nativeStatusDomain() == StatusCodeDomain.HTTP && metadata.statusCode() <= 0) {
                builder.statusCode(null);
            } else {
                builder.statusCodeDomain(nativeStatusDomain());
            }
        }
        return builder.provider(config.provider()).operation(operation)
                .startTime(timing.start).endTime(clock.instant()).duration(timing.elapsed()).outcome(outcome);
    }

    private MulticloudDbException enrichException(MulticloudDbException e, String operation, Timing timing) {
        OperationDiagnostics.Builder builder = completeDiagnostics(e.diagnostics(), operation,
                OperationOutcome.FAILURE, timing);
        if (e.diagnostics() == null || e.diagnostics().requestId() == null) {
            String requestId = e.error().providerDetails().get("requestId");
            builder.requestId("UNKNOWN".equals(requestId) ? null : requestId);
        }
        if ((e.diagnostics() == null || e.diagnostics().statusCode() == null)
                && e.error().statusCode() != null
                && (nativeStatusDomain() != StatusCodeDomain.HTTP || e.error().statusCode() > 0)) {
            builder.statusCode(e.error().statusCode()).statusCodeDomain(nativeStatusDomain());
        }
        return e.withDiagnostics(builder.build());
    }

    private StatusCodeDomain nativeStatusDomain() {
        if (config.provider() == ProviderId.SPANNER) {
            return StatusCodeDomain.GRPC;
        }
        return config.provider() == ProviderId.COSMOS || config.provider() == ProviderId.DYNAMO
                ? StatusCodeDomain.HTTP : null;
    }

    private MulticloudDbException wrapUnexpected(Exception e, String operation) {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "Unexpected error during " + operation + ": " + e.getMessage(),
                config.provider(),
                operation,
                false,
                Map.of("exceptionType", e.getClass().getName()));
        return new MulticloudDbException(error, e);
    }

    private final class Timing {
        private final Instant start;
        private final long startNanos;

        private Timing(Instant start, long startNanos) {
            this.start = start;
            this.startNanos = startNanos;
        }

        private Duration elapsed() {
            return Duration.ofNanos(nanoTime.getAsLong() - startNanos);
        }
    }

    /**
     * Fail-fast: throw if a required capability is not supported (T080).
     *
     * <p>{@code operation} carries the caller's portable operation name from
     * {@link OperationNames} (e.g. {@link OperationNames#LIST_CURSORS}). The
     * earlier signature hard-coded {@code "query"} for every call site, so
     * change-feed entry points that surfaced a capability gate failure
     * reported {@code error().operation() == "query"} instead of
     * {@code "listCursors"} / {@code "readChanges"}. Diagnostics consumers
     * that branch on {@code error().operation()} would silently mis-attribute
     * the failure; passing the real operation closes that gap.</p>
     */
    private void checkCapability(String capabilityName, String operation, String message) {
        CapabilitySet caps = providerClient.capabilities();
        if (!caps.isSupported(capabilityName)) {
            throw new MulticloudDbException(
                    new MulticloudDbError(
                            MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                            message,
                            config.provider(),
                            operation,
                            false,
                            Map.of("capability", capabilityName)));
        }
    }

    /**
     * Walk the expression AST and fail-fast if it uses capability-gated features
     * not supported by this provider (T080).
     */
    private void checkExpressionCapabilities(Expression ast) {
        CapabilitySet caps = providerClient.capabilities();
        checkExpressionCapabilitiesRecursive(ast, caps);
    }

    private void checkExpressionCapabilitiesRecursive(Expression expr, CapabilitySet caps) {
        if (expr instanceof LogicalExpression logical) {
            checkExpressionCapabilitiesRecursive(logical.left(), caps);
            checkExpressionCapabilitiesRecursive(logical.right(), caps);
        } else if (expr instanceof NotExpression not) {
            checkExpressionCapabilitiesRecursive(not.child(), caps);
        }
        // No capability-gated operators in the current expression grammar.
        // Future capability-gated features (e.g., LIKE, ORDER BY) can be
        // checked here when added to the portable expression parser.
    }
}
