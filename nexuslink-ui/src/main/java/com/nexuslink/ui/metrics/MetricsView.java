package com.nexuslink.ui.metrics;

import com.nexuslink.core.metrics.MetricsCollector;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.Map;

/**
 * Monitoring dashboard — a live per-channel metrics table (throughput, error rate, latency
 * percentiles) plus a requests-per-second line chart, both refreshed on a 1-second timer from the
 * shared {@link MetricsCollector}. Read-only: protocol views feed the collector as they run.
 */
public final class MetricsView extends BorderPane {

    /** A table row bound to a channel's current {@link MetricsCollector.Stats}. */
    public static final class Row {
        final SimpleStringProperty channel, count, errors, errorRate, p50, p95, p99, mean, throughput, bytes;
        Row(MetricsCollector.Stats s, double tps) {
            channel = new SimpleStringProperty(s.channel());
            count = new SimpleStringProperty(Long.toString(s.count()));
            errors = new SimpleStringProperty(Long.toString(s.errors()));
            errorRate = new SimpleStringProperty(String.format("%.1f%%", s.errorRate() * 100));
            p50 = new SimpleStringProperty(s.p50() + " ms");
            p95 = new SimpleStringProperty(s.p95() + " ms");
            p99 = new SimpleStringProperty(s.p99() + " ms");
            mean = new SimpleStringProperty(String.format("%.0f ms", s.mean()));
            throughput = new SimpleStringProperty(String.format("%.2f/s", tps));
            bytes = new SimpleStringProperty(prettyBytes(s.totalBytes()));
        }
        public String getChannel() { return channel.get(); }
        public String getCount() { return count.get(); }
        public String getErrors() { return errors.get(); }
        public String getErrorRate() { return errorRate.get(); }
        public String getP50() { return p50.get(); }
        public String getP95() { return p95.get(); }
        public String getP99() { return p99.get(); }
        public String getMean() { return mean.get(); }
        public String getThroughput() { return throughput.get(); }
        public String getBytes() { return bytes.get(); }

        private static String prettyBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    private static final java.time.Duration WINDOW = java.time.Duration.ofSeconds(10);

    private final TableView<Row> table = new TableView<>();
    private final XYChart.Series<Number, Number> series = new XYChart.Series<>();
    private final NumberAxis xAxis = new NumberAxis();
    private final Label statusLabel = new Label();
    private final Timeline timeline;
    private int tick;

    public MetricsView() {
        getStyleClass().add("metrics-view");
        setTop(buildBar());
        setCenter(buildBody());
        timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        refresh();
    }

    /** Stops the refresh timer (call when the tab is closed). */
    public void dispose() {
        if (timeline != null) timeline.stop();
    }

    private HBox buildBar() {
        Label title = new Label("Live metrics");
        title.getStyleClass().add("sidebar-title");
        Button clearBtn = new Button("Reset");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> {
            MetricsCollector c = Metrics.collector();
            if (c != null) c.clear();
            series.getData().clear();
            tick = 0;
            refresh();
        });
        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("metrics"));
        statusLabel.getStyleClass().add("meta-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, title, spacer, statusLabel, clearBtn, helpBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    private SplitPane buildBody() {
        addColumn("Channel", "channel", 130);
        addColumn("Requests", "count", 90);
        addColumn("Errors", "errors", 70);
        addColumn("Error %", "errorRate", 80);
        addColumn("P50", "p50", 80);
        addColumn("P95", "p95", 80);
        addColumn("P99", "p99", 80);
        addColumn("Mean", "mean", 80);
        addColumn("Throughput", "throughput", 100);
        addColumn("Data", "bytes", 90);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No requests recorded yet — send some traffic from a protocol tab."));

        xAxis.setLabel("seconds");
        xAxis.setForceZeroInRange(false);
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("requests/sec (all channels)");
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.getData().add(series);

        SplitPane sp = new SplitPane(table, chart);
        sp.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sp.setDividerPositions(0.55);
        return sp;
    }

    private void addColumn(String title, String property, double width) {
        TableColumn<Row, String> col = new TableColumn<>(title);
        col.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        table.getColumns().add(col);
    }

    private void refresh() {
        MetricsCollector collector = Metrics.collector();
        ObservableList<Row> rows = FXCollections.observableArrayList();
        double totalTps = 0;
        long totalReq = 0;
        if (collector != null) {
            Map<String, MetricsCollector.Stats> snapshot = collector.snapshot();
            for (var entry : snapshot.entrySet()) {
                double tps = collector.throughputPerSec(entry.getKey(), WINDOW);
                totalTps += tps;
                totalReq += entry.getValue().count();
                rows.add(new Row(entry.getValue(), tps));
            }
        }
        table.setItems(rows);
        statusLabel.setText(rows.size() + " channel(s) · " + totalReq + " total requests");

        // Append the throughput point and keep a rolling 60-second window on the chart.
        series.getData().add(new XYChart.Data<>(tick, totalTps));
        if (series.getData().size() > 60) series.getData().remove(0);
        xAxis.setAutoRanging(true);
        tick++;
    }
}
