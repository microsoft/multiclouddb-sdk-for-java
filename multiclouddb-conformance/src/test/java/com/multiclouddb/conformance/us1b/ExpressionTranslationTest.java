// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us1b;

import com.multiclouddb.api.query.*;
import com.multiclouddb.provider.cosmos.CosmosExpressionTranslator;
import com.multiclouddb.provider.dynamo.DynamoExpressionTranslator;
import com.multiclouddb.provider.spanner.SpannerExpressionTranslator;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for expression translators across all three providers (T046).
 * <p>
 * Verifies that the same portable expression AST produces correct
 * provider-specific SQL for Cosmos DB, DynamoDB (PartiQL), and Spanner
 * (GoogleSQL).
 */
@DisplayName("Expression Translation")
class ExpressionTranslationTest {

    private final CosmosExpressionTranslator cosmos = new CosmosExpressionTranslator();
    private final DynamoExpressionTranslator dynamo = new DynamoExpressionTranslator();
    private final SpannerExpressionTranslator spanner = new SpannerExpressionTranslator();

    private static final String TABLE = "items";
    private static final Map<String, Object> EMPTY_PARAMS = Map.of();

    // ---- Simple comparison with parameter ----

    @Test
    @DisplayName("simple equality: status = @status")
    void simpleEquality() {
        Expression ast = ExpressionParser.parse("status = @status");
        Map<String, Object> params = Map.of("status", "active");

        TranslatedQuery cosmosResult = cosmos.translate(ast, params, TABLE);
        assertEquals("SELECT * FROM c WHERE c.status = @status", cosmosResult.queryString());
        assertEquals("c.status = @status", cosmosResult.whereClause());
        assertEquals(Map.of("@status", "active"), cosmosResult.namedParameters());
        assertTrue(cosmosResult.positionalParameters().isEmpty());

        TranslatedQuery dynamoResult = dynamo.translate(ast, params, TABLE);
        assertEquals("SELECT * FROM \"items\" WHERE status = ?", dynamoResult.queryString());
        assertEquals("status = ?", dynamoResult.whereClause());
        assertEquals(List.of("active"), dynamoResult.positionalParameters());
        assertTrue(dynamoResult.namedParameters().isEmpty());

        TranslatedQuery spannerResult = spanner.translate(ast, params, TABLE);
        assertEquals("SELECT * FROM items WHERE status = @status", spannerResult.queryString());
        assertEquals("status = @status", spannerResult.whereClause());
        assertEquals(Map.of("@status", "active"), spannerResult.namedParameters());
    }

    // ---- All comparison operators ----

    @Test
    @DisplayName("all comparison operators produce correct symbols")
    void allComparisonOps() {
        for (ComparisonOp op : ComparisonOp.values()) {
            String symbol = op.symbol();
            Expression ast = ExpressionParser.parse("x " + symbol + " @v");
            Map<String, Object> params = Map.of("v", 42);

            TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
            assertTrue(cosRes.whereClause().contains(symbol),
                    "Cosmos should contain " + symbol);

            TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
            assertTrue(dynRes.whereClause().contains(symbol),
                    "Dynamo should contain " + symbol);

            TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
            assertTrue(spnRes.whereClause().contains(symbol),
                    "Spanner should contain " + symbol);
        }
    }

    // ---- Literal comparison ----

