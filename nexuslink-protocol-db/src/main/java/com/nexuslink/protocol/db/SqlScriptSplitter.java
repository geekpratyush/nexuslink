package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into individual statements on top-level {@code ;} delimiters, correctly
 * ignoring semicolons that appear inside string literals, quoted identifiers, comments and
 * PostgreSQL dollar-quoted bodies.
 * <p>
 * The splitter is a pure, dependency-free scanner: it never touches JDBC and makes no assumptions
 * about a specific dialect beyond the lexical rules below. It recognises:
 * <ul>
 *   <li>single-quoted string literals {@code '...'} with {@code ''} escaped quotes;</li>
 *   <li>double-quoted and backtick-quoted identifiers ({@code "..."} / {@code `...`}) with the
 *       doubled-delimiter escape ({@code ""} / {@code ``});</li>
 *   <li>line comments introduced by {@code --} or {@code #} (MySQL), running to end of line;</li>
 *   <li>block comments {@code /* ... *&#47;} (nesting optional, off by default);</li>
 *   <li>PostgreSQL dollar-quoted strings {@code $$ ... $$} and tagged {@code $tag$ ... $tag$}.</li>
 * </ul>
 * Each returned statement is trimmed of surrounding whitespace; its interior text (including any
 * embedded comments) is preserved verbatim. Empty and comment-only fragments are dropped unless
 * {@link Options#keepComments()} is set.
 */
public final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    /** A statement together with its start offset (first non-whitespace char) in the source script. */
    public record Statement(String text, int startOffset) {
    }

    /**
     * Scanner options.
     *
     * @param keepComments        when {@code true}, fragments that contain only comments and
     *                            whitespace are retained instead of being dropped
     * @param nestedBlockComments when {@code true}, {@code /* ... *&#47;} comments may nest (as in
     *                            PostgreSQL); when {@code false} the first {@code *&#47;} closes them
     */
    public record Options(boolean keepComments, boolean nestedBlockComments) {

        /** Default behaviour: drop comment-only fragments, non-nesting block comments. */
        public static Options defaults() {
            return new Options(false, false);
        }

        /** Like {@link #defaults()} but retains comment-only fragments. */
        public static Options keepingComments() {
            return new Options(true, false);
        }

        public Options withKeepComments(boolean value) {
            return new Options(value, nestedBlockComments);
        }

        public Options withNestedBlockComments(boolean value) {
            return new Options(keepComments, value);
        }
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT, DOLLAR_QUOTE
    }

    /** Splits {@code sql} using {@link Options#defaults()}. */
    public static List<String> split(String sql) {
        return split(sql, Options.defaults());
    }

    /** Splits {@code sql} into statement texts. */
    public static List<String> split(String sql, Options options) {
        List<String> out = new ArrayList<>();
        for (Statement s : splitWithOffsets(sql, options)) {
            out.add(s.text());
        }
        return out;
    }

    /** Splits {@code sql} using {@link Options#defaults()}, keeping each statement's start offset. */
    public static List<Statement> splitWithOffsets(String sql) {
        return splitWithOffsets(sql, Options.defaults());
    }

    /**
     * Splits {@code sql} into statements, recording for each the offset of its first non-whitespace
     * character in the original script (useful for editor cursor mapping).
     */
    public static List<Statement> splitWithOffsets(String sql, Options options) {
        List<Statement> out = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return out;
        }
        int n = sql.length();
        State state = State.NORMAL;
        int blockDepth = 0;
        String dollarTag = null;

        int fragStart = 0;
        boolean hasCode = false; // non-whitespace, non-comment content seen in the current fragment
        int i = 0;

        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            switch (state) {
                case NORMAL -> {
                    if (c == ';') {
                        emit(out, sql, fragStart, i, hasCode, options);
                        fragStart = i + 1;
                        hasCode = false;
                        i++;
                    } else if (c == '\'') {
                        state = State.SINGLE_QUOTE;
                        hasCode = true;
                        i++;
                    } else if (c == '"') {
                        state = State.DOUBLE_QUOTE;
                        hasCode = true;
                        i++;
                    } else if (c == '`') {
                        state = State.BACKTICK;
                        hasCode = true;
                        i++;
                    } else if (c == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        i += 2;
                    } else if (c == '#') {
                        state = State.LINE_COMMENT;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        blockDepth = 1;
                        i += 2;
                    } else if (c == '$') {
                        String tag = matchDollarTag(sql, i);
                        if (tag != null) {
                            dollarTag = tag;
                            state = State.DOLLAR_QUOTE;
                            hasCode = true;
                            i += tag.length();
                        } else {
                            hasCode = true;
                            i++;
                        }
                    } else {
                        if (!Character.isWhitespace(c)) {
                            hasCode = true;
                        }
                        i++;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (c == '\'') {
                        if (next == '\'') {
                            i += 2; // escaped quote
                        } else {
                            state = State.NORMAL;
                            i++;
                        }
                    } else {
                        i++;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (c == '"') {
                        if (next == '"') {
                            i += 2; // escaped quote
                        } else {
                            state = State.NORMAL;
                            i++;
                        }
                    } else {
                        i++;
                    }
                }
                case BACKTICK -> {
                    if (c == '`') {
                        if (next == '`') {
                            i += 2; // escaped backtick
                        } else {
                            state = State.NORMAL;
                            i++;
                        }
                    } else {
                        i++;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n' || c == '\r') {
                        state = State.NORMAL;
                    }
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        blockDepth--;
                        i += 2;
                        if (blockDepth == 0) {
                            state = State.NORMAL;
                        }
                    } else if (options.nestedBlockComments() && c == '/' && next == '*') {
                        blockDepth++;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                case DOLLAR_QUOTE -> {
                    if (c == '$' && sql.startsWith(dollarTag, i)) {
                        i += dollarTag.length();
                        state = State.NORMAL;
                        dollarTag = null;
                    } else {
                        i++;
                    }
                }
            }
        }

        // Final fragment (no trailing delimiter).
        emit(out, sql, fragStart, n, hasCode, options);
        return out;
    }

    /**
     * Recognises a dollar-quote opener starting at {@code start}: {@code $$} or {@code $tag$} where
     * {@code tag} matches {@code [A-Za-z_][A-Za-z0-9_]*}. Returns the full opener token (e.g.
     * {@code "$$"} or {@code "$func$"}) or {@code null} if the {@code $} is not a valid opener.
     */
    private static String matchDollarTag(String sql, int start) {
        int n = sql.length();
        int j = start + 1; // char after the opening '$'
        int tagStart = j;
        while (j < n) {
            char c = sql.charAt(j);
            if (c == '$') {
                // Empty tag ($$) is valid; a non-empty tag must be a valid identifier (checked as we go).
                return sql.substring(start, j + 1);
            }
            boolean first = j == tagStart;
            boolean ok = first
                    ? (Character.isLetter(c) || c == '_')
                    : (Character.isLetterOrDigit(c) || c == '_');
            if (!ok) {
                return null;
            }
            j++;
        }
        return null; // no closing '$' for the tag
    }

    private static void emit(List<Statement> out, String sql, int from, int to,
                             boolean hasCode, Options options) {
        // Advance past leading whitespace to locate the true statement start offset.
        int start = from;
        while (start < to && Character.isWhitespace(sql.charAt(start))) {
            start++;
        }
        int end = to;
        while (end > start && Character.isWhitespace(sql.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return; // empty / whitespace-only fragment
        }
        if (!hasCode && !options.keepComments()) {
            return; // comment-only fragment
        }
        out.add(new Statement(sql.substring(start, end), start));
    }
}
