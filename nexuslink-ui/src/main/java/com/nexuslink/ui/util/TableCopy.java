package com.nexuslink.ui.util;

import java.util.List;

/**
 * Pure formatters for copying tabular data to the clipboard — tab-separated (for pasting into a
 * spreadsheet cell-by-cell) and RFC 4180 CSV. JavaFX-free so the formatting is unit-testable; the
 * clipboard glue lives in {@link TableContextMenus}.
 */
public final class TableCopy {

    private TableCopy() {}

    /** Rows joined by newlines, cells within a row joined by tabs. Null cells render as empty. */
    public static String toTsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) sb.append('\n');
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append('\t');
                sb.append(clean(row.get(c)));
            }
        }
        return sb.toString();
    }

    /** Rows as RFC 4180 CSV (a cell is quoted when it contains a comma, quote or newline). */
    public static String toCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) sb.append('\n');
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                sb.append(csvCell(row.get(c)));
            }
        }
        return sb.toString();
    }

    /** RFC 4180 quoting for a single cell. */
    public static String csvCell(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** A single cell value with tabs/newlines flattened so a TSV row stays on one line. */
    private static String clean(String v) {
        if (v == null) return "";
        return v.replace("\t", " ").replace("\r", " ").replace("\n", " ");
    }
}
