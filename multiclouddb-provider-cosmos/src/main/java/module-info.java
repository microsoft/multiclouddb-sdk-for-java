// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
/**
 * MulticloudDB Cosmos DB provider — implements the SPI contract using
 * Azure Cosmos DB as the backing store.
 */
module com.multiclouddb.provider.cosmos {
    requires com.multiclouddb.api;
    requires com.azure.cosmos;
    requires com.azure.identity;
    requires org.slf4j;
    // ServiceLoader registration
    provides com.multiclouddb.spi.MulticloudDbProviderAdapter
        with com.multiclouddb.provider.cosmos.CosmosProviderAdapter;
    // Nothing else exported — implementation stays internal
}
