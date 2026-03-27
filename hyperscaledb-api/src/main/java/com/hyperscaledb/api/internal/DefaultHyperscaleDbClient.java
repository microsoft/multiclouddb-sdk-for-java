// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.api.internal;

import com.hyperscaledb.api.Capability;
import com.hyperscaledb.api.CapabilitySet;
import com.hyperscaledb.api.HyperscaleDbClient;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.HyperscaleDbError;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.HyperscaleDbKey;
import com.hyperscaledb.api.OperationDiagnostics;
import com.hyperscaledb.api.OperationOptions;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.hyperscaledb.api.query.Expression;
import com.hyperscaledb.api.query.ExpressionParseException;
import com.hyperscaledb.api.query.ExpressionParser;
import com.hyperscaledb.api.query.ExpressionTranslator;
import com.hyperscaledb.api.query.ExpressionValidationException;
import com.hyperscaledb.api.query.ExpressionValidator;
import com.hyperscaledb.api.query.LogicalExpression;
import com.hyperscaledb.api.query.NotExpression;
import com.hyperscaledb.api.query.TranslatedQuery;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link HyperscaleDbClient} that delegates to a provider
 * adapter client.
 * Adds diagnostics, capability fail-fast checks, and portability warning
 * propagation.
 */
public final class DefaultHyperscaleDbClient implements HyperscaleDbClient {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHyperscaleDbClient.class);

    private final HyperscaleDbProviderClient providerClient;
    private final HyperscaleDbClientConfig config;
    private volatile ExpressionTranslator expressionTranslator;

    public DefaultHyperscaleDbClient(HyperscaleDbProviderClient providerClient, HyperscaleDbClientConfig config) {
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
    public void create(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options) {
        Instant start = Instant.now();
        try {
            providerClient.create(address, key, document, options);
            LOG.debug("create completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (HyperscaleDbException e) {
            throw enrichException(e, "create", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "create", start);
        }
    }

    @Override
    public Map<String, Object> read(ResourceAddress address, HyperscaleDbKey key, OperationOptions options) {
        Instant start = Instant.now();
        try {
            Map<String, Object> result = providerClient.read(address, key, options);
            LOG.debug("read completed: address={}, key={}, found={}, duration={}ms",
                    address, key, result != null, Duration.between(start, Instant.now()).toMillis());
            return result;
        } catch (HyperscaleDbException e) {
            throw enrichException(e, "read", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "read", start);
        }
    }

    @Override
    public void update(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options) {
        Instant start = Instant.now();
        try {
            providerClient.update(address, key, document, options);
            LOG.debug("update completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (HyperscaleDbException e) {
            throw enrichException(e, "update", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "update", start);
        }
    }

    @Override
    public void upsert(ResourceAddress address, HyperscaleDbKey key, Map<String, Object> document, OperationOptions options) {
        Instant start = Instant.now();
        try {
            providerClient.upsert(address, key, document, options);
            LOG.debug("upsert completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (HyperscaleDbException e) {
            throw enrichException(e, "upsert", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "upsert", start);
        }
    }

    @Override
    public void delete(ResourceAddress address, HyperscaleDbKey key, OperationOptions options) {
        Instant start = Instant.now();
        try {
            providerClient.delete(address, key, options);
            LOG.debug("delete completed: address={}, key={}, duration={}ms",
                    address, key, Duration.between(start, Instant.now()).toMillis());
        } catch (HyperscaleDbException e) {
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
            throw new HyperscaleDbException(
                    new HyperscaleDbError(
                            HyperscaleDbErrorCategory.INVALID_REQUEST,
                            e.getMessage(),
                            config.provider(),
                            "query",
                            false,
                            Map.of()),
                    e);
        } catch (HyperscaleDbException e) {
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
        Instant start = Instant.now();
        try {
            providerClient.ensureDatabase(database);
            LOG.debug("ensureDatabase completed: database={}, duration={}ms",
                    database, Duration.between(start, Instant.now()).toMillis());
        } catch (HyperscaleDbException e) {
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
        } catch (HyperscaleDbException e) {
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
        } catch (HyperscaleDbException e) {
            throw enrichException(e, "provisionSchema", start);
        } catch (Exception e) {
            throw wrapUnexpected(e, "provisionSchema", start);
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

    private HyperscaleDbException enrichException(HyperscaleDbException e, String operation, Instant start) {
        Duration duration = Duration.between(start, Instant.now());
        OperationDiagnostics diag = new OperationDiagnostics(
                config.provider(), operation, duration,
                e.error().providerDetails().get("requestId"));
        return e.withDiagnostics(diag);
    }

    private HyperscaleDbException wrapUnexpected(Exception e, String operation, Instant start) {
        Duration duration = Duration.between(start, Instant.now());
        HyperscaleDbError error = new HyperscaleDbError(
                HyperscaleDbErrorCategory.PROVIDER_ERROR,
                "Unexpected error during " + operation + ": " + e.getMessage(),
                config.provider(),
                operation,
                false,
                Map.of("exceptionType", e.getClass().getName()));
        OperationDiagnostics diag = new OperationDiagnostics(
                config.provider(), operation, duration, null);
        return new HyperscaleDbException(error, e).withDiagnostics(diag);
    }

    /**
     * Fail-fast: throw if a required capability is not supported (T080).
     */
    private void checkCapability(String capabilityName, String message) {
        CapabilitySet caps = providerClient.capabilities();
        if (!caps.isSupported(capabilityName)) {
            throw new HyperscaleDbException(
                    new HyperscaleDbError(
                            HyperscaleDbErrorCategory.UNSUPPORTED_CAPABILITY,
                            message,
                            config.provider(),
                            "query",
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
