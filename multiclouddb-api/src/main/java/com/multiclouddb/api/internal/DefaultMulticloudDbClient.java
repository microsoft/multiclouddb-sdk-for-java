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
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
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
    public ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options) {
        Objects.requireNonNull(request, "request");
        Instant start = Instant.now();
        try {
            // Fail-fast capability gate before hitting the provider
            checkCapability(Capability.CHANGE_FEED,
                    "Change feed is not supported by provider " + config.provider().id(),
                    "readChanges");
            ChangeFeedPage page = providerClient.readChanges(request, options);
            LOG.debug("readChanges completed: address={}, events={}, hasMore={}, duration={}ms",
                    request.address(), page.events().size(), page.hasMore(),
                    Duration.between(start, Instant.now()).toMillis());
            return page;
        } catch (MulticloudDbException e) {
            throw enrichException(e, "readChanges", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "readChanges", start);
        }
    }

    @Override
    public void ensureDatabase(String database) {
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
    public void close() throws Exception {
        providerClient.close();
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
     */
    private void checkCapability(String capabilityName, String message) {
        checkCapability(capabilityName, message, "query");
    }

    private void checkCapability(String capabilityName, String message, String operation) {
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
