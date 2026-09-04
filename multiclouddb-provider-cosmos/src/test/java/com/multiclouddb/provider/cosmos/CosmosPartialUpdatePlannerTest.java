// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.models.CosmosItemOperationType;
import com.azure.cosmos.models.PartitionKey;
import com.multiclouddb.api.Capability;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmosPartialUpdatePlannerTest {

    @Test
    void escapesEachFieldAsOneRfc6901Segment() {
        assertEquals("/plain", CosmosPartialUpdatePlanner.escapePath("plain"));
        assertEquals("/~1", CosmosPartialUpdatePlanner.escapePath("/"));
        assertEquals("/~0", CosmosPartialUpdatePlanner.escapePath("~"));
        assertEquals("/a~1b~0c", CosmosPartialUpdatePlanner.escapePath("a/b~c"));
    }

    @Test
    void tenFieldsUseOneDirectPatch() {
        CosmosPartialUpdatePlanner.Plan plan = CosmosPartialUpdatePlanner.plan(
                "item", new PartitionKey("pk"), fields(10));

        assertTrue(plan.isDirect());
        assertEquals(1, plan.patchChunks().size());
        assertNull(plan.batch());
        assertEquals(10, plan.setCount());
        assertEquals(1, plan.operationCount());
    }

    @Test
    void elevenFieldsUseOneTwoOperationBatchForTheSameItem() {
        CosmosPartialUpdatePlanner.Plan plan = CosmosPartialUpdatePlanner.plan(
                "item", new PartitionKey("pk"), fields(11));

        assertFalse(plan.isDirect());
        assertNotNull(plan.batch());
        assertEquals(2, plan.batch().getOperations().size());
        assertEquals(2, plan.operationCount());
        assertTrue(plan.serializedBytes() > 0);
        plan.batch().getOperations().forEach(operation -> {
            assertEquals("item", operation.getId());
            assertEquals(CosmosItemOperationType.PATCH, operation.getOperationType());
        });
    }

    @Test
    void exactNativeEnvelopeBoundariesPass() {
        CosmosPartialUpdatePlanner.validateBatchEnvelope(
                CosmosPartialUpdatePlanner.MAX_BATCH_OPERATIONS,
                CosmosPartialUpdatePlanner.MAX_BATCH_BYTES);
    }

    @Test
    void operationCountOverNativeEnvelopeIsTypedAndComplete() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosPartialUpdatePlanner.validateBatchEnvelope(
                        CosmosPartialUpdatePlanner.MAX_BATCH_OPERATIONS + 1, 1234));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        assertFalse(ex.error().retryable());
        assertEquals(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD,
                ex.error().providerDetails().get("capability"));
        assertEquals("101", ex.error().providerDetails().get("actualOperations"));
        assertEquals("1234", ex.error().providerDetails().get("actualBytes"));
    }

    @Test
    void bytesOverNativeEnvelopeAreRejected() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosPartialUpdatePlanner.validateBatchEnvelope(
                        2, CosmosPartialUpdatePlanner.MAX_BATCH_BYTES + 1));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        assertEquals(String.valueOf(CosmosPartialUpdatePlanner.MAX_BATCH_BYTES + 1),
                ex.error().providerDetails().get("actualBytes"));
    }

    private static Map<String, Object> fields(int count) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            fields.put("field" + i, i);
        }
        return fields;
    }
}
