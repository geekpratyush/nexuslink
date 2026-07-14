package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns parsed CSV data rows into a list of single-row {@code INSERT} statements for one table,
 * given a per-CSV-column mapping to target table columns. Pure and dependency-free — it composes
 * {@link SqlInsertBuilder} (so identifiers/values are quoted and escaped the same way as the rest of
 * the SQL workbench) and is what the "Import CSV" flow feeds to {@link JdbcService#executeAll}.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>{@code targetColumns.get(i)} names the table column that CSV column {@code i} loads into; a
 *       {@code null}/blank entry ignores that CSV column entirely.</li>
 *   <li>A blank cell is written as SQL {@code NULL} when {@code blankAsNull} is true, otherwise it is
 *       omitted so the database applies the column's own default.</li>
 *   <li>A data row that ends up assigning no columns is skipped (e.g. a trailing blank line).</li>
 * </ul>
 */
public final class CsvImportPlanner {

    private CsvImportPlanner() {}

    public static List<String> toInserts(String table, List<String> targetColumns,
                                         List<List<String>> dataRows, boolean blankAsNull) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("a target table is required");
        if (targetColumns == null || targetColumns.stream().allMatch(CsvImportPlanner::blank)) {
            throw new IllegalArgumentException("map at least one CSV column to a table column");
        }
        List<String> out = new ArrayList<>();
        if (dataRows == null) return out;

        for (List<String> row : dataRows) {
            SqlInsertBuilder ins = new SqlInsertBuilder().table(table);
            int assigned = 0;
            for (int i = 0; i < targetColumns.size(); i++) {
                String target = targetColumns.get(i);
                if (blank(target)) continue;
                String cell = i < row.size() ? row.get(i) : null;
                if (blank(cell)) {
                    if (blankAsNull) { ins.valueNull(target.trim()); assigned++; }
                } else {
                    ins.value(target.trim(), cell);
                    assigned++;
                }
            }
            if (assigned > 0) out.add(ins.build());
        }
        return out;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
