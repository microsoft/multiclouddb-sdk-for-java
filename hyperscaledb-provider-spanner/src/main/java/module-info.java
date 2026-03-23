// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * HyperscaleDB Spanner provider — implements the SPI contract using
 * Google Cloud Spanner as the backing store.
 */
module com.hyperscaledb.provider.spanner {

    requires com.hyperscaledb.api;
    requires google.cloud.spanner;
    requires google.cloud.core;
    requires gax;
    requires com.google.api.apicommon;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;

    // ServiceLoader registration
    provides com.hyperscaledb.spi.HyperscaleDbProviderAdapter
        with com.hyperscaledb.provider.spanner.SpannerProviderAdapter;

    // Nothing else exported — implementation stays internal
}
