package com.nexuslink.ui.graphql;

import com.nexuslink.protocol.http.graphql.GraphQLWsProtocol;
import com.nexuslink.protocol.http.ws.WebSocketService;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Live GraphQL <b>subscription</b> panel over the {@code graphql-transport-ws} WebSocket protocol.
 * Point at the subscription endpoint (ws:// or wss://), write a {@code subscription { … }}, Start, and
 * watch {@code next} payloads scroll in live; Stop sends {@code complete} and closes the socket.
 */
public final class GraphQLSubscriptionPanel extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final WebSocketService service = new WebSocketService();

    private final TextField urlField = new TextField("wss://spacex-production.up.railway.app/graphql/ws");
    private final TextArea queryEditor = new TextArea();
    private final TextArea variablesEditor = new TextArea();
    private final Button startBtn = new Button("Start");
    private final Button clearBtn = new Button("Clear");
    private final Label statusLabel = new Label("Idle");
    private final ObservableList<String> messages = FXCollections.observableArrayList();
    private final ListView<String> messageList = new ListView<>(messages);

    private Consumer<String> logger = s -> {};
    private volatile boolean active;
    private String operationId = "1";

    public GraphQLSubscriptionPanel() {
        getStyleClass().add("graphql-subscription-panel");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Derives the WebSocket endpoint from the HTTP GraphQL endpoint (http→ws, https→wss). */
    public void prefillFromHttpEndpoint(String httpEndpoint) {
        if (httpEndpoint == null || httpEndpoint.isBlank()) return;
        String ws = httpEndpoint.trim()
                .replaceFirst("^https://", "wss://")
                .replaceFirst("^http://", "ws://");
        urlField.setText(ws);
    }

    private VBox buildBar() {
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("wss://api.example.com/graphql   (subscription endpoint)");
        HBox.setHgrow(urlField, Priority.ALWAYS);

        startBtn.getStyleClass().add("btn-primary");
        startBtn.setOnAction(e -> toggle());
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> messages.clear());

        HBox row = new HBox(8, new Label("WS endpoint:"), urlField, startBtn, clearBtn);
        ((Label) row.getChildren().get(0)).getStyleClass().add("meta-label");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        queryEditor.getStyleClass().add("code-area");
        queryEditor.setPromptText("subscription { ... }");
        queryEditor.setText("subscription {\n  \n}");
        variablesEditor.getStyleClass().add("code-area");
        variablesEditor.setPromptText("variables (JSON, optional)");
        variablesEditor.setPrefRowCount(5);

        Label qLbl = sectionLabel("SUBSCRIPTION");
        Label vLbl = sectionLabel("VARIABLES");
        VBox left = new VBox(4, qLbl, queryEditor, vLbl, variablesEditor);
        left.setPadding(new Insets(8));
        VBox.setVgrow(queryEditor, Priority.ALWAYS);

        messageList.setPlaceholder(new Label("No events yet — Start the subscription"));
        VBox right = new VBox(4, sectionLabel("LIVE EVENTS"), messageList);
        right.setPadding(new Insets(8));
        VBox.setVgrow(messageList, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.45);
        return sp;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-title");
        return l;
    }

    private void toggle() {
        if (active) { stop(); return; }
        start();
    }

    private void start() {
        String raw = urlField.getText().trim();
        if (raw.isEmpty()) { statusLabel.setText("Enter a WebSocket endpoint first"); return; }
        String url = Env.resolve(raw);
        final String query = Env.resolve(queryEditor.getText());
        final String variables = Env.resolve(variablesEditor.getText());
        operationId = Long.toString(System.currentTimeMillis());

        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        startBtn.setDisable(true);
        logger.accept("GraphQL subscription → " + url);

        service.connect(url, new WebSocketService.Listener() {
            @Override public void onOpen() {
                service.sendText(GraphQLWsProtocol.connectionInit());
                Platform.runLater(() -> {
                    active = true;
                    startBtn.setDisable(false);
                    startBtn.setText("Stop");
                    statusLabel.getStyleClass().setAll("status-2xx");
                    statusLabel.setText("● Connected — negotiating…");
                    append("⇆ connected; sent connection_init");
                });
            }
            @Override public void onText(String message) {
                handleServerMessage(message, query, variables);
            }
            @Override public void onClosed(int code, String reason) {
                Platform.runLater(() -> {
                    active = false;
                    startBtn.setDisable(false);
                    startBtn.setText("Start");
                    statusLabel.getStyleClass().setAll("meta-label");
                    statusLabel.setText("Closed (" + code + " " + reason + ")");
                    append("⇆ closed: " + code + " " + reason);
                });
            }
            @Override public void onError(Throwable error) {
                Platform.runLater(() -> {
                    active = false;
                    startBtn.setDisable(false);
                    startBtn.setText("Start");
                    statusLabel.getStyleClass().setAll("status-err");
                    statusLabel.setText("Error: " + error.getMessage());
                    append("⚠ error: " + error.getMessage());
                    logger.accept("GraphQL subscription error: " + error.getMessage());
                });
            }
        }, null, GraphQLWsProtocol.SUBPROTOCOL);
    }

    private void handleServerMessage(String message, String query, String variables) {
        GraphQLWsProtocol.ServerMessage msg = GraphQLWsProtocol.parse(message);
        String type = msg.type();
        if (type == null) { Platform.runLater(() -> append("◀ " + oneLine(message))); return; }
        switch (type) {
            case GraphQLWsProtocol.CONNECTION_ACK -> {
                service.sendText(GraphQLWsProtocol.subscribe(operationId, query, variables));
                Platform.runLater(() -> {
                    statusLabel.getStyleClass().setAll("status-2xx");
                    statusLabel.setText("● Subscribed");
                    append("✓ connection_ack; sent subscribe (" + operationId + ")");
                });
            }
            case GraphQLWsProtocol.NEXT ->
                    Platform.runLater(() -> append("◀ " + oneLine(msg.payload())));
            case GraphQLWsProtocol.ERROR ->
                    Platform.runLater(() -> {
                        statusLabel.getStyleClass().setAll("status-err");
                        statusLabel.setText("✖ subscription error");
                        append("⚠ error: " + oneLine(msg.payload()));
                    });
            case GraphQLWsProtocol.COMPLETE ->
                    Platform.runLater(() -> {
                        append("⇲ complete");
                        statusLabel.setText("Completed");
                        stopSocket();
                    });
            case GraphQLWsProtocol.PING -> service.sendText(GraphQLWsProtocol.pong());
            case GraphQLWsProtocol.PONG -> { /* keep-alive ack — nothing to do */ }
            default -> Platform.runLater(() -> append("◀ [" + type + "] " + oneLine(msg.payload())));
        }
    }

    private void stop() {
        if (active) {
            try { service.sendText(GraphQLWsProtocol.complete(operationId)); } catch (Exception ignore) {}
            append("▪ sent complete (" + operationId + ")");
        }
        stopSocket();
    }

    private void stopSocket() {
        service.close();
        active = false;
        startBtn.setText("Start");
        startBtn.setDisable(false);
    }

    private void append(String line) {
        messages.add(LocalTime.now().format(TIME) + "  " + line);
        messageList.scrollTo(messages.size() - 1);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
