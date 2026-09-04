// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.Capability;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic, package-private planner that converts a validated partial-update field map
 * into one conditional DynamoDB {@code UpdateItem} request.
 * <p>
 * Every user field becomes one {@code SET #fN = :vN} clause using stable ordinal aliases, so
 * reserved words and literal names such as {@code .}, {@code /}, and {@code ~} remain literal
 * and no user field name appears in the expression string. An aliased
 * {@code attribute_exists(#pk)} condition preserves the missing-document guard. Values are
 * mapped through {@link DynamoItemMapper#objectToAttributeValue(Object)} so null/map/list/scalar
 * shapes are preserved. No TTL assignment is ever added.
 * <p>
 * The completed update expression is measured in UTF-8: 4,096 bytes is accepted; 4,097 bytes is
 * rejected before any DynamoDB call with a non-retryable {@code UNSUPPORTED_CAPABILITY} tied to
 * {@code partial_update_extended_payload}.
 */
final class DynamoPartialUpdatePlanner {

    /** Maximum update-expression size in UTF-8 bytes. */
    static final int MAX_EXPRESSION_BYTES = 4096;
    static final String EXPRESSION_LIMIT_REASON = "dynamodb_update_expression_limit";
    /** Stable alias for the partition-key attribute used by the existence guard. */
    static final String PARTITION_KEY_ALIAS = "#pk";

    private DynamoPartialUpdatePlanner() {
    }

    /**
     * Builds a deterministic plan for the given validated fields.
     *
     * @throws MulticloudDbException non-retryable {@code UNSUPPORTED_CAPABILITY} if the completed
     *                               update expression exceeds 4,096 UTF-8 bytes; no DynamoDB call
     *                               is performed.
     */
    static Plan plan(Map<String, Object> fields) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        StringBuilder set = new StringBuilder("SET ");
        int i = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String nameAlias = "#f" + i;
            String valueAlias = ":v" + i;
            names.put(nameAlias, e.getKey());
            values.put(valueAlias, DynamoItemMapper.objectToAttributeValue(e.getValue()));
            if (i > 0) {
                set.append(", ");
            }
            set.append(nameAlias).append(" = ").append(valueAlias);
            i++;
        }
        names.put(PARTITION_KEY_ALIAS, DynamoConstants.ATTR_PARTITION_KEY);

        String updateExpression = set.toString();
        String conditionExpression = "attribute_exists(" + PARTITION_KEY_ALIAS + ")";
        int expressionBytes = expressionBytes(updateExpression);
        if (expressionBytes > MAX_EXPRESSION_BYTES) {
            throw limitError(expressionBytes);
        }
        return new Plan(updateExpression, conditionExpression, names, values, expressionBytes);
    }

    static int expressionBytes(String expression) {
        return expression.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Builds the non-retryable {@code UNSUPPORTED_CAPABILITY} thrown for an over-limit expression. */
    static MulticloudDbException limitError(int expressionBytes) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("reason", EXPRESSION_LIMIT_REASON);
        details.put("capability", Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
        details.put("actualExpressionBytes", String.valueOf(expressionBytes));
        details.put("maximumExpressionBytes", String.valueOf(MAX_EXPRESSION_BYTES));
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                "DynamoDB update expression exceeds the native envelope for "
                        + "partial_update_extended_payload: " + expressionBytes + " bytes (max "
                        + MAX_EXPRESSION_BYTES + ").",
                ProviderId.DYNAMO, OperationNames.UPDATE, false, details));
    }

    /** Immutable DynamoDB partial-update plan. */
    static final class Plan {
        private final String updateExpression;
        private final String conditionExpression;
        private final Map<String, String> names;
        private final Map<String, AttributeValue> values;
        private final int expressionBytes;

        Plan(String updateExpression, String conditionExpression, Map<String, String> names,
                Map<String, AttributeValue> values, int expressionBytes) {
            this.updateExpression = updateExpression;
            this.conditionExpression = conditionExpression;
            this.names = Map.copyOf(names);
            this.values = Map.copyOf(values);
            this.expressionBytes = expressionBytes;
        }

        String updateExpression() { return updateExpression; }
        String conditionExpression() { return conditionExpression; }
        Map<String, String> names() { return names; }
        Map<String, AttributeValue> values() { return values; }
        int expressionBytes() { return expressionBytes; }
    }
}
