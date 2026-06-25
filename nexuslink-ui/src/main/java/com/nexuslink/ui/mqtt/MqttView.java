package com.nexuslink.ui.mqtt;

import com.nexuslink.protocol.mqtt.MqttService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * MQTT client tab — connect to a broker (tcp/ssl/ws), subscribe to topic filters, publish
 * messages with a chosen QoS / retained flag, and watch a live message log. Built on Eclipse Paho.
 */
public final class MqttView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final MqttService service = new MqttService();

    private final TextField brokerField = new TextField("tcp://broker.hivemq.com:1883");
    private final TextField clientIdField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final TextField subTopic = new TextField();
    private final ComboBox<Integer> subQos = new ComboBox<>();

    private final TextField pubTopic = new TextField();
    private final ComboBox<Integer> pubQos = new ComboBox<>();
    private final CheckBox pubRetained = new CheckBox("Retained");
    private final TextArea pubPayload = new TextArea();
    private final Label pubStatus = new Label();

    private final TextArea messageLog = new TextArea();

    private Consumer<String> logger = s -> {};

    public MqttView() {
        getStyleClass().add("mqtt-view");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the broker URI (and optional credentials) when opening a saved/sample connection. */
    public void prefill(String broker, String user, String password) {
        if (broker != null && !broker.isBlank()) brokerField.setText(broker);
        if (user != null && !user.isBlank()) userField.setText(user);
        if (password != null && !password.isBlank()) passField.setText(password);
    }

    private VBox buildBar() {
        brokerField.getStyleClass().add("nl-field");
        brokerField.setPromptText("tcp://host:1883  ·  ssl://host:8883");
        HBox.setHgrow(brokerField, Priority.ALWAYS);
        clientIdField.getStyleClass().add("nl-field");
        clientIdField.setPromptText("client id (auto)");
        clientIdField.setPrefWidth(150);
        userField.getStyleClass().add("nl-field");
        userField.setPromptText("user (optional)");
        userField.setPrefWidth(130);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(130);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("mqtt"));

        HBox row1 = new HBox(8, label("Broker:"), brokerField, connectBtn, helpBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Client:"), clientIdField, label("Auth:"), userField, passField);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, statusRow);
    }

    private VBox buildBody() {
        // Subscribe row
        subTopic.getStyleClass().add("nl-field");
        subTopic.setPromptText("topic filter, e.g. sensors/#");
        HBox.setHgrow(subTopic, Priority.ALWAYS);
        subQos.getItems().addAll(0, 1, 2);
        subQos.setValue(0);
        Button subBtn = new Button("Subscribe");
        subBtn.getStyleClass().add("btn-secondary");
        subBtn.setOnAction(e -> subscribe());
        Button unsubBtn = new Button("Unsubscribe");
        unsubBtn.getStyleClass().add("btn-secondary");
        unsubBtn.setOnAction(e -> unsubscribe());
        HBox subRow = new HBox(8, label("Subscribe:"), subTopic, label("QoS:"), subQos, subBtn, unsubBtn);
        subRow.setAlignment(Pos.CENTER_LEFT);

        // Publish panel
        pubTopic.getStyleClass().add("nl-field");
        pubTopic.setPromptText("topic, e.g. sensors/temp");
        HBox.setHgrow(pubTopic, Priority.ALWAYS);
        pubQos.getItems().addAll(0, 1, 2);
        pubQos.setValue(0);
        pubPayload.getStyleClass().add("code-area");
        pubPayload.setPromptText("message payload");
        pubPayload.setPrefRowCount(4);
        pubStatus.getStyleClass().add("meta-label");
        Button pubBtn = new Button("Publish");
        pubBtn.getStyleClass().add("btn-primary");
        pubBtn.setOnAction(e -> publish());
        HBox pubRow = new HBox(8, label("Publish:"), pubTopic, label("QoS:"), pubQos, pubRetained, pubBtn, pubStatus);
        pubRow.setAlignment(Pos.CENTER_LEFT);

        // Message log
        messageLog.getStyleClass().add("code-area");
        messageLog.setEditable(false);
        messageLog.setPromptText("Subscribed messages and publish acks appear here…");
        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> messageLog.clear());
        HBox logHeader = new HBox(8, label("Messages:"), clear);
        logHeader.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, subRow, new Separator(), pubRow, pubPayload, new Separator(), logHeader, messageLog);
        box.setPadding(new Insets(10));
        VBox.setVgrow(messageLog, Priority.ALWAYS);
        return box;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void toggleConnect() {
        if (service.isConnected()) {
            service.close();
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("Disconnected");
            connectBtn.setText("Connect");
            append("⇆ disconnected");
            return;
        }
        String broker = brokerField.getText().trim();
        if (broker.isEmpty()) { statusLabel.setText("Enter a broker URI"); return; }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("MQTT connect → " + broker);

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                service.connect(broker, clientIdField.getText().trim(), userField.getText().trim(),
                        passField.getText(), true, "", "", 0);
                service.setListener(new MqttService.MessageListener() {
                    @Override public void onMessage(MqttService.Incoming m) {
                        Platform.runLater(() -> append("◀ " + m.topic() + "  (q" + m.qos()
                                + (m.retained() ? ",retained" : "") + ")  " + m.payload()));
                    }
                    @Override public void onConnectionLost(Throwable cause) {
                        Platform.runLater(() -> {
                            statusLabel.getStyleClass().setAll("status-err");
                            statusLabel.setText("Connection lost: " + cause.getMessage());
                            connectBtn.setText("Connect");
                            append("⚠ connection lost: " + cause.getMessage());
                        });
                    }
                });
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + broker);
            logger.accept("MQTT connected — " + broker);
            connectBtn.setText("Disconnect");
            connectBtn.setDisable(false);
            append("⇆ connected to " + broker);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("MQTT connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task, "mqtt-connect");
    }

    private void subscribe() {
        String topic = subTopic.getText().trim();
        if (topic.isEmpty() || !service.isConnected()) return;
        int qos = subQos.getValue();
        runAction(() -> service.subscribe(topic, qos),
                () -> append("⊕ subscribed " + topic + " (q" + qos + ")"),
                err -> append("⚠ subscribe failed: " + err.getMessage()));
    }

    private void unsubscribe() {
        String topic = subTopic.getText().trim();
        if (topic.isEmpty() || !service.isConnected()) return;
        runAction(() -> service.unsubscribe(topic),
                () -> append("⊖ unsubscribed " + topic),
                err -> append("⚠ unsubscribe failed: " + err.getMessage()));
    }

    private void publish() {
        String topic = pubTopic.getText().trim();
        if (topic.isEmpty()) { pubStatus.setText("Enter a topic"); return; }
        if (!service.isConnected()) { pubStatus.setText("Not connected"); return; }
        int qos = pubQos.getValue();
        boolean retained = pubRetained.isSelected();
        String payload = pubPayload.getText();
        pubStatus.getStyleClass().setAll("meta-label");
        pubStatus.setText("Publishing…");
        runAction(() -> service.publish(topic, payload, qos, retained),
                () -> {
                    pubStatus.getStyleClass().setAll("status-2xx");
                    pubStatus.setText("✓ sent");
                    append("▶ " + topic + "  (q" + qos + (retained ? ",retained" : "") + ")  " + payload);
                    logger.accept("MQTT published → " + topic);
                },
                err -> {
                    pubStatus.getStyleClass().setAll("status-err");
                    pubStatus.setText("✖ " + err.getMessage());
                });
    }

    /** Runs a throwing broker action on a background thread, applying results on the FX thread. */
    private void runAction(ThrowingRunnable action, Runnable onOk, Consumer<Throwable> onErr) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { action.run(); return null; }
        };
        task.setOnSucceeded(e -> onOk.run());
        task.setOnFailed(e -> onErr.accept(task.getException()));
        runBg(task, "mqtt-action");
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private void append(String line) {
        messageLog.appendText(LocalTime.now().format(TIME) + "  " + line + "\n");
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
