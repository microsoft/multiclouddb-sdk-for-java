// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.conformance.us2;

import com.hyperscaledb.api.ProviderId;
import org.junit.jupiter.api.Tag;

@Tag("spanner")
@Tag("emulator")
public class SpannerDiagnosticsTest extends DiagnosticsConformanceTest {
    @Override
    protected ProviderId providerId() {
        return ProviderId.SPANNER;
    }
}
