package com.hyperscaledb.api.query;

/**
 * Exception thrown when a portable query expression cannot be parsed.
 */
public class ExpressionParseException extends RuntimeException {

    private final int position;

    /**
     * Create a new parse exception.
     *
     * @param message  description of the error
     * @param position character position in the expression where the error occurred
     *                 (0-based)
     */
    public ExpressionParseException(String message, int position) {
        super(message + " (at position " + position + ")");
        this.position = position;
    }

    /**
     * @return the character position (0-based) in the input expression where the
     *         error occurred
     */
    public int getPosition() {
        return position;
    }
}
