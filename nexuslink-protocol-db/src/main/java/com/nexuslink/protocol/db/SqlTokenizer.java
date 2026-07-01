package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A pure, dependency-free lexical scanner that splits a SQL string into a contiguous list of
 * {@link SqlToken}s tagged with a {@link SqlTokenType}. It exists so a JavaFX editor can colour SQL
 * offline without pulling in a parser or touching JDBC.
 * <p>
 * The scanner is best-effort and never throws on malformed input: an unterminated string literal,
 * quoted identifier or block comment simply runs to the end of the input as a token of that type.
 * Every character of the input is covered by exactly one token, so concatenating the tokens'
 * {@link SqlToken#text()} in order reproduces the original string verbatim (see
 * {@link #tokenize(String)}).
 * <p>
 * Lexical rules mirror {@link SqlScriptSplitter} where they overlap:
 * <ul>
 *   <li>single-quoted string literals {@code '...'} with {@code ''} escaped quotes;</li>
 *   <li>double-quoted and backtick-quoted identifiers ({@code "..."} / {@code `...`}) with the
 *       doubled-delimiter escape ({@code ""} / {@code ``});</li>
 *   <li>line comments introduced by {@code --} or {@code #}, running to end of line;</li>
 *   <li>block comments {@code /* ... *&#47;};</li>
 *   <li>numeric literals including decimal and scientific notation;</li>
 *   <li>bind parameters {@code ?}, {@code :name} and {@code $1}.</li>
 * </ul>
 */
public final class SqlTokenizer {

    private SqlTokenizer() {
    }

    /**
     * A reasonable, case-insensitive set of reserved SQL words (stored upper-case). This is not tied
     * to any single dialect; it is a superset covering the common ANSI keywords plus a few widely
     * used vendor extensions so an editor highlights the words a user expects.
     */
    private static final Set<String> KEYWORDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BEGIN", "BETWEEN", "BY",
            "CASE", "CAST", "CHECK", "COLLATE", "COLUMN", "COMMIT", "CONSTRAINT", "CREATE",
            "CROSS", "CURRENT", "DATABASE", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP",
            "ELSE", "END", "EXCEPT", "EXISTS", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM",
            "FULL", "GRANT", "GROUP", "HAVING", "IF", "IN", "INDEX", "INNER", "INSERT",
            "INTERSECT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "NATURAL",
            "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "OUTER", "PRIMARY", "REFERENCES",
            "RIGHT", "ROLLBACK", "SELECT", "SET", "TABLE", "THEN", "TRUE", "UNION", "UNIQUE",
            "UPDATE", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WITH")));

    /** Multi-character operator lexemes, longest first so the scanner matches greedily. */
    private static final String[] MULTI_OPERATORS = {
            "->>", "<=>", "->", "<=", ">=", "<>", "!=", "::", "||", ":=", "&&"
    };

    private static final String SINGLE_OPERATORS = "=<>+-*/%,;().!&|^~@[]{}";

    /**
     * Returns the case-insensitive keyword set recognised by the tokenizer, as upper-case words. The
     * returned set is unmodifiable.
     */
    public static Set<String> keywords() {
        return KEYWORDS;
    }

    /** Returns {@code true} if {@code word} is a recognised SQL keyword, ignoring case. */
    public static boolean isKeyword(String word) {
        return word != null && KEYWORDS.contains(word.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Tokenizes {@code sql} into a contiguous list of tokens. A {@code null} or empty input yields an
     * empty list. The scan never throws; malformed constructs are emitted best-effort.
     */
    public static List<SqlToken> tokenize(String sql) {
        List<SqlToken> out = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return out;
        }
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            int start = i;
            SqlTokenType type;

            if (Character.isWhitespace(c)) {
                int j = i + 1;
                while (j < n && Character.isWhitespace(sql.charAt(j))) {
                    j++;
                }
                i = j;
                type = SqlTokenType.WHITESPACE;
            } else if ((c == '-' && next == '-') || c == '#') {
                i = scanLineComment(sql, i);
                type = SqlTokenType.LINE_COMMENT;
            } else if (c == '/' && next == '*') {
                i = scanBlockComment(sql, i);
                type = SqlTokenType.BLOCK_COMMENT;
            } else if (c == '\'') {
                i = scanDelimited(sql, i, '\'');
                type = SqlTokenType.STRING;
            } else if (c == '"') {
                i = scanDelimited(sql, i, '"');
                type = SqlTokenType.QUOTED_IDENTIFIER;
            } else if (c == '`') {
                i = scanDelimited(sql, i, '`');
                type = SqlTokenType.QUOTED_IDENTIFIER;
            } else if (isDigit(c) || (c == '.' && isDigit(next))) {
                i = scanNumber(sql, i);
                type = SqlTokenType.NUMBER;
            } else if (c == '?') {
                i = i + 1;
                type = SqlTokenType.PARAMETER;
            } else if (c == ':' && isIdentifierStart(next)) {
                i = scanWhile(sql, i + 1, true);
                type = SqlTokenType.PARAMETER;
            } else if (c == '$' && isDigit(next)) {
                int j = i + 1;
                while (j < n && isDigit(sql.charAt(j))) {
                    j++;
                }
                i = j;
                type = SqlTokenType.PARAMETER;
            } else if (isIdentifierStart(c)) {
                i = scanWhile(sql, i, false);
                String word = sql.substring(start, i);
                type = isKeyword(word) ? SqlTokenType.KEYWORD : SqlTokenType.IDENTIFIER;
            } else {
                int opLen = matchOperator(sql, i);
                if (opLen > 0) {
                    i = i + opLen;
                    type = SqlTokenType.OPERATOR;
                } else {
                    i = i + 1;
                    type = SqlTokenType.UNKNOWN;
                }
            }

            out.add(new SqlToken(type, sql.substring(start, i), start, i));
        }
        return out;
    }

    /** Consumes a {@code --} or {@code #} comment up to (but not including) the next line break. */
    private static int scanLineComment(String sql, int i) {
        int n = sql.length();
        int j = i;
        while (j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
            j++;
        }
        return j;
    }

    /** Consumes a {@code /* ... *&#47;} block comment; an unterminated comment runs to end of input. */
    private static int scanBlockComment(String sql, int i) {
        int n = sql.length();
        int j = i + 2; // past the opening "/*"
        while (j < n) {
            if (sql.charAt(j) == '*' && j + 1 < n && sql.charAt(j + 1) == '/') {
                return j + 2;
            }
            j++;
        }
        return n; // unterminated
    }

    /**
     * Consumes a delimited run opened by {@code delim} at {@code i}, honouring the doubled-delimiter
     * escape ({@code ''}, {@code ""}, {@code ``}). An unterminated run consumes to end of input.
     */
    private static int scanDelimited(String sql, int i, char delim) {
        int n = sql.length();
        int j = i + 1; // past the opening delimiter
        while (j < n) {
            char c = sql.charAt(j);
            if (c == delim) {
                if (j + 1 < n && sql.charAt(j + 1) == delim) {
                    j += 2; // escaped delimiter
                } else {
                    return j + 1; // closing delimiter
                }
            } else {
                j++;
            }
        }
        return n; // unterminated
    }

    /**
     * Consumes a numeric literal: an optional integer part, an optional {@code .fraction}, and an
     * optional {@code e[+/-]exponent}. The scan is greedy but conservative: a {@code .} is only taken
     * as a decimal point when a digit follows it, so member access such as {@code t.col} is not
     * swallowed.
     */
    private static int scanNumber(String sql, int i) {
        int n = sql.length();
        int j = i;
        while (j < n && isDigit(sql.charAt(j))) {
            j++;
        }
        if (j < n && sql.charAt(j) == '.' && j + 1 < n && isDigit(sql.charAt(j + 1))) {
            j++; // the '.'
            while (j < n && isDigit(sql.charAt(j))) {
                j++;
            }
        }
        if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
                k++;
            }
            if (k < n && isDigit(sql.charAt(k))) {
                j = k + 1;
                while (j < n && isDigit(sql.charAt(j))) {
                    j++;
                }
            }
        }
        return j;
    }

    /**
     * Consumes an identifier-body run starting at {@code from}. When {@code alreadyStarted} is false
     * the first character is assumed already validated as an identifier start; the run continues over
     * identifier-part characters.
     */
    private static int scanWhile(String sql, int from, boolean alreadyStarted) {
        int n = sql.length();
        int j = from;
        if (!alreadyStarted && j < n) {
            j++; // the validated start char
        }
        while (j < n && isIdentifierPart(sql.charAt(j))) {
            j++;
        }
        return j;
    }

    /** Returns the length of the longest operator lexeme at {@code i}, or 0 if none matches. */
    private static int matchOperator(String sql, int i) {
        for (String op : MULTI_OPERATORS) {
            if (sql.startsWith(op, i)) {
                return op.length();
            }
        }
        if (SINGLE_OPERATORS.indexOf(sql.charAt(i)) >= 0) {
            return 1;
        }
        return 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
