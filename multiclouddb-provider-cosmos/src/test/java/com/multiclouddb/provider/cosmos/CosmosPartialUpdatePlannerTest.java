// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        CosmosPartialUpdatePlanner.Plan plan = CosmosPartialUpdatePlanner.plan(fields(10));

        assertEquals(10, plan.setCount());
    }

    @Test
    void elevenFieldsAreRejected() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosPartialUpdatePlanner.plan(fields(11)));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertFalse(ex.error().retryable());
        assertEquals("11", ex.error().providerDetails().get("actualFields"));
        assertEquals("10", ex.error().providerDetails().get("maximumFields"));
    }

    private static Map<String, Object> fields(int count) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            fields.put("field" + i, i);
        }
        return fields;
    }
}
