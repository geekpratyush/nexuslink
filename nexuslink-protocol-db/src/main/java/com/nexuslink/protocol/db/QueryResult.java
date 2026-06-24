package com.nexuslink.protocol.db;

import java.util.List;

/**
 * Result of a SQL statement. For SELECTs, {@code columns} and {@code rows} are populated;
 * for updates, {@code updateCount} carries the affected-row count and rows is empty.
 */
public record QueryResult(
        boolean isResultSet,
        List<String> columns,
        List<List<String>> rows,   // cell values rendered as strings (null → "NULL")
        int updateCount,
        long durationMs,
        boolean failed,
        String errorMessage
) {
    public static QueryResult error(String message, long durationMs) {
        return new QueryResult(false, List.of(), List.of(), 0, durationMs, true, message);
    }

    public int rowCount() {
        return rows.size();
    }

    public String summary() {
        if (failed) return "Error";
        if (isResultSet) return rowCount() + " row" + (rowCount() == 1 ? "" : "s") + " · " + durationMs + " ms";
        return updateCount + " row" + (updateCount == 1 ? "" : "s") + " affected · " + durationMs + " ms";
    }
}
