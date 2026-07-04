package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaMetricsSummaryTest {

    private static String valueOf(List<KafkaMetricsSummary.Metric> rows, String label) {
        return rows.stream().filter(m -> m.label().equals(label)).map(KafkaMetricsSummary.Metric::value)
                .findFirst().orElse(null);
    }

    @Test
    void formatsEachKindOfMetric() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("connection-count", 4.0);
        raw.put("request-rate", 12.34);
        raw.put("incoming-byte-rate", 1536.0);        // 1.5 KB/s
        raw.put("request-latency-avg", 2.5);
        raw.put("io-wait-ratio", 0.25);               // 25.0%

        List<KafkaMetricsSummary.Metric> rows = KafkaMetricsSummary.summarize(raw);
        assertEquals("4", valueOf(rows, "Open connections"));
        assertEquals("12.3/s", valueOf(rows, "Requests/s"));
        assertEquals("1.5 KB/s", valueOf(rows, "Incoming"));
        assertEquals("2.5 ms", valueOf(rows, "Avg request latency"));
        assertEquals("25.0%", valueOf(rows, "I/O wait"));
    }

    @Test
    void keepsCuratedDisplayOrder() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("io-wait-ratio", 0.1);
        raw.put("connection-count", 1.0);
        raw.put("request-rate", 5.0);
        // Despite insertion order, output follows the curated order (connections, then request-rate, then io-wait).
        List<String> labels = KafkaMetricsSummary.summarize(raw).stream()
                .map(KafkaMetricsSummary.Metric::label).toList();
        assertEquals(List.of("Open connections", "Requests/s", "I/O wait"), labels);
    }

    @Test
    void skipsMissingAndNaNMetrics() {
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("connection-count", 2.0);
        raw.put("request-rate", Double.NaN);
        raw.put("unknown-metric", 99.0);
        List<KafkaMetricsSummary.Metric> rows = KafkaMetricsSummary.summarize(raw);
        assertEquals(1, rows.size());
        assertEquals("Open connections", rows.get(0).label());
    }

    @Test
    void nullOrEmptyInputYieldsNoRows() {
        assertTrue(KafkaMetricsSummary.summarize(null).isEmpty());
        assertTrue(KafkaMetricsSummary.summarize(Map.of()).isEmpty());
    }

    @Test
    void byteRateScalesUnits() {
        assertEquals("512 B/s", KafkaMetricsSummary.humanRate(512));
        assertEquals("2.0 MB/s", KafkaMetricsSummary.humanRate(2 * 1024 * 1024));
    }
}
