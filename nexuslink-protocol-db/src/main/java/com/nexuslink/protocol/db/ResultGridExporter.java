package com.nexuslink.protocol.db;

import java.util.List;

/**
 * Serializes a result grid (column headers + string cell rows) to JSON or CSV for export.
 * Pure and dependency-free (the module has no JSON library), so escaping is done by hand:
 * JSON per RFC 8259, CSV per RFC 4180. Both are total — a null cell becomes JSON {@code null}
 * / an empty CSV field rather than the text "null".
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
