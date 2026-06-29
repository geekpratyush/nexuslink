package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Computes consumer-group lag from committed offsets and log-end offsets. Pure and
 * dependency-free (no Kafka types leak in), so it is fully offline-testable: callers
 * fetch the two offset maps from a broker and hand them here as plain {@code (topic,
 * partition) -> offset} entries. Lag is clamped at zero — a committed offset ahead of
 * the reported end offset (a momentary race during fetch) reads as caught-up, never
 * negative.
 */
public final class ConsumerLagCalculator {

    private ConsumerLagCalculator() {}

    /** Identifies one partition of a topic; the key type for the offset maps. */
    public record TopicPartitionKey(String topic, int partition) {}

    /** One partition's lag for a group: {@code lag = max(0, endOffset - committed)}. */
    public record LagRow(String group, String topic, int partition, long committed, long endOffset, long lag) {}

    /**
     * Builds the lag rows for {@code group}, one per committed partition that also has a
     * known end offset. Partitions present in {@code committed} but absent from
     * {@code endOffsets} are skipped (the end offset is needed to measure lag). Rows are
     * sorted by topic, then by partition.
     */
    public static List<LagRow> compute(String group,
                                       Map<TopicPartitionKey, Long> committed,
                                       Map<TopicPartitionKey, Long> endOffsets) {
        List<LagRow> rows = new ArrayList<>();
        for (Map.Entry<TopicPartitionKey, Long> e : committed.entrySet()) {
            TopicPartitionKey tp = e.getKey();
            Long end = endOffsets.get(tp);
            if (end == null) continue; // no end offset -> cannot measure lag
            long committedOffset = e.getValue();
            long lag = Math.max(0, end - committedOffset);
            rows.add(new LagRow(group, tp.topic(), tp.partition(), committedOffset, end, lag));
        }
        rows.sort(Comparator.comparing(LagRow::topic).thenComparingInt(LagRow::partition));
        return rows;
    }

    /** The summed lag across every row. */
    public static long totalLag(List<LagRow> rows) {
        long total = 0;
        for (LagRow r : rows) total += r.lag();
        return total;
    }
}
