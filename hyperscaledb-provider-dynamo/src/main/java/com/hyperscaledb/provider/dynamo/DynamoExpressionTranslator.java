// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.hyperscaledb.provider.dynamo;

import com.hyperscaledb.api.query.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a portable expression AST into DynamoDB PartiQL syntax.
 * <p>
 * PartiQL conventions:
 * <ul>
 * <li>Table names are double-quoted: {@code "tableName"}</li>
 * <li>Uses positional {@code ?} parameters in query order</li>
 * <li>Functions: begins_with, contains, IS NOT MISSING, char_length, size</li>
 * </ul>
 */
public final class DynamoExpressionTranslator implements ExpressionTranslator {

    @Override
    public TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container) {
        StringBuilder where = new StringBuilder();
        List<Object> positionalParams = new ArrayList<>();

        translateExpression(expression, where, parameters, positionalParams);

        String whereClause = where.toString();
        String fullQuery = "SELECT * FROM \"" + container + "\" WHERE " + whereClause;

        return TranslatedQuery.withPositionalParameters(fullQuery, whereClause, positionalParams);
    }

    private void translateExpression(Expression expr, StringBuilder sb,
            Map<String, Object> srcParams,
            List<Object> outParams) {
        if (expr instanceof ComparisonExpression comp) {
            sb.append(comp.field().name());
            sb.append(' ').append(comp.op().symbol()).append(' ');
            appendValue(comp.operand(), sb, srcParams, outParams);

        } else if (expr instanceof LogicalExpression logical) {
            sb.append('(');
            translateExpression(logical.left(), sb, srcParams, outParams);
            sb.append(' ').append(logical.op().name()).append(' ');
            translateExpression(logical.right(), sb, srcParams, outParams);
            sb.append(')');

        } else if (expr instanceof NotExpression not) {
            sb.append("NOT (");
            translateExpression(not.child(), sb, srcParams, outParams);
            sb.append(')');

        } else if (expr instanceof FunctionCallExpression func) {
            translateFunction(func, sb, srcParams, outParams);

        } else if (expr instanceof InExpression in) {
            sb.append(in.field().name()).append(" IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0)
                    sb.append(", ");
                appendValue(in.values().get(i), sb, srcParams, outParams);
            }
            sb.append(')');

        } else if (expr instanceof BetweenExpression between) {
            sb.append(between.field().name()).append(" BETWEEN ");
            appendValue(between.low(), sb, srcParams, outParams);
            sb.append(" AND ");
            appendValue(between.high(), sb, srcParams, outParams);
        }
    }

    private void translateFunction(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            List<Object> outParams) {
        switch (func.function()) {
            case STARTS_WITH -> {
                sb.append("begins_with(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case CONTAINS -> {
                sb.append("contains(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case FIELD_EXISTS -> {
                // field IS NOT MISSING
                if (!func.arguments().isEmpty() && func.arguments().get(0) instanceof FieldRef field) {
                    sb.append(field.name()).append(" IS NOT MISSING");
                }
            }
            case STRING_LENGTH -> {
                sb.append("char_length(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case COLLECTION_SIZE -> {
                sb.append("size(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
        }
    }

    private void appendFunctionArgs(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            List<Object> outParams) {
        for (int i = 0; i < func.arguments().size(); i++) {
            if (i > 0)
                sb.append(", ");
            Object arg = func.arguments().get(i);
            if (arg instanceof FieldRef field) {
                sb.append(field.name());
            } else {
                appendValue(arg, sb, srcParams, outParams);
            }
        }
    }

    private void appendValue(Object value, StringBuilder sb,
            Map<String, Object> srcParams,
            List<Object> outParams) {
        if (value instanceof Parameter param) {
            sb.append('?');
            if (srcParams != null && srcParams.containsKey(param.name())) {
                outParams.add(srcParams.get(param.name()));
            }
        } else if (value instanceof Literal lit) {
            appendLiteral(lit, sb);
        }
    }

    private void appendLiteral(Literal lit, StringBuilder sb) {
        if (lit.value() == null) {
            sb.append("NULL");
        } else if (lit.value() instanceof String s) {
            sb.append('\'').append(s.replace("'", "''")).append('\'');
        } else {
            sb.append(lit.value());
        }
    }
}
