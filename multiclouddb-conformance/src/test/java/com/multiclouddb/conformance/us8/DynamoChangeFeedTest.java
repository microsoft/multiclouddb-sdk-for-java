// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us8;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Tag;

/**
 * DynamoDB binding for the portable {@link ChangeFeedConformanceTest}.
 * <p>
 * Provisioning prerequisite: the table must have
 * {@code StreamSpecification.StreamEnabled=true} with a {@code StreamViewType}
 * of {@code NEW_AND_OLD_IMAGES} (or {@code NEW_IMAGE} if old values are not
 * required). See {@code docs/configuration.md} — <em>Change Feed
 * Provisioning</em>.
 */
@Tag("dynamo")
@Tag("emulator")
@Tag("changefeed")
public class DynamoChangeFeedTest extends ChangeFeedConformanceTest {

    @Override
    protected MulticloudDbClient createClient() {
        return ConformanceHarness.createClient(ProviderId.DYNAMO);
    }

    @Override
    protected ResourceAddress getAddress() {
        return ConformanceHarness.defaultAddress(ProviderId.DYNAMO);
    }
}
