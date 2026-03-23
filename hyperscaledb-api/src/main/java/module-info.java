// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * HyperscaleDB API module — the portable client interface for CRUD and query
 * operations across cloud database providers.
 * <p>
 * App developers depend on the {@code com.hyperscaledb.api} and
 * {@code com.hyperscaledb.api.query} packages. The SPI package is only
 * visible to provider modules, and the internal package is not exported.
 */
module com.hyperscaledb.api {

    // Public API — what app developers use
    exports com.hyperscaledb.api;
    exports com.hyperscaledb.api.query;

    // SPI — only to provider modules, not to app code
    exports com.hyperscaledb.spi to
        com.hyperscaledb.provider.cosmos,
        com.hyperscaledb.provider.dynamo,
        com.hyperscaledb.provider.spanner;

    // com.hyperscaledb.api.internal — not exported

    // ServiceLoader discovery
    uses com.hyperscaledb.spi.HyperscaleDbProviderAdapter;

    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
}