    @Test
    @DisplayName("string literal: name = 'hello'")
    void stringLiteral() {
        Expression ast = ExpressionParser.parse("name = 'hello'");

        assertEquals("c.name = 'hello'", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("name = 'hello'", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("name = 'hello'", spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("numeric literal: count = 42")
    void numericLiteral() {
        Expression ast = ExpressionParser.parse("count = 42");

        assertEquals("c.count = 42", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("count = 42", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("count = 42", spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("boolean literal: active = true")
    void booleanLiteral() {
        Expression ast = ExpressionParser.parse("active = true");

        assertEquals("c.active = true", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("active = true", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("active = true", spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("null literal: value = null")
    void nullLiteral() {
        Expression ast = ExpressionParser.parse("value = null");

        assertEquals("c.value = null", cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("value = NULL", dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("value = NULL", spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    // ---- Logical operators ----

    @Test
    @DisplayName("AND expression")
    void andExpression() {
        Expression ast = ExpressionParser.parse("a = @a AND b = @b");
        Map<String, Object> params = Map.of("a", 1, "b", 2);

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("(c.a = @a AND c.b = @b)", cosRes.whereClause());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("(a = ? AND b = ?)", dynRes.whereClause());
        assertEquals(List.of(1, 2), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertEquals("(a = @a AND b = @b)", spnRes.whereClause());
    }

    @Test
    @DisplayName("OR expression")
    void orExpression() {
        Expression ast = ExpressionParser.parse("a = @a OR b = @b");
        Map<String, Object> params = Map.of("a", 1, "b", 2);

        assertEquals("(c.a = @a OR c.b = @b)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("(a = ? OR b = ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertEquals("(a = @a OR b = @b)",
                spanner.translate(ast, params, TABLE).whereClause());
    }

    @Test
    @DisplayName("NOT expression")
    void notExpression() {
        Expression ast = ExpressionParser.parse("NOT active = true");

        assertEquals("NOT (c.active = true)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("NOT (active = true)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("NOT (active = true)",
                spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    // ---- Complex logical with precedence ----

    @Test
    @DisplayName("AND + OR precedence: a=1 OR b=2 AND c=3")
    void andOrPrecedence() {
        Expression ast = ExpressionParser.parse("a = 1 OR b = 2 AND c = 3");

        // Parser: a=1 OR (b=2 AND c=3) → LogicalExpression(OR, a=1,
        // LogicalExpression(AND, b=2, c=3))
        String cosWhere = cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause();
        assertEquals("(c.a = 1 OR (c.b = 2 AND c.c = 3))", cosWhere);
    }

    // ---- Function calls ----

    @Test
    @DisplayName("starts_with function")
    void startsWithFunction() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix)");
        Map<String, Object> params = Map.of("prefix", "abc");

        assertEquals("STARTSWITH(c.name, @prefix)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("begins_with(name, ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertEquals("STARTS_WITH(name, @prefix)",
                spanner.translate(ast, params, TABLE).whereClause());
    }

    @Test
    @DisplayName("contains function")
    void containsFunction() {
        Expression ast = ExpressionParser.parse("contains(description, @kw)");
        Map<String, Object> params = Map.of("kw", "test");

        assertEquals("CONTAINS(c.description, @kw)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("contains(description, ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertEquals("STRPOS(description, @kw) > 0",
                spanner.translate(ast, params, TABLE).whereClause());
    }

    @Test
    @DisplayName("field_exists function")
    void fieldExistsFunction() {
        Expression ast = ExpressionParser.parse("field_exists(metadata)");

        assertEquals("IS_DEFINED(c.metadata)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("metadata IS NOT MISSING",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("metadata IS NOT NULL",
                spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("string_length function")
    void stringLengthFunction() {
        Expression ast = ExpressionParser.parse("string_length(name)");

        assertEquals("LENGTH(c.name)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("char_length(name)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("CHAR_LENGTH(name)",
                spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("collection_size function")
    void collectionSizeFunction() {
        Expression ast = ExpressionParser.parse("collection_size(tags)");

        assertEquals("ARRAY_LENGTH(c.tags)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("size(tags)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("ARRAY_LENGTH(tags)",
                spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    // ---- IN expression ----

    @Test
    @DisplayName("IN with parameters")
    void inWithParameters() {
        Expression ast = ExpressionParser.parse("status IN (@a, @b)");
        Map<String, Object> params = Map.of("a", "open", "b", "closed");

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("c.status IN (@a, @b)", cosRes.whereClause());
        assertEquals(Map.of("@a", "open", "@b", "closed"), cosRes.namedParameters());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("status IN (?, ?)", dynRes.whereClause());
        assertEquals(List.of("open", "closed"), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertEquals("status IN (@a, @b)", spnRes.whereClause());
    }

    @Test
    @DisplayName("IN with string literals")
    void inWithLiterals() {
        Expression ast = ExpressionParser.parse("category IN ('X', 'Y')");

        assertEquals("c.category IN ('X', 'Y')",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("category IN ('X', 'Y')",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("category IN ('X', 'Y')",
                spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    // ---- BETWEEN expression ----

    @Test
    @DisplayName("BETWEEN with parameters")
    void betweenWithParameters() {
        Expression ast = ExpressionParser.parse("age BETWEEN @min AND @max");
        Map<String, Object> params = Map.of("min", 18, "max", 65);

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("(c.age BETWEEN @min AND @max)", cosRes.whereClause());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("(age BETWEEN ? AND ?)", dynRes.whereClause());
        assertEquals(List.of(18, 65), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertEquals("(age BETWEEN @min AND @max)", spnRes.whereClause());
    }

    @Test
    @DisplayName("BETWEEN with numeric literals")
    void betweenWithLiterals() {
        Expression ast = ExpressionParser.parse("price BETWEEN 10 AND 100");

        assertEquals("(c.price BETWEEN 10 AND 100)",
                cosmos.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("(price BETWEEN 10 AND 100)",
                dynamo.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
        assertEquals("(price BETWEEN 10 AND 100)",
                spanner.translate(ast, EMPTY_PARAMS, TABLE).whereClause());
    }

    @Test
    @DisplayName("BETWEEN combined with trailing AND keeps inner parens")
    void betweenWithTrailingAnd() {
        // This is the exact form that motivated the parens wrap on Cosmos:
        // without the wrapping parens, the Cosmos NoSQL parser greedily binds
        // BETWEEN's inner AND together with the trailing logical AND, raising
        // BadRequest "Syntax error, incorrect syntax near 'AND'". A future
        // refactor that drops the parens because the standalone form parses
        // fine on every backend would re-introduce that production bug — this
        // test pins the parenthesised contract for the failure shape itself.
        Expression ast = ExpressionParser.parse(
                "age BETWEEN @lo AND @hi AND marker = @m");
        Map<String, Object> params = Map.of("lo", 18, "hi", 65, "m", "x");

        // Outer parens wrap the whole logical AND — the translators emit parens
        // around every binary AND expression. The inner (BETWEEN ...) parens are
        // what this test pins: without them, Cosmos NoSQL's parser greedily
        // binds BETWEEN's inner AND with the trailing logical AND.
        assertEquals("((c.age BETWEEN @lo AND @hi) AND c.marker = @m)",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("((age BETWEEN ? AND ?) AND marker = ?)",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertEquals("((age BETWEEN @lo AND @hi) AND marker = @m)",
                spanner.translate(ast, params, TABLE).whereClause());
    }

    // ---- Dot notation ----

    @Test
    @DisplayName("dot notation field")
    void dotNotationField() {
        Expression ast = ExpressionParser.parse("address.city = @city");
        Map<String, Object> params = Map.of("city", "Amsterdam");

        assertEquals("c.address.city = @city",
                cosmos.translate(ast, params, TABLE).whereClause());
        assertEquals("address.city = ?",
                dynamo.translate(ast, params, TABLE).whereClause());
        assertEquals("address.city = @city",
                spanner.translate(ast, params, TABLE).whereClause());
    }

    // ---- Full query string format ----

    @Test
    @DisplayName("Cosmos full query has SELECT * FROM c WHERE prefix")
    void cosmosFullQueryFormat() {
        Expression ast = ExpressionParser.parse("x = 1");
        TranslatedQuery result = cosmos.translate(ast, EMPTY_PARAMS, TABLE);
        assertTrue(result.queryString().startsWith("SELECT * FROM c WHERE "));
    }

    @Test
    @DisplayName("DynamoDB full query has double-quoted table name")
    void dynamoFullQueryFormat() {
        Expression ast = ExpressionParser.parse("x = 1");
        TranslatedQuery result = dynamo.translate(ast, EMPTY_PARAMS, "my_table");
        assertTrue(result.queryString().startsWith("SELECT * FROM \"my_table\" WHERE "));
    }

    @Test
    @DisplayName("Spanner full query has bare table name")
    void spannerFullQueryFormat() {
        Expression ast = ExpressionParser.parse("x = 1");
        TranslatedQuery result = spanner.translate(ast, EMPTY_PARAMS, "my_table");
        assertTrue(result.queryString().startsWith("SELECT * FROM my_table WHERE "));
    }

    // ---- Parameter propagation ----

    @Test
    @DisplayName("Cosmos passes named parameters from input map")
    void cosmosNamedParameterPropagation() {
        Expression ast = ExpressionParser.parse("a = @val AND b = @other");
        Map<String, Object> params = Map.of("val", "hello", "other", 42);

        TranslatedQuery result = cosmos.translate(ast, params, TABLE);
        assertEquals("hello", result.namedParameters().get("@val"));
        assertEquals(42, result.namedParameters().get("@other"));
    }

    @Test
    @DisplayName("Dynamo produces positional parameters in expression order")
    void dynamoPositionalOrder() {
        Expression ast = ExpressionParser.parse("a = @first AND b = @second");
        Map<String, Object> params = Map.of("first", "A", "second", "B");

        TranslatedQuery result = dynamo.translate(ast, params, TABLE);
        assertEquals(List.of("A", "B"), result.positionalParameters());
    }

    // ---- Complex combined queries ----

    @Test
    @DisplayName("function + comparison AND chain")
    void functionPlusComparisonAndChain() {
        Expression ast = ExpressionParser.parse("starts_with(name, @prefix) AND status = @status");
        Map<String, Object> params = Map.of("prefix", "abc", "status", "active");

        TranslatedQuery cosRes = cosmos.translate(ast, params, TABLE);
        assertEquals("(STARTSWITH(c.name, @prefix) AND c.status = @status)", cosRes.whereClause());

        TranslatedQuery dynRes = dynamo.translate(ast, params, TABLE);
        assertEquals("(begins_with(name, ?) AND status = ?)", dynRes.whereClause());
        assertEquals(List.of("abc", "active"), dynRes.positionalParameters());

        TranslatedQuery spnRes = spanner.translate(ast, params, TABLE);
        assertEquals("(STARTS_WITH(name, @prefix) AND status = @status)", spnRes.whereClause());
    }
}
