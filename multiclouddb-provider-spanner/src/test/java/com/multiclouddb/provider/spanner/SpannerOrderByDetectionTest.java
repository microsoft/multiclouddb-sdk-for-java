// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.SortDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the SQL post-processing helpers in {@link SpannerProviderClient}:
 * {@link SpannerProviderClient#hasOrderByClause(String)},
 * {@link SpannerProviderClient#containsAggregate(String)}, and
 * {@link SpannerProviderClient#appendResultSetControl(String, QueryRequest)}.
 * <p>
 * Ensures the SDK does not append a default/tiebreaker {@code ORDER BY} clause
 * to caller-supplied SQL (e.g., a raw GoogleSQL expression passed via
 * {@code QueryRequest.expression()}) when one is already present, and that
 * aggregate queries do not receive the non-grouped-column tiebreaker that
 * GoogleSQL would reject.
 */
class SpannerOrderByDetectionTest {

    @Test
    @DisplayName("returns true for trailing ORDER BY clause")
    void trailingOrderBy() {
        assertTrue(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE status = @s ORDER BY createdAt DESC"));
    }

    @Test
    @DisplayName("returns true regardless of case")
    void caseInsensitive() {
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t order by name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t Order By name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER BY name"));
    }

    @Test
    @DisplayName("returns true with multiple/newline whitespace between ORDER and BY")
    void whitespaceVariants() {
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER  BY name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER\nBY name"));
        assertTrue(SpannerProviderClient.hasOrderByClause("SELECT * FROM t ORDER\tBY name"));
    }

    @Test
    @DisplayName("returns false for SQL without ORDER BY")
    void noOrderBy() {
        assertFalse(SpannerProviderClient.hasOrderByClause("SELECT * FROM items"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE status = @s"));
    }

    @Test
    @DisplayName("returns false for column names containing the substring 'order'")
    void substringFalsePositiveGuard() {
        // Word boundaries should not match identifiers like order_id, MY_ORDER_BY
        assertFalse(SpannerProviderClient.hasOrderByClause("SELECT order_id FROM items"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT my_order, by_field FROM items"));
        assertFalse(SpannerProviderClient.hasOrderByClause("SELECT orderby_column FROM items"));
    }

    @Test
    @DisplayName("returns false for null SQL")
    void nullSqlReturnsFalse() {
        assertFalse(SpannerProviderClient.hasOrderByClause(null));
    }

    @Test
    @DisplayName("ignores 'ORDER BY' inside a string literal")
    void stringLiteralFalsePositiveGuard() {
        // The literal-stripping pass must mask out anything inside single
        // quotes; otherwise the SDK skips its default ORDER BY when a caller
        // happens to mention the phrase in a WHERE clause.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE comment = 'please ORDER BY date'"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT 'order by foo' AS msg FROM dual"));
        // SQL-escaped quotes ('') must still leave the surrounding literal masked.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM t WHERE c = 'don''t ORDER BY this'"));
    }

    @Test
    @DisplayName("real ORDER BY following a string literal is still detected")
    void orderByAfterLiteralIsDetected() {
        assertTrue(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE comment = 'please' ORDER BY date"));
    }

    @Test
    @DisplayName("containsAggregate identifies aggregate functions and GROUP BY")
    void aggregateDetection() {
        assertTrue(SpannerProviderClient.containsAggregate("SELECT COUNT(*) FROM items"));
        assertTrue(SpannerProviderClient.containsAggregate("SELECT SUM(amount) FROM items"));
        assertTrue(SpannerProviderClient.containsAggregate("SELECT MAX(age) FROM people"));
        assertTrue(SpannerProviderClient.containsAggregate(
                "SELECT status, COUNT(*) FROM items GROUP BY status"));
    }

    @Test
    @DisplayName("containsAggregate ignores aggregate keywords inside string literals")
    void aggregateDetectionIgnoresLiterals() {
        // Same masking pass as hasOrderByClause; a literal containing the
        // keyword must not be classified as an aggregate.
        assertFalse(SpannerProviderClient.containsAggregate(
                "SELECT note FROM items WHERE note = 'COUNT(*) of stuff'"));
        assertFalse(SpannerProviderClient.containsAggregate(
                "SELECT * FROM items WHERE note = 'GROUP BY day'"));
    }

    @Test
    @DisplayName("containsAggregate returns false for plain projections")
    void aggregateDetectionNegative() {
        assertFalse(SpannerProviderClient.containsAggregate("SELECT * FROM items"));
        assertFalse(SpannerProviderClient.containsAggregate("SELECT id, name FROM items"));
    }

    // ---- GoogleSQL literal-form coverage (regression for STRING_LITERAL_PATTERN
    // extension that adds double-quoted, triple-quoted, raw-prefix, and
    // backslash-escape support beyond the original single-quoted form) ----

    @Test
    @DisplayName("ignores 'ORDER BY' inside a double-quoted literal")
    void doubleQuotedLiteralFalsePositiveGuard() {
        // GoogleSQL accepts both single- and double-quoted string literals;
        // the masking pass must cover both, otherwise an ORDER BY inside a
        // double-quoted literal would suppress the default tiebreaker.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE comment = \"please ORDER BY date\""));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT \"order by foo\" AS msg FROM dual"));
    }

    @Test
    @DisplayName("ignores 'ORDER BY' inside a triple-quoted literal")
    void tripleQuotedLiteralFalsePositiveGuard() {
        // Triple-quoted GoogleSQL literals may contain lone quotes and
        // newlines. The masking pass uses lookaheads to detect the real
        // close-delimiter so embedded quotes / keywords don't false-positive.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE c = \"\"\"multi\nORDER BY day\"\"\""));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE c = '''one ' two '' ORDER BY x'''"));
    }

    @Test
    @DisplayName("ignores 'ORDER BY' inside a raw-prefixed literal")
    void rawPrefixedLiteralFalsePositiveGuard() {
        // Raw strings (r'...' / r"...") allow backslashes verbatim. The mask
        // matches them via the optional [rR]? prefix on the literal pattern.
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE c = r'no \\n ORDER BY here'"));
        assertFalse(SpannerProviderClient.hasOrderByClause(
                "SELECT * FROM items WHERE c = R\"raw ORDER BY content\""));
    }

    @Test
    @DisplayName("ignores aggregate keywords inside non-single-quoted literals")
    void aggregateDetectionIgnoresExtendedLiteralForms() {
        // Mirrors aggregateDetectionIgnoresLiterals but for the new literal
        // forms. Both detection helpers share stripStringLiterals so both
        // must remain correct after the masking pass is extended.
        assertFalse(SpannerProviderClient.containsAggregate(
                "SELECT note FROM items WHERE note = \"COUNT(*) of stuff\""));
        assertFalse(SpannerProviderClient.containsAggregate(
                "SELECT note FROM items WHERE note = \"\"\"SUM(amount) embed\"\"\""));
    }
    // ---- appendResultSetControl coverage (round-4 review: previously this
    // helper was exercised only indirectly through query() integration paths;
    // these unit tests lock in the no-aggregate / aggregate / caller-supplied
    // ORDER BY branches without an emulator). The LIMIT/OFFSET tail is appended
    // separately in executeStatement (which talks to Spanner) and is therefore
    // outside the scope of these unit tests. ----

    @Test
    @DisplayName("appendResultSetControl: appends default ORDER BY when caller has none and no aggregate")
    void appendsDefaultOrderByWhenAbsent_andNoAggregate() {
        QueryRequest q = QueryRequest.builder().build();
        String out = SpannerProviderClient.appendResultSetControl(
                "SELECT * FROM items", q);
        assertEquals("SELECT * FROM items ORDER BY partitionKey, sortKey", out);
    }

    @Test
    @DisplayName("appendResultSetControl: leaves caller-supplied ORDER BY untouched (no tiebreaker)")
    void doesNotAppendOrderByWhenCallerSupplied() {
        QueryRequest q = QueryRequest.builder().build();
        String sql = "SELECT * FROM items ORDER BY createdAt DESC";
        assertEquals(sql, SpannerProviderClient.appendResultSetControl(sql, q));
    }

    @Test
    @DisplayName("appendResultSetControl: skips default ORDER BY for aggregate queries")
    void doesNotAppendOrderByWhenAggregatePresent() {
        QueryRequest q = QueryRequest.builder().build();
        // SELECT COUNT(*) is the canonical case: GoogleSQL would reject
        // ORDER BY partitionKey, sortKey on a non-grouped column.
        String sql = "SELECT COUNT(*) FROM items";
        assertEquals(sql, SpannerProviderClient.appendResultSetControl(sql, q));
        // GROUP BY also triggers the suppression.
        String sqlGroup = "SELECT status, COUNT(*) FROM items GROUP BY status";
        assertEquals(sqlGroup, SpannerProviderClient.appendResultSetControl(sqlGroup, q));
    }

    @Test
    @DisplayName("appendResultSetControl: aggregate + caller orderBy() honors caller, no PK tiebreaker")
    void aggregateWithCallerOrderBy_honorsCallerNoTiebreaker() {
        // The aggregate branch must honor the caller's explicit ordering but
        // skip the partitionKey/sortKey tiebreakers (GoogleSQL would reject
        // those non-grouped columns on an aggregate query).
        QueryRequest q = QueryRequest.builder()
                .orderBy("status", SortDirection.DESC)
                .build();
        String sql = "SELECT status, COUNT(*) FROM items GROUP BY status";
        assertEquals(sql + " ORDER BY status DESC",
                SpannerProviderClient.appendResultSetControl(sql, q));
    }

    @Test
    @DisplayName("appendResultSetControl: caller orderBy() lacking PK columns appends them as tiebreakers")
    void callerOrderByMissingTiebreakers_appended() {
        // Caller sorts by `name` but neither partitionKey nor sortKey — the
        // SDK adds both as tiebreakers to guarantee deterministic OFFSET-based
        // pagination across pages.
        QueryRequest q = QueryRequest.builder()
                .orderBy("name", SortDirection.ASC)
                .build();
        String out = SpannerProviderClient.appendResultSetControl(
                "SELECT * FROM items", q);
        assertEquals(
                "SELECT * FROM items ORDER BY name ASC, partitionKey, sortKey",
                out);
    }

    @Test
    @DisplayName("appendResultSetControl: caller orderBy() already containing both PKs adds no duplicates")
    void callerOrderByCoversBothPrimaryKeys_noDuplicates() {
        QueryRequest q = QueryRequest.builder()
                .orderBy("partitionKey", SortDirection.ASC)
                .orderBy("sortKey", SortDirection.DESC)
                .build();
        String out = SpannerProviderClient.appendResultSetControl(
                "SELECT * FROM items", q);
        assertEquals(
                "SELECT * FROM items ORDER BY partitionKey ASC, sortKey DESC",
                out);
    }

    @Test
    @DisplayName("appendResultSetControl: caller orderBy() already containing partitionKey adds only missing sortKey")
    void callerOrderByCoversPartitionKeyOnly_appendsSortKeyTiebreaker() {
        QueryRequest q = QueryRequest.builder()
                .orderBy("partitionKey", SortDirection.ASC)
                .build();
        String out = SpannerProviderClient.appendResultSetControl(
                "SELECT * FROM items", q);
        assertEquals(
                "SELECT * FROM items ORDER BY partitionKey ASC, sortKey",
                out);
    }

    @Test
    @DisplayName("appendResultSetControl: null QueryRequest emits no ORDER BY (caller-driven path)")
    void nullQueryRequest_emitsNoDefaultOrderBy() {
        // The null QueryRequest branch is reached when the caller invokes
        // executeStatement without a QueryRequest (internal SDK paths). The
        // helper must return the SQL unchanged — neither tiebreakers nor a
        // default ORDER BY are appended.
        String sql = "SELECT * FROM items";
        assertEquals(sql, SpannerProviderClient.appendResultSetControl(sql, null));
    }
}
