package com.nexuslink.core.metrics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricsAlertsTest {

    private static final Instant T = Instant.parse("2026-07-05T00:00:00Z");

    @Test
    void highErrorRateTriggersAlert() {
        MetricsCollector c = new MetricsCollector();
        for (int i = 0; i < 10; i++) c.record("rest", 10, i < 3, 1, T);   // 7 failures / 10 = 70%
        List<MetricsAlerts.Alert> alerts = MetricsAlerts.evaluate(c.snapshot(), MetricsAlerts.Thresholds.defaults());
        assertEquals(1, alerts.size());
        assertEquals(MetricsAlerts.Kind.ERROR_RATE, alerts.get(0).kind());
        assertEquals("rest", alerts.get(0).channel());
    }

    @Test
    void highP95AndMeanLatencyBothTrigger() {
        MetricsCollector c = new MetricsCollector();
        for (int i = 0; i < 10; i++) c.record("slow", 5000, true, 1, T);   // all 5s
        var t = MetricsAlerts.Thresholds.defaults();
        List<MetricsAlerts.Alert> alerts = MetricsAlerts.evaluate(c.snapshot(), t);
        assertTrue(alerts.stream().anyMatch(a -> a.kind() == MetricsAlerts.Kind.P95_LATENCY));
        assertTrue(alerts.stream().anyMatch(a -> a.kind() == MetricsAlerts.Kind.MEAN_LATENCY));
        assertTrue(MetricsAlerts.isBreaching(c.stats("slow"), t));
    }

    @Test
    void healthyChannelHasNoAlerts() {
        MetricsCollector c = new MetricsCollector();
        for (int i = 0; i < 20; i++) c.record("ok", 20, true, 1, T);
        assertTrue(MetricsAlerts.evaluate(c.snapshot(), MetricsAlerts.Thresholds.defaults()).isEmpty());
        assertFalse(MetricsAlerts.isBreaching(c.stats("ok"), MetricsAlerts.Thresholds.defaults()));
    }

    @Test
    void smallSampleChannelsAreIgnored() {
        MetricsCollector c = new MetricsCollector();
        c.record("blip", 9999, false, 1, T);   // 1 sample, 100% error, huge latency — but below minSamples(5)
        assertTrue(MetricsAlerts.evaluate(c.snapshot(), MetricsAlerts.Thresholds.defaults()).isEmpty());
    }

    @Test
    void disabledThresholdsSkipTheirCheck() {
        MetricsCollector c = new MetricsCollector();
        for (int i = 0; i < 10; i++) c.record("x", 5000, false, 1, T);
        // Only latency checks disabled → error-rate still fires; disable all → nothing.
        var errorsOnly = new MetricsAlerts.Thresholds(0.05, 0, 0, 5);
        assertEquals(1, MetricsAlerts.evaluate(c.snapshot(), errorsOnly).size());
        var allOff = new MetricsAlerts.Thresholds(0, 0, 0, 5);
        assertTrue(MetricsAlerts.evaluate(c.snapshot(), allOff).isEmpty());
    }

    @Test
    void nullInputsAreSafe() {
        assertTrue(MetricsAlerts.evaluate(null, MetricsAlerts.Thresholds.defaults()).isEmpty());
        assertFalse(MetricsAlerts.isBreaching(null, MetricsAlerts.Thresholds.defaults()));
    }
}
