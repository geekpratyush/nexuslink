package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.ConsumerLagCalculator.TopicPartitionKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Computes the target offset each partition would be reset to for a consumer group, the way an
 * "offset reset" dialog does — earliest / latest / a specific offset / a timestamp / a relative
 * shift. Pure and Kafka-type-free (no {@code TopicPartition} or {@code OffsetAndMetadata} leak in),
 * so it is fully offline-testable: callers fetch the begin/end/committed offset maps from a broker,
 * hand them here, review the plan, then apply it via {@code alterConsumerGroupOffsets}. Every target
 * is clamped to the partition's live {@code [begin, end]} range so a plan can never seek out of
 * bounds. Mirrors {@link ConsumerLagCalculator}, whose {@link TopicPartitionKey} it reuses.
 */
public final class OffsetResetPlanner {

    /** Where to move the group's committed offset for each partition. */
    public enum Strategy {
        /** The earliest retained offset (begin/log-start). */
        EARLIEST,
        /** The log-end offset (skip to newest, no reprocessing). */
        LATEST,
        /** A fixed offset supplied by the user (clamped into range per partition). */
        SPECIFIC_OFFSET,
        /** The first offset at or after a timestamp (resolved by the broker; falls back to LATEST). */
        TIMESTAMP,
        /** The current committed offset shifted by a signed delta (rewind/skip N). */
        SHIFT_BY
    }

    /** One partition's planned move: {@code current} committed → {@code target}. */
    public record ResetRow(String topic, int partition, long current, long target) {
        /** Signed change; negative rewinds (reprocess), positive skips ahead. */
        public long delta() { return target - current; }
    }

    private OffsetResetPlanner() {}

    /**
     * Plans the reset for every partition known in {@code begin} (its keys define the valid partition
     * set, since begin+end bound the clamp). {@code committed} supplies the current offset (a partition
     * with no prior commit is shown as sitting at its begin offset). {@code arg} is the specific offset
     * for SPECIFIC_OFFSET or the signed delta for SHIFT_BY, and is ignored otherwise.
     * {@code timestampOffsets} is only consulted for TIMESTAMP (a partition absent there — the broker
     * had no message at/after the timestamp — falls back to the end offset). Rows are sorted by topic
     * then partition.
     */
    public static List<ResetRow> plan(Strategy strategy, long arg,
                                      Map<TopicPartitionKey, Long> committed,
                                      Map<TopicPartitionKey, Long> begin,
                                      Map<TopicPartitionKey, Long> end,
                                      Map<TopicPartitionKey, Long> timestampOffsets) {
        List<ResetRow> rows = new ArrayList<>();
        for (Map.Entry<TopicPartitionKey, Long> e : begin.entrySet()) {
            TopicPartitionKey tp = e.getKey();
            Long endOffset = end.get(tp);
            if (endOffset == null) continue;   // no end offset -> cannot clamp this partition
            long lo = e.getValue();
            long hi = endOffset;
            long current = committed.getOrDefault(tp, lo);

            long raw = switch (strategy) {
                case EARLIEST -> lo;
                case LATEST -> hi;
                case SPECIFIC_OFFSET -> arg;
                case SHIFT_BY -> current + arg;
                case TIMESTAMP -> timestampOffsets == null ? hi : timestampOffsets.getOrDefault(tp, hi);
            };
            long target = Math.min(hi, Math.max(lo, raw));   // clamp to [begin, end]
            rows.add(new ResetRow(tp.topic(), tp.partition(), current, target));
        }
        rows.sort(Comparator.comparing(ResetRow::topic).thenComparingInt(ResetRow::partition));
        return rows;
    }

    /** How many partitions the plan actually moves (target differs from current). */
    public static long affectedPartitions(List<ResetRow> rows) {
        return rows.stream().filter(r -> r.delta() != 0).count();
    }
}
