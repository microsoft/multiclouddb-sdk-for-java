// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser for portable query expressions.
 * <p>
 * Parses SQL-subset WHERE clause syntax with named {@code @paramName}
 * parameters
 * and portable function calls.
 * <p>
 * <b>Grammar</b> (operator precedence: NOT &gt; AND &gt; OR):
 * 
 * <pre>
 * expression     ::= orExpression
 * orExpression   ::= andExpression ( 'OR' andExpression )*
 * andExpression  ::= notExpression ( 'AND' notExpression )*
 * notExpression  ::= 'NOT' notExpression | primary
 * primary        ::= '(' expression ')'
 *                  | functionCall
 *                  | fieldExpression
 * functionCall   ::= FUNCTION_NAME '(' argList ')'
 * fieldExpression::= fieldRef 'IN' '(' valueList ')'
 *                  | fieldRef 'BETWEEN' value 'AND' value
 *                  | fieldRef compOp value
 * fieldRef       ::= IDENTIFIER ( '.' IDENTIFIER )*
 * value          ::= STRING_LITERAL | NUMBER_LITERAL | BOOLEAN_LITERAL | NULL | PARAMETER
 * argList        ::= arg ( ',' arg )*
 * arg            ::= fieldRef | value
 * valueList      ::= value ( ',' value )*
 * compOp         ::= '=' | '<>' | '!=' | '<' | '>' | '<=' | '>='
 * </pre>
 */
public final class ExpressionParser {

    private static final int MAX_EXPRESSION_LENGTH = 8192;
    private static final int MAX_RECURSION_DEPTH = 100;
    private final String input;
    private final List<Token> tokens;
    private int pos;
    private int recursionDepth;

    private ExpressionParser(String input) {
        this.input = input;
        this.tokens = tokenize(input);
        this.pos = 0;
        this.recursionDepth = 0;
    }

