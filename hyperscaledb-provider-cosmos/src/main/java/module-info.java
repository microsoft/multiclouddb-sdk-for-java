// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * HyperscaleDB Cosmos DB provider — implements the SPI contract using
 * Azure Cosmos DB as the backing store.
 */
module com.hyperscaledb.provider.cosmos {

    requires com.hyperscaledb.api;
    requires com.azure.cosmos;
    requires com.azure.identity;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;

    // ServiceLoader registration
    provides com.hyperscaledb.spi.HyperscaleDbProviderAdapter
        with com.hyperscaledb.provider.cosmos.CosmosProviderAdapter;

    // Nothing else exported — implementation stays internal
}
