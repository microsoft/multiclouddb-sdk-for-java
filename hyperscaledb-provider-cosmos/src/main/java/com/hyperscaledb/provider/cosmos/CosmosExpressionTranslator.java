package com.hyperscaledb.provider.cosmos;

import com.hyperscaledb.api.query.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates a portable expression AST into Cosmos DB SQL syntax.
 * <p>
 * Cosmos SQL conventions:
 * <ul>
 * <li>Fields are prefixed with {@code c.} (container alias)</li>
 * <li>Parameters use {@code @paramName} notation</li>
 * <li>Functions: STARTSWITH, CONTAINS, IS_DEFINED, LENGTH, ARRAY_LENGTH</li>
 * </ul>
 */
public final class CosmosExpressionTranslator implements ExpressionTranslator {

    @Override
    public TranslatedQuery translate(Expression expression, Map<String, Object> parameters, String container) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> namedParams = new LinkedHashMap<>();

        translateExpression(expression, where, parameters, namedParams);

        String whereClause = where.toString();
        String fullQuery = "SELECT * FROM c WHERE " + whereClause;

        return TranslatedQuery.withNamedParameters(fullQuery, whereClause, namedParams);
    }

    private void translateExpression(Expression expr, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        if (expr instanceof ComparisonExpression comp) {
            sb.append("c.").append(comp.field().name());
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
            sb.append("c.").append(in.field().name()).append(" IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0)
                    sb.append(", ");
                appendValue(in.values().get(i), sb, srcParams, outParams);
            }
            sb.append(')');

        } else if (expr instanceof BetweenExpression between) {
            sb.append("c.").append(between.field().name()).append(" BETWEEN ");
            appendValue(between.low(), sb, srcParams, outParams);
            sb.append(" AND ");
            appendValue(between.high(), sb, srcParams, outParams);
        }
    }

    private void translateFunction(FunctionCallExpression func, StringBuilder sb,
            Map<String, Object> srcParams,
            Map<String, Object> outParams) {
        switch (func.function()) {
            case STARTS_WITH -> {
                sb.append("STARTSWITH(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case CONTAINS -> {
                sb.append("CONTAINS(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case FIELD_EXISTS -> {
                // IS_DEFINED(c.field)
                sb.append("IS_DEFINED(");
                appendFunctionArgs(func, sb, srcParams, outParams);
                sb.append(')');
            }
            case STRING_LENGTH -> {
                sb.append("LENGTH(");
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
                sb.append("c.").append(field.name());
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
            sb.append("null");
        } else if (lit.value() instanceof String s) {
            sb.append('\'').append(s.replace("'", "''")).append('\'');
        } else {
            sb.append(lit.value());
        }
    }
}
