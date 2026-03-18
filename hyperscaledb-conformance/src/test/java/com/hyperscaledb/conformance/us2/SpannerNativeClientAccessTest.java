// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import com.google.cloud.spanner.Spanner;
import org.junit.jupiter.api.Tag;

/**
 * Spanner native client access conformance test.
 */
@Tag("spanner")
@Tag("emulator")
public class SpannerNativeClientAccessTest extends NativeClientAccessConformanceTest {

    @Override
    protected ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    protected Class<?> expectedNativeClientType() {
        return Spanner.class;
    }
}
