package com.nexuslink.ui.sse;

import com.nexuslink.protocol.http.sse.SseEvent;
import com.nexuslink.protocol.http.sse.SseService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server-Sent Events client tab — connect to a {@code text/event-stream} endpoint and watch a
 * live, timestamped event log. Supports an event-type filter and pause/resume.
 */
public final class SseView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final SseService service = new SseService();
    private final TextField urlField = new TextField("https://stream.wikimedia.org/v2/stream/recentchange");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Disconnected");
    private final TextField filterField = new TextField();
    private final ToggleButton pauseBtn = new ToggleButton("Pause");
    private final TextArea eventLog = new TextArea();

    private Consumer<String> logger = s -> {};
    private boolean connected;
    private int eventCount;

    public SseView() {
        getStyleClass().add("sse-view");
        setTop(buildBar());
        setCenter(buildLog());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the stream URL (used when opening a saved/sample connection). */
    public void prefill(String url) {
        if (url != null && !url.isBlank()) urlField.setText(url);
    }

    private VBox buildBar() {
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("https://example.com/stream  (text/event-stream)");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("rest-client"));

        HBox row = new HBox(8, urlField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        filterField.getStyleClass().add("nl-field");
        filterField.setPromptText("filter by event type (e.g. message)");
        filterField.setPrefWidth(220);
        pauseBtn.getStyleClass().add("btn-secondary");
        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> { eventLog.clear(); eventCount = 0; });
        Label filterLbl = new Label("Filter:");
        filterLbl.getStyleClass().add("meta-label");
        statusLabel.getStyleClass().add("meta-label");

        HBox controls = new HBox(8, filterLbl, filterField, pauseBtn, clearBtn, statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, controls);
    }

    private VBox buildLog() {
        eventLog.getStyleClass().add("code-area");
        eventLog.setEditable(false);
        eventLog.setPromptText("Events appear here…");
        VBox box = new VBox(eventLog);
        box.setPadding(new Insets(0, 8, 8, 8));
        VBox.setVgrow(eventLog, Priority.ALWAYS);
        return box;
    }

    private void toggleConnect() {
        if (connected) { service.disconnect(); return; }
        String url = urlField.getText().trim();
        if (url.isEmpty()) { statusLabel.setText("Enter a stream URL first"); return; }
        statusLabel.setText("Connecting…");
        connectBtn.setDisable(true);
        logger.accept("SSE connect → " + url);

        service.connect(url, Map.of(), new SseService.Listener() {
            @Override public void onOpen(int status) {
                Platform.runLater(() -> {
                    connected = true;
                    connectBtn.setDisable(false);
                    connectBtn.setText("Disconnect");
                    statusLabel.getStyleClass().setAll(status / 100 == 2 ? "status-2xx" : "status-4xx");
                    statusLabel.setText("● Streaming (HTTP " + status + ")");
                    append("⇆ open — HTTP " + status);
                    logger.accept("SSE open: HTTP " + status);
                });
            }
            @Override public void onEvent(SseEvent event) {
                Platform.runLater(() -> showEvent(event));
            }
            @Override public void onError(Throwable error) {
                Platform.runLater(() -> {
                    statusLabel.getStyleClass().setAll("status-err");
                    statusLabel.setText("Error: " + error.getMessage());
                    append("⚠ error: " + error.getMessage());
                    logger.accept("SSE error: " + error.getMessage());
                });
            }
            @Override public void onClosed() {
                Platform.runLater(() -> {
                    connected = false;
                    connectBtn.setText("Connect");
                    connectBtn.setDisable(false);
                    if (!statusLabel.getStyleClass().contains("status-err")) {
                        statusLabel.getStyleClass().setAll("meta-label");
                        statusLabel.setText("Disconnected — " + eventCount + " event(s)");
                    }
                    append("⇆ closed");
                });
            }
        });
    }

    private void showEvent(SseEvent event) {
        if (pauseBtn.isSelected()) return;
        String filter = filterField.getText().trim();
        if (!filter.isEmpty() && !event.typeOrDefault().equalsIgnoreCase(filter)) return;
        eventCount++;
        String idPart = event.id() == null || event.id().isBlank() ? "" : " #" + event.id();
        append("◀ [" + event.typeOrDefault() + idPart + "]  " + event.data());
    }

    private void append(String line) {
        eventLog.appendText(LocalTime.now().format(TIME) + "  " + line + "\n");
    }
}
