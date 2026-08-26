package com.nexuslink.protocol.mongo;

import java.util.List;

/**
 * What one aggregation stage produced: its operator, how many documents survive it, a sample of
 * those documents, and how the count moved compared with the stage before it.
 */
public record StagePreview(
        int index,
        String stageName,
        long count,
        long previousCount,
        List<String> sample,
        long durationMs,
        String error
) {
    public static StagePreview failed(int index, String stageName, String message, long durationMs) {
        return new StagePreview(index, stageName, -1, -1, List.of(), durationMs, message);
    }

    /** {@code true} when this stage could not run — the pipeline is broken from here on. */
    public boolean isFailed() { return error != null; }

    /** The change in document count at this stage; 0 for the first stage, which has no predecessor. */
    public long delta() { return previousCount < 0 ? 0 : count - previousCount; }

    /** {@code true} when this stage is where the documents ran out. */
    public boolean emptiedThePipeline() { return count == 0 && previousCount > 0; }

    /** A one-line summary for the stage list, e.g. {@code $match — 240 docs (−12,191)}. */
    public String summary() {
        if (isFailed()) return stageName + " — failed: " + error;
        StringBuilder sb = new StringBuilder(stageName).append(" — ").append(count)
                .append(count == 1 ? " doc" : " docs");
        long delta = delta();
        if (previousCount >= 0 && delta != 0) {
            sb.append(delta > 0 ? " (+" : " (−").append(Math.abs(delta)).append(')');
        }
        if (emptiedThePipeline()) sb.append("  ← nothing survives this stage");
        return sb.toString();
    }
}
