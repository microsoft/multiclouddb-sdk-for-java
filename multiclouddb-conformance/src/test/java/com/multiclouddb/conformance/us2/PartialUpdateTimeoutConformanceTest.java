// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.internal.DefaultMulticloudDbClient;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Provider-executed conformance for partial-update timeout normalization.
 * Concrete subclasses inject native failures through the actual provider adapter.
 */
public abstract class PartialUpdateTimeoutConformanceTest {

    protected static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    protected static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "item");

    protected abstract ProviderId providerId();

    protected abstract Stream<TimeoutScenario> timeoutScenarios();

    @TestFactory
    Stream<DynamicTest> updateTimeoutsAreFullyNormalized() {
        return timeoutScenarios().map(scenario -> dynamicTest(scenario.name(), () -> {
            MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                    .provider(providerId())
                    .build();

            try (DefaultMulticloudDbClient client =
                    new DefaultMulticloudDbClient(scenario.provider(), config)) {
                MulticloudDbException result = assertThrows(MulticloudDbException.class,
                        () -> client.update(ADDRESS, KEY, Map.of("status", "SHIPPED")));

                assertEquals(MulticloudDbErrorCategory.TRANSIENT_FAILURE,
                        result.error().category());
                assertTrue(result.error().retryable());
                assertEquals(providerId(), result.error().provider());
                assertEquals(OperationNames.UPDATE, result.error().operation());
                assertSame(scenario.expectedCause(), result.getCause());
                scenario.providerAssertions().accept(result);
                scenario.verifySingleNativeAttempt().run();

                OperationDiagnostics diagnostics = result.diagnostics();
                assertNotNull(diagnostics);
                assertEquals(providerId(), diagnostics.provider());
                assertEquals(OperationNames.UPDATE, diagnostics.operation());
                assertNotNull(diagnostics.duration());
                assertFalse(diagnostics.duration().isNegative());
            }
        }));
    }

    protected record TimeoutScenario(
            String name,
            MulticloudDbProviderClient provider,
            Throwable expectedCause,
            Runnable verifySingleNativeAttempt,
            Consumer<MulticloudDbException> providerAssertions) {
    }
}
