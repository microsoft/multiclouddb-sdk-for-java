package com.hyperscaledb.conformance.us1b;

import com.hyperscaledb.api.query.*;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExpressionValidator} (supplementary to T045-T046).
 */
@DisplayName("ExpressionValidator")
class ExpressionValidatorTest {

    @Test
    @DisplayName("valid parameters pass validation")
    void validParameters() {
        Expression ast = ExpressionParser.parse("status = @status AND age > @age");
        Map<String, Object> params = Map.of("status", "active", "age", 18);
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    @Test
    @DisplayName("missing parameter throws ExpressionValidationException")
    void missingParameter() {
        Expression ast = ExpressionParser.parse("status = @status AND age > @age");
        Map<String, Object> params = Map.of("status", "active");
        // "age" is missing
        ExpressionValidationException ex = assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, params));
        assertFalse(ex.getErrors().isEmpty());
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("age")));
    }

    @Test
    @DisplayName("no parameters required when only literals used")
    void noParametersRequired() {
        Expression ast = ExpressionParser.parse("status = 'active'");
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, Map.of()));
    }

    @Test
    @DisplayName("null parameters map with parameter reference throws")
    void nullParametersMap() {
        Expression ast = ExpressionParser.parse("status = @status");
        assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, null));
    }

    @Test
    @DisplayName("function parameter references are validated")
    void functionParameterValidation() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        Map<String, Object> params = Map.of("prefix", "abc");
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    @Test
    @DisplayName("function with missing parameter throws")
    void functionMissingParameter() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        assertThrows(ExpressionValidationException.class,
                () -> ExpressionValidator.validate(ast, Map.of()));
    }

    @Test
    @DisplayName("IN expression parameter references are validated")
    void inParameterValidation() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        Map<String, Object> params = Map.of("a", "x", "b", "y");
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }

    @Test
    @DisplayName("BETWEEN parameter references are validated")
    void betweenParameterValidation() {
        Expression ast = ExpressionParser.parse("age BETWEEN @min AND @max");
        Map<String, Object> params = Map.of("min", 1, "max", 100);
        assertDoesNotThrow(() -> ExpressionValidator.validate(ast, params));
    }
}
