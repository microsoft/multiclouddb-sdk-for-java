// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/**
 * MulticloudDB API module — the portable client interface for CRUD and query
 * operations across cloud database providers.
 * <p>
 * App developers depend on the {@code com.multiclouddb.api} and
 * {@code com.multiclouddb.api.query} packages. The SPI package is only
 * visible to provider modules and the conformance test module, and the
 * internal package is not exported.
 */
module com.multiclouddb.api {

    // Public API — what app developers use
    exports com.multiclouddb.api;
    exports com.multiclouddb.api.query;
    exports com.multiclouddb.api.changefeed;

    // Internal change-feed helpers exported only to provider modules.
    exports com.multiclouddb.api.changefeed.internal to
        com.multiclouddb.provider.cosmos,
        com.multiclouddb.provider.dynamo,
        com.multiclouddb.provider.spanner,
        com.multiclouddb.conformance;

    // SPI — only to provider modules and the conformance test suite, not to app code.
    // NOTE: multiclouddb-conformance currently has no module-info.java and runs on
    // the unnamed (classpath) module, so this qualified export is a forward declaration
    // for when the conformance module is eventually placed on the module path.
    exports com.multiclouddb.spi to
        com.multiclouddb.provider.cosmos,
        com.multiclouddb.provider.dynamo,
        com.multiclouddb.provider.spanner,
        com.multiclouddb.conformance;

    // com.multiclouddb.api.internal — not exported

    // ServiceLoader discovery
    uses com.multiclouddb.spi.MulticloudDbProviderAdapter;

    // transitive: JsonNode appears in the public API surface (MulticloudDbClient.read(),
    // QueryPage.items()), so app modules must be able to use it without adding their
    // own `requires com.fasterxml.jackson.databind` directive.
    requires transitive com.fasterxml.jackson.databind;
    requires org.slf4j;
}
