package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.ConsumerLagCalculator.LagRow;
import com.nexuslink.protocol.kafka.ConsumerLagCalculator.TopicPartitionKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerLagCalculatorTest {

    private static TopicPartitionKey tp(String topic, int partition) {
        return new TopicPartitionKey(topic, partition);
    }

    @Test
    void emptyInputsProduceNoRows() {
        List<LagRow> rows = ConsumerLagCalculator.compute("g", Map.of(), Map.of());
        assertTrue(rows.isEmpty());
        assertEquals(0, ConsumerLagCalculator.totalLag(rows));
    }

    @Test
    void lagIsEndMinusCommitted() {
        List<LagRow> rows = ConsumerLagCalculator.compute("g",
                Map.of(tp("orders", 0), 90L),
                Map.of(tp("orders", 0), 100L));
        assertEquals(1, rows.size());
        LagRow r = rows.get(0);
        assertEquals("g", r.group());
        assertEquals("orders", r.topic());
        assertEquals(0, r.partition());
        assertEquals(90, r.committed());
        assertEquals(100, r.endOffset());
        assertEquals(10, r.lag());
    }

    @Test
    void caughtUpPartitionHasZeroLag() {
        List<LagRow> rows = ConsumerLagCalculator.compute("g",
                Map.of(tp("t", 0), 50L),
                Map.of(tp("t", 0), 50L));
        assertEquals(0, rows.get(0).lag());
    }

    @Test
    void committedAheadOfEndClampsToZero() {
        // A momentary fetch race can leave committed > end; lag must never go negative.
        List<LagRow> rows = ConsumerLagCalculator.compute("g",
                Map.of(tp("t", 0), 120L),
                Map.of(tp("t", 0), 100L));
        assertEquals(0, rows.get(0).lag());
        assertEquals(120, rows.get(0).committed());
        assertEquals(100, rows.get(0).endOffset());
    }

    @Test
    void partitionWithoutEndOffsetIsSkipped() {
        Map<TopicPartitionKey, Long> committed = new LinkedHashMap<>();
        committed.put(tp("t", 0), 10L);
        committed.put(tp("t", 1), 20L);
        // Only partition 0 has a known end offset.
        List<LagRow> rows = ConsumerLagCalculator.compute("g", committed,
                Map.of(tp("t", 0), 30L));
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).partition());
        assertEquals(20, rows.get(0).lag());
    }

    @Test
    void rowsSortedByTopicThenPartition() {
        Map<TopicPartitionKey, Long> committed = new LinkedHashMap<>();
        committed.put(tp("beta", 1), 0L);
        committed.put(tp("alpha", 2), 0L);
        committed.put(tp("alpha", 0), 0L);
        committed.put(tp("beta", 0), 0L);
        Map<TopicPartitionKey, Long> end = new LinkedHashMap<>();
        committed.keySet().forEach(k -> end.put(k, 5L));

        List<LagRow> rows = ConsumerLagCalculator.compute("g", committed, end);
        assertEquals(4, rows.size());
        assertEquals("alpha", rows.get(0).topic());
        assertEquals(0, rows.get(0).partition());
        assertEquals("alpha", rows.get(1).topic());
        assertEquals(2, rows.get(1).partition());
        assertEquals("beta", rows.get(2).topic());
        assertEquals(0, rows.get(2).partition());
        assertEquals("beta", rows.get(3).topic());
        assertEquals(1, rows.get(3).partition());
    }

    @Test
    void totalLagSumsEveryRow() {
        Map<TopicPartitionKey, Long> committed = new LinkedHashMap<>();
        committed.put(tp("t", 0), 0L);
        committed.put(tp("t", 1), 5L);
        committed.put(tp("t", 2), 100L);
        Map<TopicPartitionKey, Long> end = new LinkedHashMap<>();
        end.put(tp("t", 0), 10L);   // lag 10
        end.put(tp("t", 1), 5L);    // lag 0
        end.put(tp("t", 2), 250L);  // lag 150

        List<LagRow> rows = ConsumerLagCalculator.compute("g", committed, end);
        assertEquals(160, ConsumerLagCalculator.totalLag(rows));
    }

    @Test
    void totalLagOfEmptyListIsZero() {
        assertEquals(0, ConsumerLagCalculator.totalLag(List.of()));
    }

    @Test
    void groupNameIsCarriedOntoEveryRow() {
        Map<TopicPartitionKey, Long> committed = new LinkedHashMap<>();
        committed.put(tp("t", 0), 1L);
        committed.put(tp("t", 1), 2L);
        Map<TopicPartitionKey, Long> end = new LinkedHashMap<>();
        end.put(tp("t", 0), 3L);
        end.put(tp("t", 1), 4L);

        List<LagRow> rows = ConsumerLagCalculator.compute("payments-svc", committed, end);
        assertEquals(2, rows.size());
        for (LagRow r : rows) assertEquals("payments-svc", r.group());
    }
}
