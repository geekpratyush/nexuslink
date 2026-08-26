package com.nexuslink.protocol.db;

import java.util.List;
import java.util.Locale;

/**
 * Serializes a result grid (column headers + string cell rows) to JSON or CSV for export.
 * Pure and dependency-free (the module has no JSON library), so escaping is done by hand:
 * JSON per RFC 8259, CSV per RFC 4180. Both are total — a null cell becomes JSON {@code null}
 * / an empty CSV field rather than the text "null".
 *
 * <p>Beyond JSON and CSV it also renders the grid as {@code INSERT} statements, XML, HTML, and a
 * {@link Delimited delimited file with explicit options} (separator, quoting policy, header,
 * line ending, and the text to write for a NULL) — the four export shapes SQL Developer offers.
 */
public final class ResultGridExporter {

    private ResultGridExporter() {}

    /** A JSON array of objects, one per row, keyed by the column headers. */
    public static String toJson(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (r > 0) sb.append(',');
            sb.append("\n  {");
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) sb.append(", ");
                String cell = c < row.size() ? row.get(c) : null;
                sb.append(jsonString(columns.get(c))).append(": ").append(jsonString(cell));
            }
            sb.append('}');
        }
        sb.append(rows.isEmpty() ? "]" : "\n]");
        return sb.toString();
    }

    /** A CSV document with a header row, RFC 4180 quoted, CRLF line endings. */
    public static String toCsv(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < columns.size(); c++) {
            if (c > 0) sb.append(',');
            sb.append(csvField(columns.get(c)));
        }
        sb.append("\r\n");
        for (List<String> row : rows) {
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) sb.append(',');
                sb.append(csvField(c < row.size() ? row.get(c) : null));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /**
     * One {@code INSERT INTO table (cols) VALUES (…);} statement per row, newline-separated.
     * Values are rendered by {@link SqlQueryBuilder#literal}, so numbers stay bare and strings are
     * single-quoted with embedded quotes doubled; a null cell becomes a bare {@code NULL}.
     */
    public static String toInsertStatements(String table, List<String> columns, List<List<String>> rows) {
        return toInsertStatements(table, columns, rows, SqlDialect.GENERIC);
    }

    /** As above, quoting identifiers the way {@code dialect}'s engine expects. */
    public static String toInsertStatements(String table, List<String> columns, List<List<String>> rows,
                                            SqlDialect dialect) {
        String target = table == null || table.isBlank() ? "TABLE" : table.trim();
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            SqlInsertBuilder insert = new SqlInsertBuilder().table(target).dialect(dialect);
            for (int c = 0; c < columns.size(); c++) {
                String cell = c < row.size() ? row.get(c) : null;
                if (cell == null) insert.valueNull(columns.get(c));
                else insert.value(columns.get(c), cell);
            }
            sb.append(insert.build()).append(";\n");
        }
        return sb.toString();
    }

    /**
     * An XML document: a {@code <results>} root holding one {@code <row>} per row, with one child
     * element per column. Column names are sanitised into legal XML names (illegal characters
     * become {@code _}, a leading digit is prefixed), and a null cell is written as an empty
     * self-closed element carrying {@code xsi:nil="true"}.
     */
    public static String toXml(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<results xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        for (List<String> row : rows) {
            sb.append("  <row>\n");
            for (int c = 0; c < columns.size(); c++) {
                String name = xmlName(columns.get(c));
                String cell = c < row.size() ? row.get(c) : null;
                if (cell == null) sb.append("    <").append(name).append(" xsi:nil=\"true\"/>\n");
                else sb.append("    <").append(name).append('>').append(xmlText(cell))
                        .append("</").append(name).append(">\n");
            }
            sb.append("  </row>\n");
        }
        return sb.append("</results>\n").toString();
    }

    /**
     * A standalone HTML page with the grid as a {@code <table>} — a header row plus one row per
     * record, everything escaped. A null cell renders as an empty cell marked {@code class="null"}
     * so a stylesheet (or the reader) can tell it apart from an empty string.
     */
    public static String toHtml(String title, List<String> columns, List<List<String>> rows) {
        String heading = title == null || title.isBlank() ? "Query results" : title;
        StringBuilder sb = new StringBuilder("<!doctype html>\n<html>\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n<title>").append(xmlText(heading)).append("</title>\n");
        sb.append("<style>table{border-collapse:collapse}th,td{border:1px solid #999;padding:4px 8px;"
                + "text-align:left}th{background:#eee}td.null{color:#999;font-style:italic}</style>\n");
        sb.append("</head>\n<body>\n<h1>").append(xmlText(heading)).append("</h1>\n<table>\n<thead><tr>");
        for (String col : columns) sb.append("<th>").append(xmlText(col)).append("</th>");
        sb.append("</tr></thead>\n<tbody>\n");
        for (List<String> row : rows) {
            sb.append("<tr>");
            for (int c = 0; c < columns.size(); c++) {
                String cell = c < row.size() ? row.get(c) : null;
                if (cell == null) sb.append("<td class=\"null\"></td>");
                else sb.append("<td>").append(xmlText(cell)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        return sb.append("</tbody>\n</table>\n</body>\n</html>\n").toString();
    }

    /**
     * A GitHub-flavoured Markdown table — a header row, an alignment row, then one row per record.
     * Pipes and newlines inside a cell are escaped so the table stays intact, and a null cell is
     * written as {@code _null_} to keep it apart from an empty string.
     */
    public static String toMarkdown(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("|");
        for (String col : columns) sb.append(' ').append(markdownCell(col)).append(" |");
        sb.append("\n|");
        for (int c = 0; c < columns.size(); c++) sb.append(" --- |");
        sb.append('\n');
        for (List<String> row : rows) {
            sb.append('|');
            for (int c = 0; c < columns.size(); c++) {
                String cell = c < row.size() ? row.get(c) : null;
                sb.append(' ').append(cell == null ? "_null_" : markdownCell(cell)).append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** A Markdown cell: pipes escaped, newlines turned into {@code <br>} so the row stays one line. */
    private static String markdownCell(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
    }

    /**
     * Options for a delimited export. {@code delimiter} separates fields, {@code quote} wraps a
     * field (doubled when it appears inside one) — pass {@code '\0'} to disable quoting entirely,
     * in which case a delimiter inside a value is escaped with a backslash. {@code quoteAll} quotes
     * every field rather than only the ones that need it, {@code header} writes the column names
     * first, {@code lineEnding} terminates each record, and {@code nullText} is written for a null
     * cell (unquoted, so it stays distinguishable from the literal string of the same text).
     */
    public record Delimited(String delimiter, char quote, boolean quoteAll,
                            boolean header, String lineEnding, String nullText) {

        /** RFC 4180 CSV: comma, double quotes as needed, header, CRLF, empty for NULL. */
        public static Delimited csv() { return new Delimited(",", '"', false, true, "\r\n", ""); }

        /** Tab-separated, unquoted, header, LF, the text {@code NULL} for a null cell. */
        public static Delimited tsv() { return new Delimited("\t", '\0', false, true, "\n", "NULL"); }

        public Delimited {
            if (delimiter == null || delimiter.isEmpty()) throw new IllegalArgumentException("delimiter is required");
            if (lineEnding == null || lineEnding.isEmpty()) lineEnding = "\n";
            if (nullText == null) nullText = "";
        }

        boolean quoting() { return quote != '\0'; }
    }

    /** Renders the grid with explicit delimiter/quoting/header/line-ending/NULL options. */
    public static String toDelimited(List<String> columns, List<List<String>> rows, Delimited opts) {
        StringBuilder sb = new StringBuilder();
        if (opts.header()) {
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) sb.append(opts.delimiter());
                sb.append(delimitedField(columns.get(c), opts));
            }
            sb.append(opts.lineEnding());
        }
        for (List<String> row : rows) {
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) sb.append(opts.delimiter());
                String cell = c < row.size() ? row.get(c) : null;
                sb.append(cell == null ? opts.nullText() : delimitedField(cell, opts));
            }
            sb.append(opts.lineEnding());
        }
        return sb.toString();
    }

    /** One delimited field, quoted or escaped according to {@code opts}. */
    private static String delimitedField(String s, Delimited opts) {
        if (s == null) return opts.nullText();
        if (!opts.quoting()) {
            return s.replace("\\", "\\\\").replace(opts.delimiter(), "\\" + opts.delimiter());
        }
        String q = String.valueOf(opts.quote());
        boolean needsQuote = opts.quoteAll() || s.contains(opts.delimiter()) || s.contains(q)
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        return q + s.replace(q, q + q) + q;
    }

    /** A column header turned into a legal XML element name. */
    private static String xmlName(String s) {
        if (s == null || s.isBlank()) return "column";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        char first = sb.charAt(0);
        if (Character.isDigit(first) || first == '-' || first == '.') sb.insert(0, '_');
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Escapes text for an XML/HTML text node or attribute. */
    private static String xmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** A JSON string literal, or the bare token {@code null}. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A CSV field, quoted only when it contains a comma, quote, CR or LF. */
    private static String csvField(String s) {
        if (s == null) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
