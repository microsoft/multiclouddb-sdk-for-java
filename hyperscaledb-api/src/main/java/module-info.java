// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * HyperscaleDB API module — the portable client interface for CRUD and query
 * operations across cloud database providers.
 * <p>
 * App developers depend on the {@code com.hyperscaledb.api} and
 * {@code com.hyperscaledb.api.query} packages. The SPI package is only
 * visible to provider modules and the conformance test module, and the
 * internal package is not exported.
 */
module com.hyperscaledb.api {

    // Public API — what app developers use
    exports com.hyperscaledb.api;
    exports com.hyperscaledb.api.query;

    // SPI — only to provider modules and the conformance test suite, not to app code.
    // NOTE: hyperscaledb-conformance currently has no module-info.java and runs on
    // the unnamed (classpath) module, so this qualified export is a forward declaration
    // for when the conformance module is eventually placed on the module path.
    exports com.hyperscaledb.spi to
        com.hyperscaledb.provider.cosmos,
        com.hyperscaledb.provider.dynamo,
        com.hyperscaledb.provider.spanner,
        com.hyperscaledb.conformance;

    // com.hyperscaledb.api.internal — not exported

    // ServiceLoader discovery
    uses com.hyperscaledb.spi.HyperscaleDbProviderAdapter;

    // transitive: JsonNode appears in the public API surface (HyperscaleDbClient.read(),
    // QueryPage.items()), so app modules must be able to use it without adding their
    // own `requires com.fasterxml.jackson.databind` directive.
    requires transitive com.fasterxml.jackson.databind;
    requires org.slf4j;
}
