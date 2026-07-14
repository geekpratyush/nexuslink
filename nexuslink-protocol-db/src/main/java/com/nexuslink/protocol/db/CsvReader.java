package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny, dependency-free RFC 4180 CSV parser (the read counterpart to {@link ResultGridExporter}'s
 * writer). Splits a whole CSV document into rows of string fields, honouring:
 * <ul>
 *   <li>quoted fields ({@code "..."}) that may contain commas, {@code CR}/{@code LF} and doubled
 *       quotes ({@code ""} → a literal {@code "}),</li>
 *   <li>either {@code \n} or {@code \r\n} line endings,</li>
 *   <li>a trailing newline (which does not produce a spurious empty final row).</li>
 * </ul>
 * Fields are returned exactly as written (no trimming); an empty document yields an empty list.
 * Purely functional and unit-testable — it drives the SQL workbench's "Import CSV" flow.
 */
public final class CsvReader {

    private CsvReader() {}

    public static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return rows;

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowStarted = false;   // did we see any content/field on the current line?
        int n = csv.length();

        for (int i = 0; i < n; i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    field.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> { inQuotes = true; rowStarted = true; }
                case ',' -> { row.add(field.toString()); field.setLength(0); rowStarted = true; }
                case '\r' -> { /* swallow; the paired \n (or EOF) ends the row */ }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    rowStarted = false;
                }
                default -> { field.append(c); rowStarted = true; }
            }
        }
        // Flush a final row that wasn't terminated by a newline.
        if (rowStarted || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
