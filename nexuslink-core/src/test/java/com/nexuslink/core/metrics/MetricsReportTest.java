package com.nexuslink.core.metrics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsReportTest {

    private static MetricsCollector withSamples() {
        MetricsCollector c = new MetricsCollector();
        Instant t = Instant.parse("2026-07-05T00:00:00Z");
        c.record("rest", 10, true, 100, t);
        c.record("rest", 30, false, 200, t);
        c.record("kafka", 5, true, 50, t);
        return c;
    }

    @Test
    void csvHasHeaderAndOneRowPerChannel() {
        String csv = MetricsReport.toCsv(withSamples().snapshot());
        String[] lines = csv.strip().split("\n");
        assertEquals(3, lines.length, "header + 2 channels");
        assertTrue(lines[0].startsWith("channel,count,errors,errorRate"));
        // snapshot() is name-ordered → kafka before rest
        assertTrue(lines[1].startsWith("kafka,1,0,"));
        assertTrue(lines[2].startsWith("rest,2,1,"));
    }

    @Test
    void csvQuotesChannelWithComma() {
        MetricsCollector c = new MetricsCollector();
        c.record("host, port", 1, true, 1, Instant.now());
        String csv = MetricsReport.toCsv(c.snapshot());
        assertTrue(csv.contains("\"host, port\""), csv);
    }

    @Test
    void jsonIsAWellFormedArray() {
        String json = MetricsReport.toJson(withSamples().snapshot());
        assertTrue(json.startsWith("["));
        assertTrue(json.trim().endsWith("]"));
        assertTrue(json.contains("\"channel\":\"rest\""));
        assertTrue(json.contains("\"errors\":1"));
        // one object per channel → two opening braces
        assertEquals(2, json.chars().filter(ch -> ch == '{').count());
    }

    @Test
    void emptySnapshotRenders() {
        Map<String, MetricsCollector.Stats> empty = new MetricsCollector().snapshot();
        assertEquals("[]", MetricsReport.toJson(empty));
        assertEquals("channel,count,errors,errorRate,p50,p95,p99,min,max,mean,totalBytes",
                MetricsReport.toCsv(empty).strip());
    }

    @Test
    void errorRateFractionIsFormatted() {
        String csv = MetricsReport.toCsv(withSamples().snapshot());
        // rest channel: 1 error / 2 = 0.5
        assertTrue(csv.contains(",0.5000,") || csv.contains(",0.5,"), csv);
    }
}
