package com.hyperscaledb.api.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a parsed expression AST before translation.
 * <p>
 * Checks that all {@code @param} references in the expression have
 * corresponding entries in the provided parameters map.
 */
public final class ExpressionValidator {

    private ExpressionValidator() {
    }

    /**
     * Validate an expression tree against the provided parameters.
     *
     * @param expression the parsed expression AST
     * @param parameters the parameter map (may be null or empty)
     * @throws ExpressionValidationException if validation fails
     */
    public static void validate(Expression expression, Map<String, Object> parameters) {
        List<String> errors = new ArrayList<>();
        collectErrors(expression, parameters, errors);
        if (!errors.isEmpty()) {
            throw new ExpressionValidationException(errors);
        }
    }

    private static void collectErrors(Expression expr, Map<String, Object> params, List<String> errors) {
        if (expr instanceof ComparisonExpression comp) {
            checkValue(comp.operand(), params, errors);
        } else if (expr instanceof LogicalExpression logical) {
            collectErrors(logical.left(), params, errors);
            collectErrors(logical.right(), params, errors);
        } else if (expr instanceof NotExpression not) {
            collectErrors(not.child(), params, errors);
        } else if (expr instanceof FunctionCallExpression func) {
            for (Object arg : func.arguments()) {
                checkValue(arg, params, errors);
            }
        } else if (expr instanceof InExpression in) {
            for (Object val : in.values()) {
                checkValue(val, params, errors);
            }
        } else if (expr instanceof BetweenExpression between) {
            checkValue(between.low(), params, errors);
            checkValue(between.high(), params, errors);
        }
    }

    private static void checkValue(Object value, Map<String, Object> params, List<String> errors) {
        if (value instanceof Parameter param) {
            if (params == null || !params.containsKey(param.name())) {
                errors.add("Parameter '@" + param.name()
                        + "' is referenced in the expression but not provided in the parameters map");
            }
        }
    }
}
