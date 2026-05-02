// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us1b;

import com.multiclouddb.api.query.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExpressionParser} (T045).
 * <p>
 * Covers: literal types, comparison operators, logical operators (AND, OR,
 * NOT),
 * operator precedence, parentheses, function calls, IN lists, BETWEEN,
 * parameter references, dot-notation fields, malformed input, edge cases.
 */
@DisplayName("ExpressionParser")
class ExpressionParserTest {

    // ---- Simple comparisons ----

    @Test
    @DisplayName("simple equality: status = @status")
    void simpleEquality() {
        Expression ast = ExpressionParser.parse("status = @status");
        assertInstanceOf(ComparisonExpression.class, ast);
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertEquals("status", comp.field().name());
        assertEquals(ComparisonOp.EQ, comp.op());
        assertInstanceOf(Parameter.class, comp.operand());
        assertEquals("status", ((Parameter) comp.operand()).name());
    }

    @Test
    @DisplayName("not-equal operators: <> and !=")
    void notEqualOperators() {
        Expression ne1 = ExpressionParser.parse("x <> @v");
        assertInstanceOf(ComparisonExpression.class, ne1);
        assertEquals(ComparisonOp.NE, ((ComparisonExpression) ne1).op());

        Expression ne2 = ExpressionParser.parse("x != @v");
        assertInstanceOf(ComparisonExpression.class, ne2);
        assertEquals(ComparisonOp.NE, ((ComparisonExpression) ne2).op());
    }

    @Test
    @DisplayName("all comparison operators")
    void allComparisonOperators() {
        var ops = new String[] { "=", "<>", "!=", "<", ">", "<=", ">=" };
        var expected = new ComparisonOp[] {
                ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.NE,
                ComparisonOp.LT, ComparisonOp.GT, ComparisonOp.LE, ComparisonOp.GE
        };
        for (int i = 0; i < ops.length; i++) {
            Expression ast = ExpressionParser.parse("x " + ops[i] + " @v");
            assertInstanceOf(ComparisonExpression.class, ast);
            assertEquals(expected[i], ((ComparisonExpression) ast).op(),
                    "Operator " + ops[i] + " should map to " + expected[i]);
        }
    }

    // ---- Literal types ----

    @Test
    @DisplayName("string literal")
    void stringLiteral() {
        Expression ast = ExpressionParser.parse("name = 'hello'");
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertInstanceOf(Literal.class, comp.operand());
        assertEquals("hello", ((Literal) comp.operand()).value());
    }

    @Test
    @DisplayName("string literal with escaped quotes")
    void stringLiteralEscapedQuotes() {
        Expression ast = ExpressionParser.parse("name = 'it''s'");
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertEquals("it's", ((Literal) comp.operand()).value());
    }

    @Test
    @DisplayName("integer literal")
    void integerLiteral() {
        Expression ast = ExpressionParser.parse("count = 42");
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertEquals(42, ((Literal) comp.operand()).value());
    }

    @Test
    @DisplayName("negative integer")
    void negativeInteger() {
        Expression ast = ExpressionParser.parse("temp = -10");
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertEquals(-10, ((Literal) comp.operand()).value());
    }

    @Test
    @DisplayName("decimal literal")
    void decimalLiteral() {
        Expression ast = ExpressionParser.parse("price = 19.99");
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertEquals(19.99, ((Literal) comp.operand()).value());
    }

    @Test
    @DisplayName("boolean literals")
    void booleanLiterals() {
        Expression trueAst = ExpressionParser.parse("active = true");
        assertEquals(true, ((Literal) ((ComparisonExpression) trueAst).operand()).value());

        Expression falseAst = ExpressionParser.parse("active = false");
        assertEquals(false, ((Literal) ((ComparisonExpression) falseAst).operand()).value());
    }

    @Test
    @DisplayName("null literal")
    void nullLiteral() {
        Expression ast = ExpressionParser.parse("value = null");
        assertNull(((Literal) ((ComparisonExpression) ast).operand()).value());
    }

    // ---- Logical operators ----

    @Test
    @DisplayName("AND expression")
    void andExpression() {
        Expression ast = ExpressionParser.parse("a = @a AND b = @b");
        assertInstanceOf(LogicalExpression.class, ast);
        LogicalExpression logical = (LogicalExpression) ast;
        assertEquals(LogicalOp.AND, logical.op());
        assertInstanceOf(ComparisonExpression.class, logical.left());
        assertInstanceOf(ComparisonExpression.class, logical.right());
    }

    @Test
    @DisplayName("OR expression")
    void orExpression() {
        Expression ast = ExpressionParser.parse("a = @a OR b = @b");
        assertInstanceOf(LogicalExpression.class, ast);
        assertEquals(LogicalOp.OR, ((LogicalExpression) ast).op());
    }

    @Test
    @DisplayName("NOT expression")
    void notExpression() {
        Expression ast = ExpressionParser.parse("NOT active = true");
        assertInstanceOf(NotExpression.class, ast);
        assertInstanceOf(ComparisonExpression.class, ((NotExpression) ast).child());
    }

    // ---- Operator precedence ----

