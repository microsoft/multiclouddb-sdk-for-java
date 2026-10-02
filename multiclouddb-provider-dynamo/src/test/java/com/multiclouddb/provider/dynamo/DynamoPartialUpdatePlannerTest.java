// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoPartialUpdatePlannerTest {

    @Test
    void aliasesLiteralNamesAndPreservesStructuredValues() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("size", "large");
        fields.put(".", null);
        fields.put("/", Map.of("nested", true));
        fields.put("~", List.of(1, 2));

        DynamoPartialUpdatePlanner.Plan plan = DynamoPartialUpdatePlanner.plan(fields);

        assertEquals("SET #f0 = :v0, #f1 = :v1, #f2 = :v2, #f3 = :v3",
                plan.updateExpression());
        assertFalse(plan.updateExpression().contains("size"));
        assertEquals(".", plan.names().get("#f0"));
        assertEquals("/", plan.names().get("#f1"));
        assertEquals("size", plan.names().get("#f2"));
        assertEquals("~", plan.names().get("#f3"));
        assertEquals(DynamoConstants.ATTR_PARTITION_KEY,
                plan.names().get(DynamoPartialUpdatePlanner.PARTITION_KEY_ALIAS));
        assertEquals("attribute_exists(#pk)", plan.conditionExpression());

        assertEquals(AttributeValue.Type.NUL, plan.values().get(":v0").type());
        assertEquals(AttributeValue.Type.M, plan.values().get(":v1").type());
        assertEquals(AttributeValue.Type.S, plan.values().get(":v2").type());
        assertEquals(AttributeValue.Type.L, plan.values().get(":v3").type());
    }

    @Test
    void planDoesNotDependOnCallerMapIterationOrder() {
        Map<String, Object> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("beta", 2);
        reverseOrder.put("alpha", 1);
        Map<String, Object> forwardOrder = new LinkedHashMap<>();
        forwardOrder.put("alpha", 1);
        forwardOrder.put("beta", 2);

        DynamoPartialUpdatePlanner.Plan reversePlan =
                DynamoPartialUpdatePlanner.plan(reverseOrder);
        DynamoPartialUpdatePlanner.Plan forwardPlan =
                DynamoPartialUpdatePlanner.plan(forwardOrder);

        assertEquals("alpha", reversePlan.names().get("#f0"));
        assertEquals("beta", reversePlan.names().get("#f1"));
        assertEquals(reversePlan.names(), forwardPlan.names());
        assertEquals(reversePlan.values(), forwardPlan.values());
    }

    @Test
    void portableTenFieldRequestStaysBelowNativeExpressionLimit() {
        DynamoPartialUpdatePlanner.Plan plan = DynamoPartialUpdatePlanner.plan(fields(10));

        assertEquals(112, plan.expressionBytes());
        assertTrue(plan.expressionBytes() < DynamoPartialUpdatePlanner.MAX_EXPRESSION_BYTES);
    }

    @Test
    void expressionByteMeasurementUsesUtf8Exactly() {
        assertEquals(4096, DynamoPartialUpdatePlanner.expressionBytes("a".repeat(4096)));
        assertEquals(4097, DynamoPartialUpdatePlanner.expressionBytes("a".repeat(4097)));
        assertEquals(4, DynamoPartialUpdatePlanner.expressionBytes("éé"));
    }

    @Test
    void largestGeneratedExpressionBelowLimitPasses() {
        DynamoPartialUpdatePlanner.Plan plan = DynamoPartialUpdatePlanner.plan(fields(287));
        assertEquals(4087, plan.expressionBytes());
    }

    @Test
    void firstGeneratedExpressionAboveLimitIsTypedAndComplete() {
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> DynamoPartialUpdatePlanner.plan(fields(288)));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        assertFalse(ex.error().retryable());
        assertFalse(ex.error().providerDetails().containsKey("capability"));
        assertEquals("4102", ex.error().providerDetails().get("actualExpressionBytes"));
        assertEquals("4096", ex.error().providerDetails().get("maximumExpressionBytes"));
    }

    private static Map<String, Object> fields(int count) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            fields.put("field" + i, i);
        }
        return fields;
    }
}
