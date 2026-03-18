// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.samples.riskplatform.tenant;

import com.hyperscaledb.api.*;
import com.hyperscaledb.samples.riskplatform.model.Models;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-tenant management layer for the Risk Analysis Platform.
 * <p>
 * Implements <b>database-per-tenant</b> isolation using the portable
 * {@link ResourceAddress} model. Each tenant gets a logical database
 * (e.g. {@code "acme-capital-risk-db"}) with collections for portfolios,
 * positions, risk metrics, and alerts.
 * <p>
 * The SDK's provider layer handles the mapping from logical
 * {@code (database, collection)} pairs to physical storage:
 * <ul>
 * <li><b>Cosmos DB</b>: separate database + container</li>
 * <li><b>DynamoDB</b>: composed {@code database__collection} table name
 * (DynamoDB has no native database concept)</li>
 * </ul>
 * <p>
 * This class uses <b>no provider-specific logic</b> — all addressing
 * goes through {@link ResourceAddress} and the provider handles the rest.
 * A shared "admin" database stores tenant registry metadata.
 */
public class TenantManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Admin database for the tenant registry. */
    private static final String ADMIN_DB = "riskplatform-admin";
    private static final String TENANTS_COLLECTION = "tenants";

    private final HyperscaleDbClient client;
    private final Map<String, String> tenantDatabases = new ConcurrentHashMap<>();

    public TenantManager(HyperscaleDbClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    // ── Tenant lifecycle ────────────────────────────────────────────────────

    /**
     * Register a new tenant. Creates the tenant record in the admin database.
     */
    public JsonNode createTenant(String tenantId, String name, String tier, String industry) {
        ResourceAddress addr = new ResourceAddress(ADMIN_DB, TENANTS_COLLECTION);
        JsonNode doc = Models.tenant(tenantId, name, tier, industry);
        Key key = Key.of(tenantId, tenantId);
        client.upsert(addr, key, doc);
        tenantDatabases.put(tenantId, tenantId + "-risk-db");
        return doc;
    }

    /**
     * List all registered tenants.
     */
    public List<JsonNode> listTenants() {
        ResourceAddress addr = new ResourceAddress(ADMIN_DB, TENANTS_COLLECTION);
        QueryRequest query = QueryRequest.builder().pageSize(100).build();
        QueryPage page = client.query(addr, query);
        List<JsonNode> tenants = new ArrayList<>(page.items());

        // Refresh local cache
        for (JsonNode t : tenants) {
            String id = t.path("tenantId").asText();
            String db = t.path("databaseName").asText();
            if (!id.isEmpty() && !db.isEmpty()) {
                tenantDatabases.put(id, db);
            }
        }
        return tenants;
    }

    /**
     * Get a single tenant by ID.
     */
    public JsonNode getTenant(String tenantId) {
        ResourceAddress addr = new ResourceAddress(ADMIN_DB, TENANTS_COLLECTION);
        Key key = Key.of(tenantId, tenantId);
        return client.read(addr, key);
    }

    // ── Per-tenant resource addressing ──────────────────────────────────────

    /**
     * Get a {@link ResourceAddress} for a collection within a specific tenant's
     * isolated database.
     * <p>
     * Returns a portable {@code ResourceAddress(database, collection)} — the
     * provider layer handles mapping to physical storage (e.g. Cosmos DB uses
     * separate databases, DynamoDB composes {@code database__collection} table
     * names).
     *
     * @param tenantId   the tenant identifier
     * @param collection the logical collection (e.g. "portfolios", "positions")
     * @return resource address scoped to the tenant's database
     */
    public ResourceAddress addressFor(String tenantId, String collection) {
        String database = tenantDatabases.computeIfAbsent(tenantId,
                id -> id + "-risk-db");
        return new ResourceAddress(database, collection);
    }

    /**
     * Upsert a document into a tenant-scoped collection.
     */
    public void upsert(String tenantId, String collection, Key key, JsonNode document) {
        client.upsert(addressFor(tenantId, collection), key, document);
    }

    /**
     * Read a document from a tenant-scoped collection.
     */
    public JsonNode read(String tenantId, String collection, Key key) {
        return client.read(addressFor(tenantId, collection), key);
    }

    /**
     * Delete a document from a tenant-scoped collection.
     */
    public void delete(String tenantId, String collection, Key key) {
        client.delete(addressFor(tenantId, collection), key);
    }

    /**
     * Query a tenant-scoped collection.
     */
    public List<JsonNode> query(String tenantId, String collection, QueryRequest request) {
        ResourceAddress addr = addressFor(tenantId, collection);
        QueryPage page = client.query(addr, request);
        return new ArrayList<>(page.items());
    }

    /**
     * Query a tenant-scoped collection (all items, no filter).
     */
    public List<JsonNode> listAll(String tenantId, String collection) {
        return query(tenantId, collection,
                QueryRequest.builder().pageSize(200).build());
    }

    /**
     * Query a tenant-scoped collection, scoped to a single partition key value.
     * <p>
     * Uses {@link QueryRequest#partitionKey()} so each provider applies its
     * native efficient mechanism (Cosmos&nbsp;DB {@code setPartitionKey},
     * DynamoDB/Spanner {@code partitionKey} equality).
     *
     * @param tenantId     the tenant identifier
     * @param collection   the logical collection
     * @param partitionKey the partition key value to scope the query to
     * @return items in the partition
     */
    public List<JsonNode> queryByPartition(String tenantId, String collection,
            String partitionKey) {
        return query(tenantId, collection,
                QueryRequest.builder()
                        .partitionKey(partitionKey)
                        .pageSize(200)
                        .build());
    }

    /**
     * Query a tenant-scoped collection, scoped to a single partition key value,
     * with an additional filter expression.
     *
     * @param tenantId     the tenant identifier
     * @param collection   the logical collection
     * @param partitionKey the partition key value to scope the query to
     * @param request      additional query parameters (expression, parameters,
     *                     pageSize etc.)
     * @return matching items in the partition
     */
    public List<JsonNode> queryByPartition(String tenantId, String collection,
            String partitionKey, QueryRequest request) {
        QueryRequest scoped = QueryRequest.builder()
                .partitionKey(partitionKey)
                .expression(request.expression())
                .nativeExpression(request.nativeExpression())
                .parameters(request.parameters())
                .pageSize(request.pageSize() != null ? request.pageSize() : 200)
                .continuationToken(request.continuationToken())
                .build();
        return query(tenantId, collection, scoped);
    }

    // ── Convenience accessors ───────────────────────────────────────────────

    public HyperscaleDbClient getClient() {
        return client;
    }

    public Set<String> getKnownTenantIds() {
        return Collections.unmodifiableSet(tenantDatabases.keySet());
    }
}