    @Test
    @DisplayName("AND binds tighter than OR: a=1 OR b=2 AND c=3 → OR(a=1, AND(b=2, c=3))")
    void andBindsTighterThanOr() {
        Expression ast = ExpressionParser.parse("a = 1 OR b = 2 AND c = 3");
        assertInstanceOf(LogicalExpression.class, ast);
        LogicalExpression or = (LogicalExpression) ast;
        assertEquals(LogicalOp.OR, or.op());
        assertInstanceOf(ComparisonExpression.class, or.left());
        assertInstanceOf(LogicalExpression.class, or.right());
        assertEquals(LogicalOp.AND, ((LogicalExpression) or.right()).op());
    }

    @Test
    @DisplayName("NOT binds tighter than AND: NOT a=1 AND b=2 → AND(NOT(a=1), b=2)")
    void notBindsTighterThanAnd() {
        Expression ast = ExpressionParser.parse("NOT a = 1 AND b = 2");
        assertInstanceOf(LogicalExpression.class, ast);
        LogicalExpression and = (LogicalExpression) ast;
        assertEquals(LogicalOp.AND, and.op());
        assertInstanceOf(NotExpression.class, and.left());
        assertInstanceOf(ComparisonExpression.class, and.right());
    }

    @Test
    @DisplayName("parentheses override precedence: (a=1 OR b=2) AND c=3")
    void parenthesesOverridePrecedence() {
        Expression ast = ExpressionParser.parse("(a = 1 OR b = 2) AND c = 3");
        assertInstanceOf(LogicalExpression.class, ast);
        LogicalExpression and = (LogicalExpression) ast;
        assertEquals(LogicalOp.AND, and.op());
        assertInstanceOf(LogicalExpression.class, and.left());
        assertEquals(LogicalOp.OR, ((LogicalExpression) and.left()).op());
    }

    // ---- Function calls ----

