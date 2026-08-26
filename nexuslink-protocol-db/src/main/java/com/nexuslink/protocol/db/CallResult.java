package com.nexuslink.protocol.db;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a stored-procedure or function call produced: the OUT/INOUT values keyed by parameter name
 * (in declaration order), any result set the routine returned, the update count, how long it took,
 * and the error message when it failed.
 */
public record CallResult(
        Map<String, String> outputs,
        QueryResult resultSet,   // null when the routine returned no rows
        int updateCount,
        long durationMs,
        boolean failed,
        String errorMessage
) {
    public static CallResult error(String message, long durationMs) {
        return new CallResult(Map.of(), null, 0, durationMs, true, message);
    }

    public static CallResult of(Map<String, String> outputs, QueryResult rs, int updateCount, long durationMs) {
        return new CallResult(new LinkedHashMap<>(outputs), rs, updateCount, durationMs, false, null);
    }

    /** A one-line summary for the status bar. */
    public String summary() {
        if (failed) return "Error";
        StringBuilder sb = new StringBuilder();
        if (resultSet != null) sb.append(resultSet.rowCount()).append(" row(s) · ");
        if (!outputs.isEmpty()) sb.append(outputs.size()).append(" out param(s) · ");
        return sb.append(durationMs).append(" ms").toString();
    }
}
