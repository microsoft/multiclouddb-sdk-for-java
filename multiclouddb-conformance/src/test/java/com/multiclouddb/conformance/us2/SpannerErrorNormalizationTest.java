// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.conformance.SpannerTestSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.ExecutionException;

@Tag("spanner")
@Tag("emulator")
public class SpannerErrorNormalizationTest extends ErrorNormalizationConformanceTest {
    @Override
    protected ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @BeforeAll
    static void ensureSchema() throws ExecutionException, InterruptedException {
        SpannerTestSchema.ensureSchema("testdb", "todos");
    }
}
