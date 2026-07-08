package com.nexuslink.ui.chart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-math tests for the heatmap shading — no JavaFX toolkit needed. */
class LagHeatmapTest {

    @Test
    void zeroLagIsZeroIntensity() {
        assertEquals(0.0, LagHeatmap.intensity(0, 100));
        assertEquals(0.0, LagHeatmap.intensity(-5, 100));
    }

    @Test
    void maxLagIsFullIntensity() {
        assertEquals(1.0, LagHeatmap.intensity(100, 100));
        assertEquals(1.0, LagHeatmap.intensity(150, 100)); // clamps
    }

    @Test
    void guardsAgainstZeroMax() {
        assertEquals(0.0, LagHeatmap.intensity(50, 0));
    }

    @Test
    void intensityIsMonotonicAndBounded() {
        long max = 10_000;
        double prev = -1;
        for (long lag = 0; lag <= max; lag += 500) {
            double t = LagHeatmap.intensity(lag, max);
            assertTrue(t >= 0.0 && t <= 1.0, "in [0,1] at lag=" + lag);
            assertTrue(t >= prev, "monotonic non-decreasing at lag=" + lag);
            prev = t;
        }
    }

    @Test
    void logScaleLiftsSmallLagsAboveLinear() {
        // On a log scale, 1% of max should read hotter than a plain linear 0.01.
        double t = LagHeatmap.intensity(100, 10_000);
        assertTrue(t > 0.01, "log scale should lift small lags, was " + t);
    }

    @Test
    void heatColorRampStaysInGamut() {
        for (double t = 0; t <= 1.0; t += 0.1) {
            var c = LagHeatmap.heatColor(t);
            assertTrue(c.getRed() >= 0 && c.getRed() <= 1);
            assertTrue(c.getGreen() >= 0 && c.getGreen() <= 1);
            assertTrue(c.getBlue() >= 0 && c.getBlue() <= 1);
        }
    }
}
