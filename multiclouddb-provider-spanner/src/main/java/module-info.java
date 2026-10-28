// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
/**
 * MulticloudDB Spanner provider — implements the SPI contract using
 * Google Cloud Spanner as the backing store.
 */
module com.multiclouddb.provider.spanner {
    requires com.multiclouddb.api;
    requires google.cloud.spanner;
    requires google.cloud.core;
    // gax and com.google.api.apicommon are not directly imported by this module's
    // source, but google.cloud.spanner exposes types from both in its public API
    // surface (method return/parameter types). Without these requires directives the
    // JPMS compiler reports "module not in scope" errors when it resolves the
    // signatures of google.cloud.spanner methods we call.
    requires gax;
    requires com.google.api.apicommon;
    requires com.google.gson;
    requires org.slf4j;
    // ServiceLoader registration
    provides com.multiclouddb.spi.MulticloudDbProviderAdapter
        with com.multiclouddb.provider.spanner.SpannerProviderAdapter;
    // Nothing else exported — implementation stays internal
}
