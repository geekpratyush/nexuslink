package com.nexuslink.protocol.mongo;

import java.util.List;

/**
 * Result of a MongoDB read (find / aggregate). Documents are rendered as JSON strings
 * (relaxed/shell mode) for display; writes return counts directly from {@link MongoService}.
 */
public record MongoQueryResult(
        boolean success,
        List<String> documents,
        long durationMs,
        String error
) {
    public static MongoQueryResult ok(List<String> documents, long durationMs) {
        return new MongoQueryResult(true, documents, durationMs, null);
    }

    public static MongoQueryResult error(String message, long durationMs) {
        return new MongoQueryResult(false, List.of(), durationMs, message);
    }

    public int count() {
        return documents.size();
    }
}
