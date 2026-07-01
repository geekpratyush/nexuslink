package com.nexuslink.protocol.db;

/**
 * The lexical category of a {@link SqlToken} produced by {@link SqlTokenizer}.
 * <p>
 * The categories are deliberately coarse: they describe how an offline editor should colour a run
 * of source characters, not the grammatical role the run plays in a statement.
 */
public enum SqlTokenType {

    /** A reserved SQL word such as {@code SELECT} or {@code FROM} (see {@link SqlTokenizer#keywords()}). */
    KEYWORD,

    /** An unquoted name that is not a keyword (table/column/alias/function names, etc.). */
    IDENTIFIER,

    /** A single-quoted string literal {@code '...'} with {@code ''} escaped quotes. */
    STRING,

    /** A delimited identifier: {@code "..."} or {@code `...`}, with the doubled-delimiter escape. */
    QUOTED_IDENTIFIER,

    /** A numeric literal: integer, decimal, or scientific ({@code 1}, {@code 3.14}, {@code 1.0e-3}). */
    NUMBER,

    /** A punctuation or operator run such as {@code =}, {@code <=}, {@code ,}, {@code (}, {@code .}. */
    OPERATOR,

    /** A {@code -- ...} (or {@code # ...}) comment running to end of line. */
    LINE_COMMENT,

    /** A {@code /* ... *&#47;} comment. */
    BLOCK_COMMENT,

    /** A bind parameter: {@code ?}, a named {@code :name}, or a positional {@code $1}. */
    PARAMETER,

    /** A run of whitespace characters. */
    WHITESPACE,

    /** Any character that does not start one of the categories above. */
    UNKNOWN
}
