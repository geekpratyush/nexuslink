package com.nexuslink.ui.chart;

import com.nexuslink.protocol.kafka.ConsumerLagCalculator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Consumer-lag summary heatmap (§4.8): a topic (rows) × partition (columns) grid where each cell is
 * shaded by its lag magnitude, so hot partitions jump out at a glance. Fed from the same
 * {@link ConsumerLagCalculator.LagRow} list that drives the lag table.
 *
 * <p>The cell-shading math ({@link #intensity}) is pure and unit-tested; the JavaFX rendering wraps it.
 */
public final class LagHeatmap extends BorderPane {

    private final GridPane grid = new GridPane();
    private final Label caption = new Label("No lag data — refresh a consumer group.");

    public LagHeatmap() {
        getStyleClass().add("lag-heatmap");
        grid.setHgap(2);
        grid.setVgap(2);
        grid.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(false);
        scroll.setPannable(true);
        caption.getStyleClass().add("meta-label");
        setTop(caption);
        BorderPane.setMargin(caption, new Insets(4, 0, 4, 8));
        setCenter(scroll);
        setBottom(legend());
    }

    /**
     * Maps a lag value to a colour intensity in {@code [0,1]} on a log scale (so a few huge partitions
     * don't wash the rest out). {@code 0} lag → 0; {@code maxLag} → 1.
     */
    public static double intensity(long lag, long maxLag) {
        if (lag <= 0 || maxLag <= 0) return 0.0;
        if (lag >= maxLag) return 1.0;
        double scaled = Math.log1p(lag) / Math.log1p(maxLag);
        return Math.max(0.0, Math.min(1.0, scaled));
    }

    /** Repaints the grid from the given lag rows. Must run on the FX thread. */
    public void setData(List<ConsumerLagCalculator.LagRow> rows) {
        grid.getChildren().clear();
        if (rows == null || rows.isEmpty()) {
            caption.setText("No lag data — refresh a consumer group.");
            return;
        }

        // Collect the topic (row) and partition (column) axes, and index lag by (topic,partition).
        TreeSet<String> topics = new TreeSet<>();
        TreeSet<Integer> partitions = new TreeSet<>();
        Map<String, Map<Integer, Long>> lagByTopic = new TreeMap<>();
        long maxLag = 0;
        for (ConsumerLagCalculator.LagRow r : rows) {
            topics.add(r.topic());
            partitions.add(r.partition());
            lagByTopic.computeIfAbsent(r.topic(), k -> new TreeMap<>()).put(r.partition(), r.lag());
            maxLag = Math.max(maxLag, r.lag());
        }

        List<Integer> partList = new ArrayList<>(partitions);
        // Column headers (partition numbers).
        grid.add(headerCell("topic \\ part"), 0, 0);
        for (int c = 0; c < partList.size(); c++) {
            grid.add(headerCell("P" + partList.get(c)), c + 1, 0);
        }

        int row = 1;
        for (String topic : topics) {
            grid.add(headerCell(topic), 0, row);
            Map<Integer, Long> parts = lagByTopic.get(topic);
            for (int c = 0; c < partList.size(); c++) {
                Long lag = parts.get(partList.get(c));
                grid.add(cell(lag, maxLag), c + 1, row);
            }
            row++;
        }
        caption.setText(topics.size() + " topic(s) × " + partList.size()
                + " partition(s) · max lag " + maxLag);
    }

    private static Label headerCell(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heatmap-header");
        l.setMinSize(46, 24);
        l.setPadding(new Insets(2, 6, 2, 6));
        l.setAlignment(Pos.CENTER);
        return l;
    }

    private static Label cell(Long lag, long maxLag) {
        Label l = new Label(lag == null ? "·" : Long.toString(lag));
        l.setMinSize(46, 24);
        l.setAlignment(Pos.CENTER);
        l.getStyleClass().add("heatmap-cell");
        if (lag == null) {
            l.setStyle("-fx-background-color: transparent; -fx-text-fill: -nl-text-faint;");
            l.setTooltip(new Tooltip("no committed offset for this partition"));
        } else {
            double t = intensity(lag, maxLag);
            Color bg = heatColor(t);
            l.setStyle("-fx-background-color: " + toRgb(bg) + "; -fx-text-fill: "
                    + (t > 0.55 ? "white" : "-nl-text-strong") + ";");
            l.setTooltip(new Tooltip("lag " + lag));
        }
        return l;
    }

    /** Cool-to-hot ramp: green (idle) → amber → red (hot). */
    static Color heatColor(double t) {
        Color cool = Color.web("#134E4A"); // deep teal
        Color mid = Color.web("#B45309");  // amber
        Color hot = Color.web("#B91C1C");  // red
        if (t <= 0.5) return cool.interpolate(mid, t / 0.5);
        return mid.interpolate(hot, (t - 0.5) / 0.5);
    }

    private static String toRgb(Color c) {
        return String.format("rgb(%d,%d,%d)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    private HBox legend() {
        HBox box = new HBox(6, new Label("low"));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 8, 6, 8));
        for (double t = 0; t <= 1.0001; t += 0.125) {
            Label swatch = new Label();
            swatch.setMinSize(18, 12);
            swatch.setStyle("-fx-background-color: " + toRgb(heatColor(t)) + ";");
            box.getChildren().add(swatch);
        }
        box.getChildren().add(new Label("high lag"));
        box.getChildren().forEach(n -> {
            if (n instanceof Label lbl && lbl.getMinWidth() != 18) lbl.getStyleClass().add("meta-label");
        });
        return box;
    }
}
