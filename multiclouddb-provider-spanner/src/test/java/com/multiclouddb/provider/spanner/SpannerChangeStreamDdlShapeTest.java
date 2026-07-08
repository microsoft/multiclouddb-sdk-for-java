// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static source-level guard for the change-stream DDL the SDK emits in
 * {@link SpannerProviderClient#ensureContainer}. We deliberately do not run
 * against a live emulator because (a) the Spanner emulator silently ignores
 * {@code OPTIONS (retention_period = …)} on {@code CREATE CHANGE STREAM}
 * (so behavioural conformance is deferred to live-cloud nightly per the
 * Planning Addendum) and (b) any future refactor of the DDL string here
 * must remain caught by a green test in the build.
 *
 * <p>The DDL must contain both:
 * <ul>
 *   <li>{@code value_capture_type = 'NEW_ROW'} — required by the SDK's own
 *       reader ({@code SpannerChangeFeedReader#extractValues}) for correct
 *       full-row UPDATE payloads. Spanner's default is
 *       {@code OLD_AND_NEW_VALUES}, under which {@code mods.new_values}
 *       carries only the columns mutated by each write — silently dropping
 *       unchanged columns on every UPDATE event.</li>
 *   <li>{@code retention_period = '<value>'} — the opt-in's whole purpose.</li>
 * </ul>
 */
class SpannerChangeStreamDdlShapeTest {

    @Test
    @DisplayName("CREATE CHANGE STREAM DDL must include value_capture_type='NEW_ROW' AND retention_period")
    void ddlIncludesNewRowCaptureTypeAndRetentionPeriod() throws IOException {
        Path sourceFile = Paths.get("src/main/java/com/multiclouddb/provider/spanner/SpannerProviderClient.java");
        if (!Files.exists(sourceFile)) {
            // Allow running from repo root, too
            sourceFile = Paths.get("multiclouddb-provider-spanner/src/main/java/com/multiclouddb/provider/spanner/SpannerProviderClient.java");
        }
        String source = Files.readString(sourceFile);

        // Capture both the DDL prefix and the option clauses
        assertTrue(source.contains("CREATE CHANGE STREAM "),
                "SpannerProviderClient must emit a CREATE CHANGE STREAM DDL");
        assertTrue(source.contains("value_capture_type = 'NEW_ROW'"),
                "SpannerProviderClient's CREATE CHANGE STREAM DDL MUST set value_capture_type = 'NEW_ROW'; "
                        + "without it, Spanner defaults to OLD_AND_NEW_VALUES — under which mods.new_values "
                        + "carries only modified columns, silently dropping unchanged columns on every UPDATE. "
                        + "This is also the DDL the documented out-of-band provisioning prescribes "
                        + "(SpannerChangeFeedReader#extractValues, configuration.md, plan.md).");
        assertTrue(source.contains("retention_period = '"),
                "SpannerProviderClient's CREATE CHANGE STREAM DDL MUST emit retention_period");
    }
}