    /**
     * Parse a portable expression string into an AST.
     *
     * @param expression the portable WHERE clause expression
     * @return the parsed Expression AST
     * @throws ExpressionParseException if the expression is malformed
     */
    public static Expression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ExpressionParseException("Expression must not be null or blank", 0);
        }
        String trimmed = expression.trim();
        if (trimmed.length() > MAX_EXPRESSION_LENGTH) {
            throw new ExpressionParseException(
                    "Expression length must be <= " + MAX_EXPRESSION_LENGTH
                            + " characters",
                    MAX_EXPRESSION_LENGTH);
        }
        ExpressionParser parser = new ExpressionParser(trimmed);
        Expression result = parser.parseOrExpression();
        if (parser.pos < parser.tokens.size()) {
            Token unexpected = parser.tokens.get(parser.pos);
            throw new ExpressionParseException(
                    "Unexpected token: " + unexpected.value, unexpected.position);
        }
        return result;
    }

    // ---- Recursive descent ----

    private void enterRecursion() {
        recursionDepth++;
        if (recursionDepth > MAX_RECURSION_DEPTH) {
            throw new ExpressionParseException(
                    "Expression recursion depth must be <= " + MAX_RECURSION_DEPTH,
                    pos < tokens.size() ? tokens.get(pos).position : input.length());
        }
    }

    private void exitRecursion() {
        recursionDepth--;
    }

    private Expression parseOrExpression() {
        Expression left = parseAndExpression();
        while (matchKeyword("OR")) {
            Expression right = parseAndExpression();
            left = new LogicalExpression(left, LogicalOp.OR, right);
        }
        return left;
    }

    private Expression parseAndExpression() {
        Expression left = parseNotExpression();
        while (matchKeyword("AND") && !isAtBetweenAnd()) {
            Expression right = parseNotExpression();
            left = new LogicalExpression(left, LogicalOp.AND, right);
        }
        return left;
    }

    /**
     * Checks if the upcoming AND is part of a BETWEEN ... AND ... construct.
     * This is used to disambiguate AND as a logical operator vs BETWEEN's AND.
     * We track this via a simple flag set during BETWEEN parsing.
     */
    private boolean isAtBetweenAnd() {
        return false; // BETWEEN AND is consumed inline; this guard is not needed
    }

    private Expression parseNotExpression() {
        enterRecursion();
        try {
            if (matchKeyword("NOT")) {
                Expression child = parseNotExpression();
                return new NotExpression(child);
            }
            return parsePrimary();
        } finally {
            exitRecursion();
        }
    }

    private Expression parsePrimary() {
        if (pos >= tokens.size()) {
            throw new ExpressionParseException("Unexpected end of expression", input.length());
        }

        // Parenthesized expression
        if (match(TokenType.LPAREN)) {
            enterRecursion();
            try {
                Expression inner = parseOrExpression();
                expect(TokenType.RPAREN, ")");
                return inner;
            } finally {
                exitRecursion();
            }
        }

        Token current = tokens.get(pos);

        // Function call: check if identifier is a known portable function
        if (current.type == TokenType.IDENTIFIER) {
            PortableFunction func = PortableFunction.fromName(current.value);
            if (func != null && pos + 1 < tokens.size()
                    && tokens.get(pos + 1).type == TokenType.LPAREN) {
                return parseFunctionCall(func);
            }
        }

        // Field expression (comparison, IN, BETWEEN)
        return parseFieldExpression();
    }

    private Expression parseFunctionCall(PortableFunction func) {
        pos++; // consume function name
        expect(TokenType.LPAREN, "(");
        List<Object> args = new ArrayList<>();
        if (pos < tokens.size() && tokens.get(pos).type != TokenType.RPAREN) {
            args.add(parseFunctionArg());
            while (match(TokenType.COMMA)) {
                args.add(parseFunctionArg());
            }
        }
        expect(TokenType.RPAREN, ")");
        return new FunctionCallExpression(func, args);
    }

    private Object parseFunctionArg() {
        if (pos >= tokens.size()) {
            throw new ExpressionParseException("Unexpected end of expression in function arguments", input.length());
        }
        Token current = tokens.get(pos);

        // Parameter
        if (current.type == TokenType.PARAMETER) {
            pos++;
            return new Parameter(current.value);
        }

        // Literal
        if (current.type == TokenType.STRING || current.type == TokenType.NUMBER
                || current.type == TokenType.BOOLEAN || current.type == TokenType.NULL_LIT) {
            pos++;
            return toLiteral(current);
        }

        // Field reference
        if (current.type == TokenType.IDENTIFIER) {
            return parseFieldRef();
        }

        throw new ExpressionParseException(
                "Expected field, literal, or parameter in function argument, got: " + current.value,
                current.position);
    }

    private Expression parseFieldExpression() {
        FieldRef field = parseFieldRef();

        if (pos >= tokens.size()) {
            throw new ExpressionParseException("Expected operator after field '" + field.name() + "'",
                    input.length());
        }

        Token opToken = tokens.get(pos);

        // IN expression
        if (opToken.type == TokenType.IDENTIFIER && "IN".equalsIgnoreCase(opToken.value)) {
            pos++;
            expect(TokenType.LPAREN, "(");
            List<Object> values = new ArrayList<>();
            values.add(parseValue());
            while (match(TokenType.COMMA)) {
                values.add(parseValue());
            }
            expect(TokenType.RPAREN, ")");
            return new InExpression(field, values);
        }

        // BETWEEN expression
        if (opToken.type == TokenType.IDENTIFIER && "BETWEEN".equalsIgnoreCase(opToken.value)) {
            pos++;
            Object low = parseValue();
            expectKeyword("AND");
            Object high = parseValue();
            return new BetweenExpression(field, low, high);
        }

        // Comparison expression
        if (opToken.type == TokenType.COMP_OP) {
            pos++;
            ComparisonOp op = ComparisonOp.fromSymbol(opToken.value);
            Object value = parseValue();
            return new ComparisonExpression(field, op, value);
        }

        throw new ExpressionParseException(
                "Expected comparison operator, IN, or BETWEEN after field '" + field.name()
                        + "', got: " + opToken.value,
                opToken.position);
    }

    private FieldRef parseFieldRef() {
        if (pos >= tokens.size() || tokens.get(pos).type != TokenType.IDENTIFIER) {
            int errPos = pos < tokens.size() ? tokens.get(pos).position : input.length();
            String errVal = pos < tokens.size() ? tokens.get(pos).value : "end of expression";
            throw new ExpressionParseException(
                    "Expected field name, got: " + errVal, errPos);
        }
        StringBuilder sb = new StringBuilder(tokens.get(pos).value);
        pos++;
        // Support dot notation
        while (pos + 1 < tokens.size()
                && tokens.get(pos).type == TokenType.DOT
                && tokens.get(pos + 1).type == TokenType.IDENTIFIER) {
            sb.append('.').append(tokens.get(pos + 1).value);
            pos += 2;
        }
        return new FieldRef(sb.toString());
    }

    private Object parseValue() {
        if (pos >= tokens.size()) {
            throw new ExpressionParseException("Unexpected end of expression, expected a value", input.length());
        }
        Token t = tokens.get(pos);
        if (t.type == TokenType.PARAMETER) {
            pos++;
            return new Parameter(t.value);
        }
        if (t.type == TokenType.STRING || t.type == TokenType.NUMBER
                || t.type == TokenType.BOOLEAN || t.type == TokenType.NULL_LIT) {
            pos++;
            return toLiteral(t);
        }
        throw new ExpressionParseException(
                "Expected value (literal or @parameter), got: " + t.value, t.position);
    }

    private Literal toLiteral(Token t) {
        return switch (t.type) {
            case STRING -> new Literal(t.value);
            case NUMBER -> {
                if (t.value.contains(".")) {
                    yield new Literal(Double.parseDouble(t.value));
                } else {
                    try {
                        yield new Literal(Integer.parseInt(t.value));
                    } catch (NumberFormatException e) {
                        yield new Literal(Long.parseLong(t.value));
                    }
                }
            }
            case BOOLEAN -> new Literal(Boolean.parseBoolean(t.value));
            case NULL_LIT -> new Literal(null);
            default -> throw new ExpressionParseException("Unexpected token type: " + t.type, t.position);
        };
    }

    // ---- Token helpers ----

    private boolean match(TokenType type) {
        if (pos < tokens.size() && tokens.get(pos).type == type) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean matchKeyword(String keyword) {
        if (pos < tokens.size() && tokens.get(pos).type == TokenType.IDENTIFIER
                && keyword.equalsIgnoreCase(tokens.get(pos).value)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(TokenType type, String displayName) {
        if (pos >= tokens.size()) {
            throw new ExpressionParseException("Expected '" + displayName + "' but reached end of expression",
                    input.length());
        }
        if (tokens.get(pos).type != type) {
            throw new ExpressionParseException(
                    "Expected '" + displayName + "', got: " + tokens.get(pos).value,
                    tokens.get(pos).position);
        }
        pos++;
    }

    private void expectKeyword(String keyword) {
        if (pos >= tokens.size()) {
            throw new ExpressionParseException(
                    "Expected '" + keyword + "' but reached end of expression", input.length());
        }
        if (!(tokens.get(pos).type == TokenType.IDENTIFIER
                && keyword.equalsIgnoreCase(tokens.get(pos).value))) {
            throw new ExpressionParseException(
                    "Expected '" + keyword + "', got: " + tokens.get(pos).value,
                    tokens.get(pos).position);
        }
        pos++;
    }

    // ---- Tokenizer ----

    private enum TokenType {
        IDENTIFIER, PARAMETER, STRING, NUMBER, BOOLEAN, NULL_LIT,
        COMP_OP, LPAREN, RPAREN, COMMA, DOT
    }

    private record Token(TokenType type, String value, int position) {
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = input.length();

        while (i < len) {
            char ch = input.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }

            // String literal (single-quoted)
            if (ch == '\'') {
                int start = i;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len) {
                    if (input.charAt(i) == '\'') {
                        // Check for escaped quote ('')
                        if (i + 1 < len && input.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        sb.append(input.charAt(i));
                        i++;
                    }
                }
                tokens.add(new Token(TokenType.STRING, sb.toString(), start));
                continue;
            }

            // Parameter (@paramName)
            if (ch == '@') {
                int start = i;
                i++;
                int nameStart = i;
                while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) {
                    i++;
                }
                if (i == nameStart) {
                    throw new ExpressionParseException("Empty parameter name after @", start);
                }
                tokens.add(new Token(TokenType.PARAMETER, input.substring(nameStart, i), start));
                continue;
            }

            // Number (integer or decimal, optionally negative)
            if (Character.isDigit(ch) || (ch == '-' && i + 1 < len && Character.isDigit(input.charAt(i + 1)))) {
                int start = i;
                if (ch == '-')
                    i++;
                while (i < len && Character.isDigit(input.charAt(i)))
                    i++;
                if (i < len && input.charAt(i) == '.') {
                    i++;
                    while (i < len && Character.isDigit(input.charAt(i)))
                        i++;
                }
                tokens.add(new Token(TokenType.NUMBER, input.substring(start, i), start));
                continue;
            }

            // Comparison operators
            if (ch == '=' || ch == '<' || ch == '>' || ch == '!') {
                int start = i;
                if (ch == '<') {
                    if (i + 1 < len && input.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.COMP_OP, "<=", start));
                        i += 2;
                    } else if (i + 1 < len && input.charAt(i + 1) == '>') {
                        tokens.add(new Token(TokenType.COMP_OP, "<>", start));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.COMP_OP, "<", start));
                        i++;
                    }
                } else if (ch == '>') {
                    if (i + 1 < len && input.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.COMP_OP, ">=", start));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenType.COMP_OP, ">", start));
                        i++;
                    }
                } else if (ch == '!') {
                    if (i + 1 < len && input.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenType.COMP_OP, "!=", start));
                        i += 2;
                    } else {
                        throw new ExpressionParseException("Unexpected character '!'", start);
                    }
                } else { // '='
                    tokens.add(new Token(TokenType.COMP_OP, "=", start));
                    i++;
                }
                continue;
            }

            if (ch == '(') {
                tokens.add(new Token(TokenType.LPAREN, "(", i));
                i++;
                continue;
            }
            if (ch == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")", i));
                i++;
                continue;
            }
            if (ch == ',') {
                tokens.add(new Token(TokenType.COMMA, ",", i));
                i++;
                continue;
            }
            if (ch == '.') {
                tokens.add(new Token(TokenType.DOT, ".", i));
                i++;
                continue;
            }

            // Identifier or keyword
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) {
                    i++;
                }
                String word = input.substring(start, i);
                // Check for boolean/null keywords
                if ("true".equalsIgnoreCase(word) || "false".equalsIgnoreCase(word)) {
                    tokens.add(new Token(TokenType.BOOLEAN, word.toLowerCase(), start));
                } else if ("null".equalsIgnoreCase(word)) {
                    tokens.add(new Token(TokenType.NULL_LIT, "null", start));
                } else {
                    tokens.add(new Token(TokenType.IDENTIFIER, word, start));
                }
                continue;
            }

            throw new ExpressionParseException("Unexpected character: '" + ch + "'", i);
        }

        return tokens;
    }
}
