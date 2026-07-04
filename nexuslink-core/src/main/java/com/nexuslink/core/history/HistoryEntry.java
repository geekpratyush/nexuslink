package com.nexuslink.core.history;

/**
 * One persisted record of an executed request/operation, across any protocol.
 * {@code summary} is the short one-line label; {@code detail} holds a JSON blob
 * the originating protocol can use to fully reconstruct (replay) the request.
 */
public record HistoryEntry(
        long id,                // 0 for not-yet-persisted
        String protocol,        // "rest", "kafka", "sftp", …
        long timestamp,         // epoch millis
        String summary,         // e.g. "GET https://api.example.com/users → 200"
        int statusCode,         // protocol status (HTTP code, 0 if n/a)
        long durationMs,
        boolean favorite,
        String detail           // JSON for replay
) {
    public static HistoryEntry newRest(String summary, int statusCode, long durationMs, String detailJson) {
        return new HistoryEntry(0, "rest", System.currentTimeMillis(),
                summary, statusCode, durationMs, false, detailJson);
    }

    /** A SQL/JDBC history entry; {@code detailJson} carries the connection URL + statement for replay. */
    public static HistoryEntry newSql(String summary, long durationMs, String detailJson) {
        return new HistoryEntry(0, "sql", System.currentTimeMillis(),
                summary, 0, durationMs, false, detailJson);
    }
}
