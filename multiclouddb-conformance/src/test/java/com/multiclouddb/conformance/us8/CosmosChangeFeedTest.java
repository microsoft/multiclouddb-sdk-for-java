// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us8;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Tag;

/**
 * Cosmos DB binding for the portable {@link ChangeFeedConformanceTest}.
 * <p>
 * Provisioning prerequisite: the container must be created with
 * {@code changeFeedPolicy = AllVersionsAndDeletes} so DELETE events surface.
 * Without that the conformance suite's DELETE assertions will fail. See
 * {@code docs/configuration.md} — <em>Change Feed Provisioning</em>.
 */
@Tag("cosmos")
@Tag("emulator")
@Tag("changefeed")
public class CosmosChangeFeedTest extends ChangeFeedConformanceTest {

    @Override
    protected MulticloudDbClient createClient() {
        return ConformanceHarness.createClient(ProviderId.COSMOS);
    }

    @Override
    protected ResourceAddress getAddress() {
        return ConformanceHarness.defaultAddress(ProviderId.COSMOS);
    }
}
