// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us8;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Tag;

/**
 * Spanner binding for the portable {@link ChangeFeedConformanceTest}.
 * <p>
 * Provisioning prerequisite: a Spanner change stream must be created
 * out-of-band, e.g.
 * <pre>{@code
 *   CREATE CHANGE STREAM <collection>_changes FOR <collection>
 *     OPTIONS (value_capture_type = 'NEW_ROW');
 * }</pre>
 * The Spanner emulator does <strong>not</strong> support change streams — run
 * this test against a real Spanner instance.
 */
@Tag("spanner")
@Tag("emulator")
@Tag("changefeed")
public class SpannerChangeFeedTest extends ChangeFeedConformanceTest {

    @Override
    protected MulticloudDbClient createClient() {
        return ConformanceHarness.createClient(ProviderId.SPANNER);
    }

    @Override
    protected ResourceAddress getAddress() {
        return ConformanceHarness.defaultAddress(ProviderId.SPANNER);
    }
}
