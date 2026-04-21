// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.Tag;

/**
 * Cosmos DB sort-key ordering conformance test.
 * <p>
 * Verifies that Cosmos DB queries return items in ascending sort-key order
 * (ORDER BY c.id ASC) by default — matching DynamoDB's implicit sort-key
 * ordering within a partition.
 * <p>
 * By default targets the Cosmos DB Emulator on https://localhost:8081. Override
 * via system properties:
 * <ul>
 *   <li>{@code -Dcosmos.endpoint=https://your-account.documents.azure.com:443/}</li>
 *   <li>{@code -Dcosmos.key=YOUR_KEY} (omit to use DefaultAzureCredential)</li>
 *   <li>{@code -Dcosmos.database=riskplatform} and {@code -Dcosmos.container=tenants}</li>
 * </ul>
 */
@Tag("cosmos")
class CosmosSortKeyOrderingTest extends SortKeyOrderingConformanceTest {

    private static final String ENDPOINT = System.getProperty(
            "cosmos.endpoint", "https://localhost:8081");
    private static final String KEY = System.getProperty(
            "cosmos.key",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
    private static final String DATABASE = System.getProperty(
            "cosmos.database", "todoapp");
    private static final String CONTAINER = System.getProperty(
            "cosmos.container", "todos");

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", ENDPOINT);
        if (KEY != null && !KEY.isBlank()) {
            builder.connection("key", KEY);
        }
        return MulticloudDbClientFactory.create(builder.build());
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, CONTAINER);
    }

    @Override
    protected String sortKeyFieldName() {
        // Cosmos DB stores the sort key as the document "id" field
        return "id";
    }
}
