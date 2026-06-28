package com.nexuslink.ui.rest;

import com.nexuslink.protocol.http.rest.RestResponse;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;

/**
 * A request waterfall: one horizontal bar per timing phase, laid end-to-end so
 * the row offsets show when each phase began relative to the whole request.
 * <p>
 * The phases mirror {@link RestResponse.Timing}. The JDK client used today only
 * separates setup (waiting) from download, so those are the bars normally drawn;
 * when a finer DNS/TCP/TLS breakdown becomes available the extra phases appear
 * automatically because the model is purely cumulative.
 */
final class TimelineView extends Region {

    private static final double ROW_H = 26;
    private static final double LABEL_W = 130;
    private static final double MS_W = 70;
    private static final double PAD = 12;
    private static final double TOP = 10;

    private final Canvas canvas = new Canvas();
    private RestResponse.Timing timing;

    TimelineView() {
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        widthProperty().addListener((o, ov, nv) -> redraw());
    }

    /** Sets the timing to render (null clears the chart). */
    void setTiming(RestResponse.Timing timing) {
        this.timing = timing;
        redraw();
    }

    private record Phase(String name, long ms, Color color) {}

    private List<Phase> phases() {
        List<Phase> list = new ArrayList<>();
        if (timing == null) return list;
        addIfPositive(list, "DNS", timing.dnsMs(), Color.web("#8e9aff"));
        addIfPositive(list, "Connect", timing.connectMs(), Color.web("#3fb6a8"));
        addIfPositive(list, "TLS", timing.tlsMs(), Color.web("#b07cff"));
        addIfPositive(list, "Waiting (TTFB)", timing.ttfbMs(), Color.web("#f0a93b"));
        addIfPositive(list, "Download", timing.downloadMs(), Color.web("#4c8bf0"));
        return list;
    }

    private static void addIfPositive(List<Phase> list, String name, long ms, Color color) {
        if (ms > 0) list.add(new Phase(name, ms, color));
    }

    private void redraw() {
        double w = getWidth();
        List<Phase> phases = phases();
        double height = TOP + Math.max(1, phases.size()) * ROW_H + 28;
        canvas.setHeight(height);
        setPrefHeight(height);

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, height);
        if (w <= 0) return;

        if (phases.isEmpty()) {
            g.setFill(Color.web("#8a92a6"));
            g.setFont(Font.font("System", 13));
            g.fillText("No timing to display. Send a request to see the waterfall.", PAD, TOP + 20);
            return;
        }

        long total = phases.stream().mapToLong(Phase::ms).sum();
        if (total <= 0) total = 1;
        double trackX = LABEL_W;
        double trackW = Math.max(40, w - LABEL_W - MS_W - PAD);

        Font labelFont = Font.font("System", 12);
        Font msFont = Font.font("System", FontWeight.SEMI_BOLD, 12);

        double cumulative = 0;
        double y = TOP;
        for (Phase p : phases) {
            double x = trackX + (cumulative / total) * trackW;
            double barW = Math.max(2, (p.ms() / (double) total) * trackW);

            g.setFill(Color.web("#8a92a6"));
            g.setFont(labelFont);
            g.fillText(p.name(), PAD, y + ROW_H / 2 + 4);

            g.setFill(p.color());
            g.fillRoundRect(x, y + 4, barW, ROW_H - 12, 4, 4);

            g.setFill(Color.web("#c8cedd"));
            g.setFont(msFont);
            g.fillText(p.ms() + " ms", w - MS_W, y + ROW_H / 2 + 4);

            cumulative += p.ms();
            y += ROW_H;
        }

        // Total footer.
        g.setStroke(Color.web("#3a4254"));
        g.strokeLine(PAD, y + 6, w - PAD, y + 6);
        g.setFill(Color.web("#e6e9f2"));
        g.setFont(Font.font("System", FontWeight.BOLD, 12));
        g.fillText("Total", PAD, y + 24);
        g.fillText(total + " ms", w - MS_W, y + 24);
    }
}
