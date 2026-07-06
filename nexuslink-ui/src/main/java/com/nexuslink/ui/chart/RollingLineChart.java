package com.nexuslink.ui.chart;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reusable live line chart that keeps a rolling window of the most-recent points per named series.
 * Each {@link #tick(Map)} advances a shared x-counter and appends one y-value to every named series,
 * dropping points older than the window so the chart scrolls. Series appear/disappear as names come and
 * go across ticks.
 * <p>
 * Not tied to any protocol — used for Kafka per-partition lag, and reusable for any "value over time"
 * dashboard (throughput, connection counts, …). All mutation must happen on the JavaFX thread.
 */
public final class RollingLineChart extends LineChart<Number, Number> {

    private final Map<String, Series<Number, Number>> seriesByName = new LinkedHashMap<>();
    private final int windowSize;
    private long tick = 0;

    /** @param windowSize how many recent points to retain per series (e.g. 60) */
    public RollingLineChart(String yLabel, int windowSize) {
        super(newXAxis(), newYAxis(yLabel));
        this.windowSize = Math.max(2, windowSize);
        setAnimated(false);
        setCreateSymbols(false);
        setLegendVisible(true);
        getStyleClass().add("rolling-line-chart");
    }

    private static NumberAxis newXAxis() {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setTickLabelsVisible(false);
        x.setMinorTickVisible(false);
        x.setAutoRanging(true);
        return x;
    }

    private static NumberAxis newYAxis(String label) {
        NumberAxis y = new NumberAxis();
        y.setLabel(label);
        y.setForceZeroInRange(true);
        return y;
    }

    /**
     * Advance one tick, appending {@code values} (series name → y). Series present in the map get a new
     * point; the rolling window is enforced for all known series. Absent series simply don't advance.
     */
    public void tick(Map<String, ? extends Number> values) {
        tick++;
        for (Map.Entry<String, ? extends Number> e : values.entrySet()) {
            Series<Number, Number> s = seriesByName.computeIfAbsent(e.getKey(), name -> {
                Series<Number, Number> ns = new Series<>();
                ns.setName(name);
                getData().add(ns);
                return ns;
            });
            s.getData().add(new Data<>(tick, e.getValue()));
        }
        // Trim every series to the rolling window.
        for (Series<Number, Number> s : seriesByName.values()) {
            while (s.getData().size() > windowSize) s.getData().remove(0);
        }
    }

    /** Remove all series and reset the tick counter. */
    public void reset() {
        getData().clear();
        seriesByName.clear();
        tick = 0;
    }

    /** Number of series currently tracked (for tests / status). */
    public int seriesCount() {
        return seriesByName.size();
    }
}
