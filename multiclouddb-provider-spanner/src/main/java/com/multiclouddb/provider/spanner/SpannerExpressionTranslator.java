// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.query.BetweenExpression;
import com.multiclouddb.api.query.ComparisonExpression;
import com.multiclouddb.api.query.Expression;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.api.query.FieldRef;
import com.multiclouddb.api.query.FunctionCallExpression;
import com.multiclouddb.api.query.InExpression;
import com.multiclouddb.api.query.Literal;
import com.multiclouddb.api.query.LogicalExpression;
import com.multiclouddb.api.query.NotExpression;
import com.multiclouddb.api.query.Parameter;
import com.multiclouddb.api.query.TranslatedQuery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates a portable expression AST into Google Cloud Spanner GoogleSQL
 * syntax.
 * <p>
 * Spanner GoogleSQL conventions:
 * <ul>
 * <li>Bare field names (no prefix)</li>
 * <li>Parameters use {@code @paramName} notation</li>
 * <li>Functions: STARTS_WITH, STRPOS(field,value)&gt;0 for contains,
 * IS NOT NULL for field_exists, CHAR_LENGTH, ARRAY_LENGTH</li>
 * </ul>
 */
public final class SpannerExpressionTranslator implements ExpressionTranslator {

    @Override
    public TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> namedParams = new LinkedHashMap<>();

        translateExpression(expression, where, parameters, namedParams);

        String whereClause = where.toString();
        String fullQuery = "SELECT * FROM " + container + " WHERE " + whereClause;

        return TranslatedQuery.withNamedParameters(fullQuery, whereClause, namedParams);
    }

    private void translateExpression(Expression expr, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
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
            // Wrap in parentheses for consistency with sibling translators.
            // GoogleSQL parses the un-parenthesised form correctly (its
            // operator precedence binds BETWEEN tighter than logical AND), so
            // this is not strictly required here — but uniform output across
            // providers simplifies cross-provider debugging and query stitching.
            sb.append('(').append(between.field().name()).append(" BETWEEN ");
            appendValue(between.low(), sb, srcParams, outParams);
            sb.append(" AND ");
            appendValue(between.high(), sb, srcParams, outParams);
            sb.append(')');
        }
    }

    private void translateFunction(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        switch (func.function()) {
            case STARTS_WITH -> {
                sb.append("STARTS_WITH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case CONTAINS -> {
                // STRPOS(field, value) > 0
                if (func.arguments().size() >= 2) {
                    sb.append("STRPOS(");
                    appendFunctionArgs(func, sb, srcParams, outParams);
                    sb.append(") > 0");
                }
            }
            case FIELD_EXISTS -> {
                // field IS NOT NULL
                if (!func.arguments().isEmpty() && func.arguments().get(0) instanceof FieldRef field) {
                    sb.append(field.name()).append(" IS NOT NULL");
                }
            }
            case STRING_LENGTH -> {
                sb.append("CHAR_LENGTH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case COLLECTION_SIZE -> {
                sb.append("ARRAY_LENGTH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
        }
    }

    private void appendFunctionArgs(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
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
            Map<String, Object> outParams) {
        if (value instanceof Parameter param) {
            String paramName = "@" + param.name();
            sb.append(paramName);
            if (srcParams != null && srcParams.containsKey(param.name())) {
                outParams.put(paramName, srcParams.get(param.name()));
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
