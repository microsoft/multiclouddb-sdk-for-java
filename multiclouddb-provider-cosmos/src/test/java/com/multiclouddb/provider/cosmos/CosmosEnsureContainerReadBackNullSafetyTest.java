// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static source-level guard for the round-6 NPE fix on
 * {@code CosmosProviderClient#ensureContainer}'s read-back-and-reject path.
 *
 * <p>Background: the Cosmos Java SDK's
 * {@code ChangeFeedPolicy.getRetentionDurationForAllVersionsAndDeletesPolicy()}
 * returns {@code null} when the policy is non-AVAD (LATEST_VERSION — the
 * historical pre-AVAD default). The earlier code only null-checked
 * {@code activePolicy}, not the AVAD-getter's result, so a container
 * created with the LATEST_VERSION default threw raw
 * {@link NullPointerException} from {@code Map.of(...)} (rejects null
 * values) and {@code activeRetention.toString()} — leaking a provider-
 * specific exception type through the portable surface instead of the
 * documented {@code UNSUPPORTED_CAPABILITY(reason=extended_retention_not_enacted)}
 * envelope.
 *
 * <p>The test asserts the source carries the two structural guards that
 * close the bug:
 * <ul>
 *   <li>An {@code activeAvadRetention} local declared as the result of
 *       coalescing both null axes ({@code activePolicy == null ? null : avadGetter()}).</li>
 *   <li>{@code String.valueOf(activeAvadRetention)} on the {@code Map.of}
 *       arguments so the map never sees null.</li>
 *   <li>A {@code "capability"} key in the providerDetails map so observability
 *       grouping by {@code providerDetails.capability} no longer holes.</li>
 * </ul>
 *
 * <p>A live mockito-based test is intentionally avoided here to keep the
 * unit suite emulator-free; the behavioural deferral is documented in the
 * Planning Addendum (Cosmos emulator caps AVAD retention at 10 minutes).
 */
class CosmosEnsureContainerReadBackNullSafetyTest {

    @Test
    @DisplayName("ensureContainer's read-back path uses null-safe coalescing + String.valueOf")
    void readBackPathIsNullSafe() throws IOException {
        Path sourceFile = Paths.get("src/main/java/com/multiclouddb/provider/cosmos/CosmosProviderClient.java");
        if (!Files.exists(sourceFile)) {
            sourceFile = Paths.get("multiclouddb-provider-cosmos/src/main/java/com/multiclouddb/provider/cosmos/CosmosProviderClient.java");
        }
        String source = Files.readString(sourceFile);

        assertTrue(source.contains("Duration activeAvadRetention = activePolicy == null"),
                "ensureContainer must declare activeAvadRetention by coalescing the activePolicy-null axis; "
                        + "without this, a container with a non-AVAD ChangeFeedPolicy NPEs in the throw path.");
        assertTrue(source.contains("getRetentionDurationForAllVersionsAndDeletesPolicy()"),
                "must use the AVAD-specific retention getter (the only one applicable when AVAD policy is set)");
        assertTrue(source.contains("String.valueOf(activeAvadRetention)"),
                "Map.of rejects null values; the throw path must wrap activeAvadRetention with String.valueOf "
                        + "so a non-AVAD existing container surfaces UNSUPPORTED_CAPABILITY rather than NPE.");
        assertTrue(source.contains("\"capability\", Capability.EXTENDED_CHANGE_FEED_HISTORY"),
                "providerDetails MUST carry the 'capability' key for portable observability grouping "
                        + "(matches the factory + Dynamo SPI defence-in-depth gates).");
    }
}
