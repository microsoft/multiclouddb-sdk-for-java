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
import com.multiclouddb.api.query.Expression;
import com.multiclouddb.api.query.ExpressionParseException;
import com.multiclouddb.api.query.ExpressionParser;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.api.query.ExpressionValidationException;
import com.multiclouddb.api.query.ExpressionValidator;
import com.multiclouddb.api.query.TranslatedQuery;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

            // Portable expression pipeline: parse → validate → translate → execute.
            // Under strict LCD, provider-native passthrough is no longer supported.
            if (query.expression() != null && !query.expression().isBlank()) {
                if (expressionTranslator == null) {
                    throw new MulticloudDbException(new MulticloudDbError(
                            MulticloudDbErrorCategory.INVALID_REQUEST,
                            "Portable query expressions require a translator configured on the provider",
                            config.provider(), OperationNames.QUERY, false, Map.of()));
                }
                checkCapability(Capability.PORTABLE_QUERY_EXPRESSION,
                        "Portable query expressions are not supported by provider " + config.provider().id());

                Expression ast = ExpressionParser.parse(query.expression());
                ExpressionValidator.validate(ast, query.parameters());

                TranslatedQuery translated = expressionTranslator.translate(
                        ast, query.parameters(), address.collection());
                LOG.debug("query translated: address={}, native={}", address, translated.queryString());
                page = providerClient.queryWithTranslation(address, translated, query, options);
            } else {
                // No filter expression — provider scans the supplied partition.
                page = providerClient.query(address, query, options);
            }

            // Apply client-side maxResults cap to enforce the SDK-level total cap.
            page = applyMaxResultsCap(page, query);

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
     * Truncate the page items to satisfy {@link QueryRequest#maxResults()}.
     * <p>The maxResults cap is enforced client-side and applies to a single
     * page invocation: if the provider returned more items than the cap, the
     * extra items are dropped and the continuation token is cleared so the
     * caller does not request another page.
     * Callers iterating across pages should treat the cap as a per-call upper
     * bound; tracking the total across pages is the caller's responsibility.
     */
    private QueryPage applyMaxResultsCap(QueryPage page, QueryRequest query) {
        Integer cap = query.maxResults();
        if (cap == null) {
            return page;
        }
        List<Map<String, Object>> items = page.items();
        if (items.size() <= cap) {
            return page;
        }
        List<Map<String, Object>> truncated = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            truncated.add(items.get(i));
        }
        return new QueryPage(truncated, null);
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
        CapabilitySet caps = providerClient.capabilities();
        if (!caps.isSupported(capabilityName)) {
            throw new MulticloudDbException(
                    new MulticloudDbError(
                            MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                            message,
                            config.provider(),
                            "query",
                            false,
                            Map.of("capability", capabilityName)));
        }
    }
}
