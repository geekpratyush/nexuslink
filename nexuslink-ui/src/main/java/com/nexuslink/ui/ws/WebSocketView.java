package com.nexuslink.ui.ws;

import com.nexuslink.protocol.http.ws.WebSocketService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * WebSocket client tab — connect to a ws:// or wss:// endpoint, send text frames, and
 * watch a live, timestamped message log with direction markers.
 */
public final class WebSocketView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final WebSocketService service = new WebSocketService();
    private final TextField urlField = new TextField("wss://echo.websocket.events");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Disconnected");
    private final TextArea messageLog = new TextArea();
    private final TextField sendField = new TextField();
    private final Button sendBtn = new Button("Send");

    private Consumer<String> logger = s -> {};
    private boolean connected;

    public WebSocketView() {
        getStyleClass().add("ws-view");
        setTop(buildBar());
        setCenter(buildLog());
        setBottom(buildSendBar());
        setSendEnabled(false);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the socket URL (used when opening a saved/sample connection). */
    public void prefill(String url) {
        if (url != null && !url.isBlank()) urlField.setText(url);
    }

    private VBox buildBar() {
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("wss://example.com/socket");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("rest-client"));

        HBox row = new HBox(8, urlField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private VBox buildLog() {
        messageLog.getStyleClass().add("code-area");
        messageLog.setEditable(false);
        VBox box = new VBox(messageLog);
        box.setPadding(new Insets(0, 8, 0, 8));
        VBox.setVgrow(messageLog, Priority.ALWAYS);
        return box;
    }

    private HBox buildSendBar() {
        sendField.getStyleClass().add("nl-field");
        sendField.setPromptText("Message to send…");
        HBox.setHgrow(sendField, Priority.ALWAYS);
        sendField.setOnAction(e -> send());

        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> send());

        HBox row = new HBox(8, sendField, sendBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8));
        return row;
    }

    private void toggleConnect() {
        if (connected) {
            service.close();
            return;
        }
        String raw = urlField.getText().trim();
        if (raw.isEmpty()) { statusLabel.setText("Enter a URL first"); return; }
        String url = com.nexuslink.ui.env.Env.resolve(raw);   // resolve ${VAR} against active environment
        statusLabel.setText("Connecting…");
        connectBtn.setDisable(true);
        logger.accept("WS connect → " + url);

        service.connect(url, new WebSocketService.Listener() {
            @Override public void onOpen() {
                Platform.runLater(() -> {
                    connected = true;
                    connectBtn.setDisable(false);
                    connectBtn.setText("Disconnect");
                    statusLabel.getStyleClass().setAll("status-2xx");
                    statusLabel.setText("● Connected");
                    setSendEnabled(true);
                    append("⇆ connected");
                    logger.accept("WS connected");
                });
            }
            @Override public void onText(String message) {
                Platform.runLater(() -> append("◀ " + message));
            }
            @Override public void onClosed(int code, String reason) {
                Platform.runLater(() -> {
                    connected = false;
                    connectBtn.setText("Connect");
                    connectBtn.setDisable(false);
                    statusLabel.getStyleClass().setAll("meta-label");
                    statusLabel.setText("Disconnected (" + code + " " + reason + ")");
                    setSendEnabled(false);
                    append("⇆ closed: " + code + " " + reason);
                });
            }
            @Override public void onError(Throwable error) {
                Platform.runLater(() -> {
                    connected = false;
                    connectBtn.setText("Connect");
                    connectBtn.setDisable(false);
                    statusLabel.getStyleClass().setAll("status-err");
                    statusLabel.setText("Error: " + error.getMessage());
                    setSendEnabled(false);
                    append("⚠ error: " + error.getMessage());
                    logger.accept("WS error: " + error.getMessage());
                });
            }
        });
    }

    private void send() {
        String msg = sendField.getText();
        if (msg.isEmpty() || !connected) return;
        service.sendText(msg);
        append("▶ " + msg);
        sendField.clear();
    }

    private void append(String line) {
        messageLog.appendText(LocalTime.now().format(TIME) + "  " + line + "\n");
    }

    private void setSendEnabled(boolean enabled) {
        sendField.setDisable(!enabled);
        sendBtn.setDisable(!enabled);
    }
}
