// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Common assertions for provider-native partial-update result-size rejection. */
interface PartialUpdateResultLimitConformanceTest {

    MulticloudDbClient createResultLimitClient();

    ResourceAddress resultLimitAddress();

    PartialUpdateResultLimitScenario resultLimitScenario();

    @Test
    @Order(32)
    @DisplayName("native result-item size overflow is normalized and atomic")
    default void resultItemSizeOverflowIsNormalizedAndLeavesItemUnchanged()
            throws Exception {
        PartialUpdateResultLimitScenario scenario = resultLimitScenario();
        try (scenario) {
            scenario.seed().run();
            try (MulticloudDbClient client = createResultLimitClient()) {
                MulticloudDbException failure = assertThrows(
                        MulticloudDbException.class,
                        () -> client.update(
                                resultLimitAddress(),
                                scenario.key(),
                                scenario.updateFields()));

                assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                        failure.error().category(), failure.error().toString());
                assertEquals("update", failure.error().operation());
                assertFalse(failure.error().retryable());
                assertEquals(scenario.expectedReason(),
                        failure.error().providerDetails().get("reason"));
                assertEquals(scenario.maximumResultBytes(),
                        failure.error().providerDetails().get("maximumResultBytes"));
            }
            scenario.verifyUnchanged().run();
        }
    }

    @FunctionalInterface
    interface CheckedAction {
        void run() throws Exception;
    }

    record PartialUpdateResultLimitScenario(
            MulticloudDbKey key,
            Map<String, Object> updateFields,
            String expectedReason,
            String maximumResultBytes,
            CheckedAction seed,
            CheckedAction verifyUnchanged,
            CheckedAction cleanup) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            cleanup.run();
        }
    }
}