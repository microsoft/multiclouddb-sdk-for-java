// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * MulticloudDB DynamoDB provider — implements the SPI contract using
 * Amazon DynamoDB as the backing store.
 */
module com.multiclouddb.provider.dynamo {

    requires com.multiclouddb.api;
    requires software.amazon.awssdk.services.dynamodb;
    requires software.amazon.awssdk.core;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.awscore;
    requires software.amazon.awssdk.http;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;

    // ServiceLoader registration
    provides com.multiclouddb.spi.MulticloudDbProviderAdapter
        with com.multiclouddb.provider.dynamo.DynamoProviderAdapter;

    // Nothing else exported — implementation stays internal
}
