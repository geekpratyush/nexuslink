package com.nexuslink.ui.chart;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the rolling-window trim + series lifecycle on a real JavaFX thread. Skipped automatically
 * if no JavaFX toolkit/display can start (headless CI) by catching the startup failure.
 */
class RollingLineChartTest {

    private static boolean fxUp = false;

    @BeforeAll
    static void startFx() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            fxUp = latch.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException already) {
            fxUp = true; // toolkit already running
        } catch (Throwable t) {
            fxUp = false; // no display — test will no-op
        }
    }

    @Test
    void trimsToWindowAndTracksSeries() throws Exception {
        if (!fxUp) return; // headless: nothing to verify

        AtomicReference<AssertionError> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                RollingLineChart chart = new RollingLineChart("Lag", 3);
                for (int i = 0; i < 5; i++) {
                    chart.tick(Map.of("t-0", (long) i, "t-1", (long) (i * 2)));
                }
                assertEquals(2, chart.seriesCount());
                // window=3 → each series retains only its 3 most-recent points
                chart.getData().forEach(s -> assertEquals(3, s.getData().size()));
                // last point of t-1 should be i=4 → value 8
                var t1 = chart.getData().stream().filter(s -> s.getName().equals("t-1")).findFirst().orElseThrow();
                assertEquals(8, t1.getData().get(t1.getData().size() - 1).getYValue().intValue());

                chart.reset();
                assertEquals(0, chart.seriesCount());
                assertTrue(chart.getData().isEmpty());
            } catch (AssertionError e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX task did not complete");
        if (failure.get() != null) throw failure.get();
    }
}
