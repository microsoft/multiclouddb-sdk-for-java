// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * HyperscaleDB DynamoDB provider — implements the SPI contract using
 * Amazon DynamoDB as the backing store.
 */
module com.hyperscaledb.provider.dynamo {

    requires com.hyperscaledb.api;
    requires software.amazon.awssdk.services.dynamodb;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.awscore;
    requires software.amazon.awssdk.http;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;

    // ServiceLoader registration
    provides com.hyperscaledb.spi.HyperscaleDbProviderAdapter
        with com.hyperscaledb.provider.dynamo.DynamoProviderAdapter;

    // Nothing else exported — implementation stays internal
}
