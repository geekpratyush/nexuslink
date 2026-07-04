package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.ConsumerLagCalculator.TopicPartitionKey;
import com.nexuslink.protocol.kafka.OffsetResetPlanner.ResetRow;
import com.nexuslink.protocol.kafka.OffsetResetPlanner.Strategy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OffsetResetPlannerTest {

    private static TopicPartitionKey tp(String topic, int partition) {
        return new TopicPartitionKey(topic, partition);
    }

    // One topic "t" with a single partition 0: begin=100, end=200, committed=150.
    private final TopicPartitionKey p0 = tp("t", 0);
    private final Map<TopicPartitionKey, Long> begin = Map.of(p0, 100L);
    private final Map<TopicPartitionKey, Long> end = Map.of(p0, 200L);
    private final Map<TopicPartitionKey, Long> committed = Map.of(p0, 150L);

    private ResetRow only(Strategy s, long arg, Map<TopicPartitionKey, Long> tsOffsets) {
        List<ResetRow> rows = OffsetResetPlanner.plan(s, arg, committed, begin, end, tsOffsets);
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    void earliestGoesToBegin() {
        assertEquals(100L, only(Strategy.EARLIEST, 0, null).target());
    }

    @Test
    void latestGoesToEnd() {
        assertEquals(200L, only(Strategy.LATEST, 0, null).target());
    }

    @Test
    void specificOffsetInRangeIsUsedVerbatim() {
        assertEquals(175L, only(Strategy.SPECIFIC_OFFSET, 175, null).target());
    }

    @Test
    void specificOffsetIsClampedToRange() {
        assertEquals(200L, only(Strategy.SPECIFIC_OFFSET, 999, null).target(), "above end clamps to end");
        assertEquals(100L, only(Strategy.SPECIFIC_OFFSET, 1, null).target(), "below begin clamps to begin");
    }

    @Test
    void shiftByRewindsAndSkipsFromCurrent() {
        assertEquals(140L, only(Strategy.SHIFT_BY, -10, null).target());
        assertEquals(160L, only(Strategy.SHIFT_BY, 10, null).target());
    }

    @Test
    void shiftByIsClampedSoItCannotLeaveTheRange() {
        assertEquals(100L, only(Strategy.SHIFT_BY, -1000, null).target());
        assertEquals(200L, only(Strategy.SHIFT_BY, 1000, null).target());
    }

    @Test
    void timestampUsesResolvedOffsetWhenPresent() {
        assertEquals(180L, only(Strategy.TIMESTAMP, 0, Map.of(p0, 180L)).target());
    }

    @Test
    void timestampFallsBackToEndWhenPartitionUnresolved() {
        // Broker returned no offset at/after the timestamp for p0 (empty map) -> end offset.
        assertEquals(200L, only(Strategy.TIMESTAMP, 0, Map.of()).target());
        assertEquals(200L, only(Strategy.TIMESTAMP, 0, null).target());
    }

    @Test
    void currentShowsBeginWhenNeverCommitted() {
        ResetRow r = OffsetResetPlanner.plan(Strategy.LATEST, 0, Map.of(), begin, end, null).get(0);
        assertEquals(100L, r.current(), "no prior commit -> sits at begin");
        assertEquals(200L, r.target());
        assertEquals(100L, r.delta());
    }

    @Test
    void partitionWithoutEndOffsetIsSkipped() {
        Map<TopicPartitionKey, Long> b = Map.of(p0, 0L, tp("t", 1), 0L);
        Map<TopicPartitionKey, Long> e = Map.of(p0, 50L);   // partition 1 has no end offset
        List<ResetRow> rows = OffsetResetPlanner.plan(Strategy.LATEST, 0, Map.of(), b, e, null);
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).partition());
    }

    @Test
    void rowsAreSortedByTopicThenPartition() {
        Map<TopicPartitionKey, Long> b = new LinkedHashMap<>();
        b.put(tp("b", 1), 0L);
        b.put(tp("a", 2), 0L);
        b.put(tp("a", 0), 0L);
        Map<TopicPartitionKey, Long> e = new LinkedHashMap<>();
        b.keySet().forEach(k -> e.put(k, 10L));
        List<ResetRow> rows = OffsetResetPlanner.plan(Strategy.EARLIEST, 0, Map.of(), b, e, null);
        assertEquals(List.of("a", "a", "b"), rows.stream().map(ResetRow::topic).toList());
        assertEquals(List.of(0, 2, 1), rows.stream().map(ResetRow::partition).toList());
    }

    @Test
    void affectedPartitionsCountsOnlyRealMoves() {
        // Reset to LATEST: p0 committed 150 -> 200 (moves); a second partition already at end (no move).
        Map<TopicPartitionKey, Long> b = Map.of(p0, 100L, tp("t", 1), 0L);
        Map<TopicPartitionKey, Long> e = Map.of(p0, 200L, tp("t", 1), 300L);
        Map<TopicPartitionKey, Long> c = Map.of(p0, 150L, tp("t", 1), 300L);
        List<ResetRow> rows = OffsetResetPlanner.plan(Strategy.LATEST, 0, c, b, e, null);
        assertEquals(2, rows.size());
        assertEquals(1, OffsetResetPlanner.affectedPartitions(rows));
    }
}