    @Test
    @DisplayName("starts_with function")
    void startsWithFunction() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        assertInstanceOf(FunctionCallExpression.class, ast);
        FunctionCallExpression func = (FunctionCallExpression) ast;
        assertEquals(PortableFunction.STARTS_WITH, func.function());
        assertEquals(2, func.arguments().size());
        assertInstanceOf(FieldRef.class, func.arguments().get(0));
        assertEquals("name", ((FieldRef) func.arguments().get(0)).name());
        assertInstanceOf(Parameter.class, func.arguments().get(1));
    }

    @Test
    @DisplayName("contains function")
    void containsFunction() {
        Expression ast = ExpressionParser.parse("contains(description, @keyword)");
        assertInstanceOf(FunctionCallExpression.class, ast);
        assertEquals(PortableFunction.CONTAINS, ((FunctionCallExpression) ast).function());
    }

    @Test
    @DisplayName("field_exists function")
    void fieldExistsFunction() {
        Expression ast = ExpressionParser.parse("field_exists(metadata)");
        assertInstanceOf(FunctionCallExpression.class, ast);
        FunctionCallExpression func = (FunctionCallExpression) ast;
        assertEquals(PortableFunction.FIELD_EXISTS, func.function());
        assertEquals(1, func.arguments().size());
    }

    @Test
    @DisplayName("string_length function")
    void stringLengthFunction() {
        Expression ast = ExpressionParser.parse("string_length(name)");
        assertInstanceOf(FunctionCallExpression.class, ast);
        assertEquals(PortableFunction.STRING_LENGTH, ((FunctionCallExpression) ast).function());
    }

    @Test
    @DisplayName("collection_size function")
    void collectionSizeFunction() {
        Expression ast = ExpressionParser.parse("collection_size(tags)");
        assertInstanceOf(FunctionCallExpression.class, ast);
        assertEquals(PortableFunction.COLLECTION_SIZE, ((FunctionCallExpression) ast).function());
    }

    // ---- IN expression ----

    @Test
    @DisplayName("IN with parameters")
    void inWithParameters() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b, @c)");
        assertInstanceOf(InExpression.class, ast);
        InExpression in = (InExpression) ast;
        assertEquals("status", in.field().name());
        assertEquals(3, in.values().size());
    }

    @Test
    @DisplayName("IN with literals")
    void inWithLiterals() {
        Expression ast = ExpressionParser.parse("category IN ('A', 'B', 'C')");
        assertInstanceOf(InExpression.class, ast);
        assertEquals(3, ((InExpression) ast).values().size());
    }

    // ---- BETWEEN expression ----

    @Test
    @DisplayName("BETWEEN with parameters")
    void betweenWithParameters() {
        Expression ast = ExpressionParser.parse("age BETWEEN @min AND @max");
        assertInstanceOf(BetweenExpression.class, ast);
        BetweenExpression between = (BetweenExpression) ast;
        assertEquals("age", between.field().name());
        assertInstanceOf(Parameter.class, between.low());
        assertInstanceOf(Parameter.class, between.high());
    }

    @Test
    @DisplayName("BETWEEN with literals")
    void betweenWithLiterals() {
        Expression ast = ExpressionParser.parse("price BETWEEN 10 AND 100");
        assertInstanceOf(BetweenExpression.class, ast);
        assertEquals(10, ((Literal) ((BetweenExpression) ast).low()).value());
        assertEquals(100, ((Literal) ((BetweenExpression) ast).high()).value());
    }

    // ---- Dot notation ----

    @Test
    @DisplayName("field with dot notation")
    void dotNotation() {
        Expression ast = ExpressionParser.parse("address.city = @city");
        ComparisonExpression comp = (ComparisonExpression) ast;
        assertEquals("address.city", comp.field().name());
    }

    @Test
    @DisplayName("deep dot notation")
    void deepDotNotation() {
        Expression ast = ExpressionParser.parse("a.b.c = @v");
        assertEquals("a.b.c", ((ComparisonExpression) ast).field().name());
    }

    // ---- Complex expressions ----

    @Test
    @DisplayName("compound: status = @status AND starts_with(name, @prefix)")
    void compoundExpression() {
        Expression ast = ExpressionParser.parse("status = @status AND starts_with(name, @prefix)");
        assertInstanceOf(LogicalExpression.class, ast);
        LogicalExpression and = (LogicalExpression) ast;
        assertEquals(LogicalOp.AND, and.op());
        assertInstanceOf(ComparisonExpression.class, and.left());
        assertInstanceOf(FunctionCallExpression.class, and.right());
    }

    @Test
    @DisplayName("triple AND chain")
    void tripleAnd() {
        Expression ast = ExpressionParser.parse("a = 1 AND b = 2 AND c = 3");
        // (a=1 AND b=2) AND c=3 — left-associative
        assertInstanceOf(LogicalExpression.class, ast);
        LogicalExpression top = (LogicalExpression) ast;
        assertInstanceOf(LogicalExpression.class, top.left());
        assertInstanceOf(ComparisonExpression.class, top.right());
    }

    @Test
    @DisplayName("nested NOT with OR")
    void nestedNotWithOr() {
        Expression ast = ExpressionParser.parse("NOT (a = 1 OR b = 2)");
        assertInstanceOf(NotExpression.class, ast);
        assertInstanceOf(LogicalExpression.class, ((NotExpression) ast).child());
    }

    // ---- Case insensitivity ----

    @Test
    @DisplayName("keywords are case-insensitive")
    void caseInsensitiveKeywords() {
        // AND, OR, NOT, IN, BETWEEN, true, false, null should all be case-insensitive
        assertDoesNotThrow(() -> ExpressionParser.parse("a = 1 and b = 2"));
        assertDoesNotThrow(() -> ExpressionParser.parse("a = 1 or b = 2"));
        assertDoesNotThrow(() -> ExpressionParser.parse("not a = 1"));
        assertDoesNotThrow(() -> ExpressionParser.parse("x in (1, 2)"));
        assertDoesNotThrow(() -> ExpressionParser.parse("x between 1 and 10"));
        assertDoesNotThrow(() -> ExpressionParser.parse("x = TRUE"));
        assertDoesNotThrow(() -> ExpressionParser.parse("x = NULL"));
    }

    // ---- Error cases ----

    @Test
    @DisplayName("expression length over limit throws")
    void expressionLengthOverLimitThrows() {
        String longExpression = "a = 1 AND ".repeat(900) + "b = 2";

        ExpressionParseException ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse(longExpression));

        assertTrue(ex.getMessage().contains("Expression length must be <="));
    }

    @Test
    @DisplayName("deeply nested expression throws")
    void deeplyNestedExpressionThrows() {
        String nestedExpression = "(".repeat(101) + "a = 1" + ")".repeat(101);

        ExpressionParseException ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse(nestedExpression));

        assertTrue(ex.getMessage().contains("Expression recursion depth must be <="));
    }

    @Test
    @DisplayName("null input throws ExpressionParseException")
    void nullInput() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse(null));
    }

    @Test
    @DisplayName("empty input throws ExpressionParseException")
    void emptyInput() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse(""));
    }

    @Test
    @DisplayName("blank input throws ExpressionParseException")
    void blankInput() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("   "));
    }

    @Test
    @DisplayName("missing operator throws")
    void missingOperator() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("field value"));
    }

    @Test
    @DisplayName("unclosed parenthesis throws")
    void unclosedParenthesis() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("(a = 1"));
    }

    @Test
    @DisplayName("unexpected token after expression throws")
    void unexpectedTokenAfterExpression() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("a = 1 b"));
    }

    @Test
    @DisplayName("empty parameter name throws")
    void emptyParameterName() {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse("a = @"));
    }

    @Test
    @DisplayName("exception has position info")
    void exceptionHasPosition() {
        ExpressionParseException ex = assertThrows(ExpressionParseException.class,
                () -> ExpressionParser.parse("a = "));
        assertTrue(ex.getPosition() >= 0, "Position should be non-negative");
        assertTrue(ex.getMessage().contains("position"), "Message should contain position info");
    }

    @ParameterizedTest
    @ValueSource(strings = { "a = ", "= 1", "a b c", "(((" })
    @DisplayName("various malformed input throws")
    void malformedInputs(String input) {
        assertThrows(ExpressionParseException.class, () -> ExpressionParser.parse(input));
    }
}
