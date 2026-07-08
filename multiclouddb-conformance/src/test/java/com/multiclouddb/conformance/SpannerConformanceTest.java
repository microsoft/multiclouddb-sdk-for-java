// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.ExecutionException;

/**
 * Spanner conformance test running against the Spanner Emulator.
 * <p>
 * Prerequisites:
 * <ul>
 * <li>Spanner Emulator running on localhost:9010 (gRPC) / localhost:9020
 * (REST)</li>
 * </ul>
 * <p>
 * The test auto-creates the instance, database, and table in
 * {@code @BeforeAll} via the shared {@link SpannerTestSchema} helper.
 */
@Tag("spanner")
@Tag("emulator")
class SpannerConformanceTest extends CrudConformanceTests {

    private static final String DATABASE_ID = "testdb";
    private static final String TABLE = "todos";

    @BeforeAll
    static void ensureInstanceAndDatabase() throws ExecutionException, InterruptedException {
        SpannerTestSchema.ensureSchema(DATABASE_ID, TABLE);
    }

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", SpannerTestSchema.PROJECT_ID)
                .connection("instanceId", SpannerTestSchema.INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", SpannerTestSchema.EMULATOR_HOST)
                .build();
        return MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE_ID, TABLE);
    }
}
