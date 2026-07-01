package com.nexuslink.protocol.db;

/**
 * A single lexical token carved out of a SQL string by {@link SqlTokenizer}.
 * <p>
 * The {@code start} (inclusive) and {@code end} (exclusive) offsets are indices into the original
 * input, so {@code input.substring(start, end)} equals {@link #text()} and consecutive tokens cover
 * the input contiguously with no gaps or overlaps.
 *
 * @param type  the lexical category of the token
 * @param text  the exact source text of the token (equal to {@code input.substring(start, end)})
 * @param start the offset of the first character of the token, inclusive
 * @param end   the offset one past the last character of the token, exclusive
 */
public record SqlToken(SqlTokenType type, String text, int start, int end) {

    public SqlToken {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span: start=" + start + ", end=" + end);
        }
        if (text.length() != end - start) {
            throw new IllegalArgumentException(
                    "text length " + text.length() + " does not match span " + (end - start));
        }
    }

    /** The number of characters this token spans ({@code end - start}). */
    public int length() {
        return end - start;
    }
}
