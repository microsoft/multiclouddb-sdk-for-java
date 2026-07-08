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
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.CursorExpiredException;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

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
    private volatile ExpressionTranslator expressionTranslator;
    /**
     * Lifecycle flag flipped to {@code true} by {@link #close()}. All public
     * entry points consult {@link #checkOpen(String)} first so a post-close
     * call always surfaces {@link MulticloudDbErrorCategory#CLIENT_CLOSED}
     * before any other validation (e.g. {@link DocumentSizeValidator}) runs.
     * Declared {@code volatile} so a thread that races with {@link #close()}
     * sees the flip without locking. See also the matching provider-level
     * guards in {@code SpannerProviderClient}, {@code CosmosProviderClient},
     * and {@code DynamoProviderClient}.
     */
    private volatile boolean closed = false;

    public DefaultMulticloudDbClient(MulticloudDbProviderClient providerClient, MulticloudDbClientConfig config) {
        this.providerClient = Objects.requireNonNull(providerClient, "providerClient");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Set the expression translator for portable query support.
     */
    public void setExpressionTranslator(ExpressionTranslator translator) {
        this.expressionTranslator = translator;
    }

    @Override
    public void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.CREATE);
        Instant start = Instant.now();
        try {
            DocumentSizeValidator.validate(document, OperationNames.CREATE);
            providerClient.create(address, key, document, options);
            LOG.debug("create completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "create", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "create", start);
        }
    }

    @Override
    public DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        checkOpen(OperationNames.READ);
        Instant start = Instant.now();
        try {
            DocumentResult result = providerClient.read(address, key, options);
            LOG.debug("read completed: address={}, key={}, found={}, duration={}ms",
                    address, key, result != null, Duration.between(start, Instant.now()).toMillis());
            return result;
        } catch (MulticloudDbException e) {
            throw enrichException(e, "read", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "read", start);
        }
    }

    @Override
    public void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.UPDATE);
        Instant start = Instant.now();
        try {
            DocumentSizeValidator.validate(document, OperationNames.UPDATE);
            providerClient.update(address, key, document, options);
            LOG.debug("update completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "update", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "update", start);
        }
    }

    @Override
    public void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.UPSERT);
        Instant start = Instant.now();
        try {
            DocumentSizeValidator.validate(document, OperationNames.UPSERT);
            providerClient.upsert(address, key, document, options);
            LOG.debug("upsert completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "upsert", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "upsert", start);
        }
    }

    @Override
    public void delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        checkOpen(OperationNames.DELETE);
        Instant start = Instant.now();
        try {
            providerClient.delete(address, key, options);
            LOG.debug("delete completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "delete", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "delete", start);
        }
    }

    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        checkOpen(OperationNames.QUERY);
        Instant start = Instant.now();
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
                    Duration.between(start, Instant.now()).toMillis());
            return page;
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
            throw enrichException(e, "query", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "query", start);
        }
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
    public void ensureDatabase(String database) {
        checkOpen(OperationNames.ENSURE_DATABASE);
        Instant start = Instant.now();
        try {
            providerClient.ensureDatabase(database);
            LOG.debug("ensureDatabase completed: database={}, duration={}ms",
                    database, Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "ensureDatabase", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "ensureDatabase", start);
        }
    }

    @Override
    public void ensureContainer(ResourceAddress address) {
        checkOpen(OperationNames.ENSURE_CONTAINER);
        Instant start = Instant.now();
        try {
            providerClient.ensureContainer(address);
            LOG.debug("ensureContainer completed: address={}, duration={}ms",
                    address, Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "ensureContainer", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "ensureContainer", start);
        }
    }

    @Override
    public void provisionSchema(Map<String, java.util.List<String>> schema) {
        checkOpen(OperationNames.PROVISION_SCHEMA);
        Instant start = Instant.now();
        try {
            providerClient.provisionSchema(schema);
            LOG.debug("provisionSchema completed: databases={}, duration={}ms",
                    schema.size(), Duration.between(start, Instant.now()).toMillis());
        } catch (MulticloudDbException e) {
            throw enrichException(e, "provisionSchema", start);
        } catch (Exception e) {
            // A provider's provisionSchema may run tasks via CompletableFuture internally.
            // Peel all CompletionException layers so a MulticloudDbException thrown
            // inside an async task is not misclassified as PROVIDER_ERROR.
            Throwable cause = e;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof MulticloudDbException) {
                throw enrichException((MulticloudDbException) cause, "provisionSchema", start);
            }
            throw wrapUnexpected(cause instanceof Exception ? (Exception) cause : e, "provisionSchema", start);
        }
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
    public List<ChangeFeedCursor> listCursors(ResourceAddress address) {
        checkOpen(OperationNames.LIST_CURSORS);
        Objects.requireNonNull(address, "address");
        checkCapability(Capability.CHANGE_FEED,
                OperationNames.LIST_CURSORS,
                "Change feed is not supported by provider " + config.provider().id());
        Instant start = Instant.now();
        try {
            List<ChangeFeedCursor> cursors = providerClient.listCursors(address);
            LOG.debug("listCursors completed: address={}, cursors={}, duration={}ms",
                    address, cursors.size(), Duration.between(start, Instant.now()).toMillis());
            return cursors;
        } catch (MulticloudDbException e) {
            throw enrichException(e, OperationNames.LIST_CURSORS, start);
        } catch (Exception e) {
            throw wrapUnexpected(e, OperationNames.LIST_CURSORS, start);
        }
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

        Instant start = Instant.now();
        try {
            ChangeFeedPage page = providerClient.readChanges(address, cursor, options);
            LOG.debug("readChanges completed: address={}, events={}, hasMore={}, terminal={}, duration={}ms",
                    address, page.events().size(), page.hasMore(), page.isTerminal(),
                    Duration.between(start, Instant.now()).toMillis());
            return page;
        } catch (MulticloudDbException e) {
            // enrichException mutates and returns the same instance, so a
            // CursorExpiredException still surfaces as CursorExpiredException.
            throw enrichException(e, OperationNames.READ_CHANGES, start);
        } catch (Exception e) {
            throw wrapUnexpected(e, OperationNames.READ_CHANGES, start);
        }
    }

    private MulticloudDbException enrichException(MulticloudDbException e, String operation, Instant start) {
        Duration duration = Duration.between(start, Instant.now());
        OperationDiagnostics diag = OperationDiagnostics
                .builder(config.provider(), operation, duration)
                .requestId(e.error().providerDetails().get("requestId"))

                .build();
        return e.withDiagnostics(diag);
    }

    private MulticloudDbException wrapUnexpected(Exception e, String operation, Instant start) {
        Duration duration = Duration.between(start, Instant.now());
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "Unexpected error during " + operation + ": " + e.getMessage(),
                config.provider(),
                operation,
                false,
                Map.of("exceptionType", e.getClass().getName()));
        OperationDiagnostics diag = OperationDiagnostics
                .builder(config.provider(), operation, duration)
                .build();
        return new MulticloudDbException(error, e).withDiagnostics(diag);
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
