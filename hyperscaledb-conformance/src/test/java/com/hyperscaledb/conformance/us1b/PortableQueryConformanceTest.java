package com.hyperscaledb.conformance.us1b;

import com.hyperscaledb.api.query.*;
import com.hyperscaledb.provider.cosmos.CosmosExpressionTranslator;
import com.hyperscaledb.provider.dynamo.DynamoExpressionTranslator;
import com.hyperscaledb.provider.spanner.SpannerExpressionTranslator;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-provider portable query conformance tests (T047).
 * <p>
 * Verifies that the same portable expression can be translated by all providers
 * without error and produces structurally valid output.
 */
@DisplayName("Portable Query Conformance")
class PortableQueryConformanceTest {

    private final ExpressionTranslator[] translators = {
            new CosmosExpressionTranslator(),
            new DynamoExpressionTranslator(),
            new SpannerExpressionTranslator()
    };

    private final String[] providerNames = { "Cosmos", "DynamoDB", "Spanner" };

    private void assertAllTranslate(String expression, Map<String, Object> params, String table) {
        Expression ast = ExpressionParser.parse(expression);
        for (int i = 0; i < translators.length; i++) {
            TranslatedQuery result = translators[i].translate(ast, params, table);
            assertNotNull(result, providerNames[i] + " result should not be null");
            assertFalse(result.queryString().isBlank(), providerNames[i] + " queryString should not be blank");
            assertFalse(result.whereClause().isBlank(), providerNames[i] + " whereClause should not be blank");
            assertTrue(result.queryString().contains("WHERE"),
                    providerNames[i] + " queryString should contain WHERE");
            assertTrue(result.queryString().contains(result.whereClause()),
                    providerNames[i] + " queryString should contain the whereClause");
        }
    }

    // ---- Conformance: every expression must translate for all providers ----

    @Test
    @DisplayName("simple equality translates across all providers")
    void simpleEquality() {
        assertAllTranslate("status = @status", Map.of("status", "active"), "items");
    }

    @Test
    @DisplayName("all comparison operators translate across all providers")
    void allComparisonOps() {
        for (ComparisonOp op : ComparisonOp.values()) {
            assertAllTranslate("field " + op.symbol() + " @v", Map.of("v", 1), "items");
        }
    }

    @Test
    @DisplayName("AND expression translates across all providers")
    void andExpression() {
        assertAllTranslate("a = @a AND b = @b", Map.of("a", 1, "b", 2), "items");
    }

    @Test
    @DisplayName("OR expression translates across all providers")
    void orExpression() {
        assertAllTranslate("a = @a OR b = @b", Map.of("a", 1, "b", 2), "items");
    }

    @Test
    @DisplayName("NOT expression translates across all providers")
    void notExpression() {
        assertAllTranslate("NOT active = true", Map.of(), "items");
    }

    @Test
    @DisplayName("starts_with function translates across all providers")
    void startsWithFunction() {
        assertAllTranslate("starts_with(name, @p)", Map.of("p", "x"), "items");
    }

    @Test
    @DisplayName("contains function translates across all providers")
    void containsFunction() {
        assertAllTranslate("contains(name, @p)", Map.of("p", "x"), "items");
    }

    @Test
    @DisplayName("field_exists function translates across all providers")
    void fieldExistsFunction() {
        assertAllTranslate("field_exists(metadata)", Map.of(), "items");
    }

    @Test
    @DisplayName("string_length function translates across all providers")
    void stringLengthFunction() {
        assertAllTranslate("string_length(name)", Map.of(), "items");
    }

    @Test
    @DisplayName("collection_size function translates across all providers")
    void collectionSizeFunction() {
        assertAllTranslate("collection_size(tags)", Map.of(), "items");
    }

    @Test
    @DisplayName("IN expression translates across all providers")
    void inExpression() {
        assertAllTranslate("status IN (@a, @b)", Map.of("a", "x", "b", "y"), "items");
    }

    @Test
    @DisplayName("BETWEEN expression translates across all providers")
    void betweenExpression() {
        assertAllTranslate("age BETWEEN @min AND @max", Map.of("min", 1, "max", 100), "items");
    }

    @Test
    @DisplayName("dot notation fields translate across all providers")
    void dotNotation() {
        assertAllTranslate("address.city = @city", Map.of("city", "NYC"), "items");
    }

    @Test
    @DisplayName("complex compound expression translates across all providers")
    void complexCompound() {
        assertAllTranslate(
                "status = @status AND starts_with(name, @prefix) OR age BETWEEN @min AND @max",
                Map.of("status", "active", "prefix", "abc", "min", 18, "max", 65),
                "items");
    }

    @Test
    @DisplayName("nested NOT with parentheses translates across all providers")
    void nestedNotWithParens() {
        assertAllTranslate("NOT (a = 1 OR b = 2)", Map.of(), "items");
    }

    @Test
    @DisplayName("function in AND chain translates across all providers")
    void functionInAndChain() {
        assertAllTranslate(
                "field_exists(metadata) AND contains(name, @kw) AND status = @s",
                Map.of("kw", "test", "s", "active"),
                "items");
    }
}
