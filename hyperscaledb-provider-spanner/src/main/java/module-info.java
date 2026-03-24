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
    // gax and com.google.api.apicommon are not directly imported by this module's
    // source, but google.cloud.spanner exposes types from both in its public API
    // surface (method return/parameter types). Without these requires directives the
    // JPMS compiler reports "module not in scope" errors when it resolves the
    // signatures of google.cloud.spanner methods we call.
    requires gax;
    requires com.google.api.apicommon;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
    // ServiceLoader registration
    provides com.hyperscaledb.spi.HyperscaleDbProviderAdapter
        with com.hyperscaledb.provider.spanner.SpannerProviderAdapter;
    // Nothing else exported — implementation stays internal
}
