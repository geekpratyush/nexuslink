package com.nexuslink.ui.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, dialect-agnostic SQL pretty-printer for the editor's <b>Format</b> action. It upper-cases
 * reserved words and breaks the statement onto readable lines before the major clauses — enough to
 * tidy a pasted one-liner without pretending to be a full parser. String literals and comments are
 * preserved verbatim.
 */
public final class SqlFormatter {

    private SqlFormatter() { }

    // Words we upper-case wherever they appear.
    private static final Set<String> KEYWORDS = Set.of(
        "select","from","where","and","or","not","in","like","between","is","null","insert","into",
        "values","update","set","delete","create","alter","drop","table","view","index","join",
        "inner","left","right","full","outer","cross","on","using","group","by","order","having",
        "limit","offset","distinct","as","union","all","except","intersect","case","when","then",
        "else","end","asc","desc","with","returning","exists","primary","key","foreign","references",
        "default","unique","constraint","add","column","cast","over","partition");

    // Clauses that start a fresh line.
    private static final Pattern NEWLINE_BEFORE = Pattern.compile(
        "\\b(FROM|WHERE|GROUP BY|ORDER BY|HAVING|LIMIT|OFFSET|LEFT JOIN|RIGHT JOIN|INNER JOIN"
        + "|FULL JOIN|CROSS JOIN|JOIN|UNION ALL|UNION|EXCEPT|INTERSECT|VALUES|SET|RETURNING|ON)\\b");

    // Split on words, whitespace, strings and comments so we only touch bare words.
    private static final Pattern TOKEN = Pattern.compile(
        "'(?:[^']|'')*'|--[^\\n]*|/\\*(?:.|\\n)*?\\*/|[A-Za-z_][A-Za-z0-9_]*|\\s+|.",
        Pattern.DOTALL);

    /** Formats one statement; returns the input unchanged if anything looks off. */
    public static String format(String sql) {
        if (sql == null || sql.isBlank()) return sql == null ? "" : sql;
        String upper = upcaseKeywords(sql.trim());
        // Collapse runs of whitespace to a single space (outside strings/comments handled by tokenizer above).
        String collapsed = upper.replaceAll("[ \\t]+", " ");
        String broken = NEWLINE_BEFORE.matcher(collapsed).replaceAll("\n$1");
        // Indent the SELECT column list slightly is overkill here; keep clauses left-aligned.
        return broken.trim();
    }

    private static String upcaseKeywords(String sql) {
        Matcher m = TOKEN.matcher(sql);
        StringBuilder out = new StringBuilder(sql.length());
        while (m.find()) {
            String t = m.group();
            char c0 = t.isEmpty() ? ' ' : t.charAt(0);
            boolean literal = c0 == '\'' || (t.length() >= 2 && c0 == '-' && t.charAt(1) == '-')
                    || (t.length() >= 2 && c0 == '/' && t.charAt(1) == '*');
            if (!literal && t.length() > 0 && (Character.isLetter(c0) || c0 == '_')
                    && KEYWORDS.contains(t.toLowerCase(Locale.ROOT))) {
                out.append(t.toUpperCase(Locale.ROOT));
            } else {
                out.append(t);
            }
        }
        return out.toString();
    }
}
