package com.nexuslink.ui.mqtt;

import com.nexuslink.protocol.mqtt.MqttCodeGenerator;
import com.nexuslink.protocol.mqtt.MqttHistoryEntry;
import com.nexuslink.protocol.mqtt.MqttHistoryStore;
import com.nexuslink.protocol.mqtt.MqttMessageHistory;
import com.nexuslink.protocol.mqtt.MqttService;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /** Persistent message history: the file-backed store, its in-memory model and the table view. */
    private final MqttHistoryStore historyStore;
    private final MqttMessageHistory history = new MqttMessageHistory();
    private final ObservableList<MqttHistoryEntry> historyRows = FXCollections.observableArrayList();
    private final TableView<MqttHistoryEntry> historyTable = new TableView<>(historyRows);
    private final TextField historyTopicFilter = new TextField();
    private final TextField historySearch = new TextField();
    private final Label historyStatus = new Label();

    private Consumer<String> logger = s -> {};

    public MqttView() {
        this(MqttHistoryStore.userDefault());
    }

    /** Test seam: lets a test point the persistent history at a temporary file. */
    public MqttView(MqttHistoryStore historyStore) {
        this.historyStore = historyStore;
        getStyleClass().add("mqtt-view");
        setTop(buildBar());
        setCenter(buildBody());
        loadHistory();
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

        Button codeBtn = new Button("Code…");
        codeBtn.getStyleClass().add("btn-secondary");
        codeBtn.setTooltip(new Tooltip("Generate a publish/subscribe snippet for this broker and topic"));
        codeBtn.setOnAction(e -> com.nexuslink.ui.rest.CodeGenDialog.show(
                getScene() == null ? null : getScene().getWindow(), codeGenRequest()));

        HBox row1 = new HBox(8, label("Broker:"), brokerField, connectBtn, codeBtn, helpBtn);
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

        // Activity log (connection / subscription events)
        messageLog.getStyleClass().add("code-area");
        messageLog.setEditable(false);
        messageLog.setPromptText("Connection and subscription activity appears here…");
        Button clearLog = new Button("Clear");
        clearLog.getStyleClass().add("btn-secondary");
        clearLog.setOnAction(e -> messageLog.clear());
        HBox logHeader = new HBox(8, label("Activity:"), clearLog);
        logHeader.setAlignment(Pos.CENTER_LEFT);
        VBox activityBox = new VBox(6, logHeader, messageLog);
        activityBox.setPadding(new Insets(8));
        VBox.setVgrow(messageLog, Priority.ALWAYS);

        TabPane bottom = new TabPane(
                closedTab("Messages", buildHistoryPane()),
                closedTab("Activity", activityBox));

        VBox box = new VBox(8, subRow, new Separator(), pubRow, pubPayload, new Separator(), bottom);
        box.setPadding(new Insets(10));
        VBox.setVgrow(bottom, Priority.ALWAYS);
        return box;
    }

    private Tab closedTab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    /**
     * The persistent message history: every message sent or received is appended to
     * {@code ~/.nexuslink/mqtt-history.log} and reloaded on the next launch, so a subscription's
     * traffic outlives the session. The topic box filters with real MQTT wildcards.
     */
    private VBox buildHistoryPane() {
        historyTable.getColumns().setAll(
                column("Time", 90, e -> TIME.format(e.timestamp().atZone(ZoneId.systemDefault()))),
                column("Dir", 46, e -> e.direction() == MqttHistoryEntry.Direction.RECEIVED ? "◀ in" : "▶ out"),
                column("Topic", 240, MqttHistoryEntry::topic),
                column("QoS", 46, e -> String.valueOf(e.qos())),
                column("Ret", 46, e -> e.retained() ? "✓" : ""),
                column("Payload", 420, MqttHistoryEntry::payload));
        historyTable.setId("mqtt-history-table");
        historyTopicFilter.setId("mqtt-history-topic-filter");
        historyTable.setPlaceholder(new Label("No messages yet — subscribe to a topic to start recording."));
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        historyTopicFilter.getStyleClass().add("nl-field");
        historyTopicFilter.setPromptText("filter by topic, e.g. sensors/#");
        historyTopicFilter.textProperty().addListener((o, a, b) -> refreshHistoryRows());
        historySearch.getStyleClass().add("nl-field");
        historySearch.setPromptText("payload contains…");
        historySearch.textProperty().addListener((o, a, b) -> refreshHistoryRows());
        HBox.setHgrow(historyTopicFilter, Priority.ALWAYS);
        HBox.setHgrow(historySearch, Priority.ALWAYS);

        Button clearHistory = new Button("Clear history");
        clearHistory.getStyleClass().add("btn-secondary");
        clearHistory.setOnAction(e -> clearHistory());

        historyStatus.getStyleClass().add("meta-label");
        HBox header = new HBox(8, label("Topic:"), historyTopicFilter,
                label("Payload:"), historySearch, clearHistory, historyStatus);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, header, historyTable);
        box.setPadding(new Insets(8));
        VBox.setVgrow(historyTable, Priority.ALWAYS);
        return box;
    }

    private TableColumn<MqttHistoryEntry, String> column(String title, double width,
                                                         java.util.function.Function<MqttHistoryEntry, String> value) {
        TableColumn<MqttHistoryEntry, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(value.apply(c.getValue())));
        return col;
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
        String broker = Env.resolve(brokerField.getText().trim());   // resolve ${VAR} against active environment
        if (broker.isEmpty()) { statusLabel.setText("Enter a broker URI"); return; }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("MQTT connect → " + com.nexuslink.core.security.UriRedactor.redact(broker));

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                service.connect(broker, Env.resolve(clientIdField.getText().trim()),
                        Env.resolve(userField.getText().trim()), Env.resolve(passField.getText()),
                        true, "", "", 0);
                service.setListener(new MqttService.MessageListener() {
                    @Override public void onMessage(MqttService.Incoming m) {
                        Platform.runLater(() -> record(MqttHistoryEntry.received(
                                m.topic(), m.payload(), m.qos(), m.retained())));
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
        String topic = Env.resolve(subTopic.getText().trim());   // resolve ${VAR} in the topic filter
        if (topic.isEmpty() || !service.isConnected()) return;
        int qos = subQos.getValue();
        runAction(() -> service.subscribe(topic, qos),
                () -> append("⊕ subscribed " + topic + " (q" + qos + ")"),
                err -> append("⚠ subscribe failed: " + err.getMessage()));
    }

    private void unsubscribe() {
        String topic = Env.resolve(subTopic.getText().trim());   // resolve ${VAR} in the topic filter
        if (topic.isEmpty() || !service.isConnected()) return;
        runAction(() -> service.unsubscribe(topic),
                () -> append("⊖ unsubscribed " + topic),
                err -> append("⚠ unsubscribe failed: " + err.getMessage()));
    }

    private void publish() {
        String topic = Env.resolve(pubTopic.getText().trim());   // resolve ${VAR} in topic + payload
        if (topic.isEmpty()) { pubStatus.setText("Enter a topic"); return; }
        if (!service.isConnected()) { pubStatus.setText("Not connected"); return; }
        int qos = pubQos.getValue();
        boolean retained = pubRetained.isSelected();
        String payload = Env.resolve(pubPayload.getText());
        pubStatus.getStyleClass().setAll("meta-label");
        pubStatus.setText("Publishing…");
        runAction(() -> service.publish(topic, payload, qos, retained),
                () -> {
                    pubStatus.getStyleClass().setAll("status-2xx");
                    pubStatus.setText("✓ sent");
                    record(MqttHistoryEntry.published(topic, payload, qos, retained));
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

    /** The current broker + topic as a code-generation request (see the code-gen SPI). */
    private MqttCodeGenerator.Request codeGenRequest() {
        String topic = Env.resolve(pubTopic.getText().trim());
        if (topic.isEmpty()) topic = Env.resolve(subTopic.getText().trim());
        return new MqttCodeGenerator.Request(
                Env.resolve(brokerField.getText().trim()),
                topic,
                pubQos.getValue() == null ? 0 : pubQos.getValue(),
                pubRetained.isSelected(),
                Env.resolve(userField.getText().trim()));
    }

    // ------------------------------------------------------------------ persistent message history

    /** Loads the persisted history (off the FX thread — the file may be large) into the table. */
    private void loadHistory() {
        Task<List<MqttHistoryEntry>> task = new Task<>() {
            @Override protected List<MqttHistoryEntry> call() { return historyStore.load(); }
        };
        task.setOnSucceeded(e -> {
            history.addAll(task.getValue());
            refreshHistoryRows();
        });
        task.setOnFailed(e -> historyStatus.setText("History unavailable"));
        runBg(task, "mqtt-history-load");
    }

    /**
     * Records one message in the in-memory log and appends it to the history file. Called on the FX
     * thread; the (small, append-only) write is handed to a background thread so a slow disk never
     * stalls the message stream.
     */
    private void record(MqttHistoryEntry entry) {
        history.add(entry);
        refreshHistoryRows();
        Thread writer = new Thread(() -> historyStore.append(entry), "mqtt-history-append");
        writer.setDaemon(true);
        writer.start();
    }

    private void refreshHistoryRows() {
        List<MqttHistoryEntry> shown = history.search(historyTopicFilter.getText(), historySearch.getText());
        historyRows.setAll(shown);
        int total = history.size();
        historyStatus.setText(shown.size() == total
                ? total + " message(s)"
                : shown.size() + " of " + total + " message(s)");
        if (!historyRows.isEmpty()) {
            historyTable.scrollTo(historyRows.size() - 1);
        }
    }

    /** Empties both the in-memory log and the persisted file. */
    private void clearHistory() {
        history.clear();
        refreshHistoryRows();
        Thread cleaner = new Thread(historyStore::clear, "mqtt-history-clear");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    private void append(String line) {
        messageLog.appendText(LocalTime.now().format(TIME) + "  " + line + "\n");
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
