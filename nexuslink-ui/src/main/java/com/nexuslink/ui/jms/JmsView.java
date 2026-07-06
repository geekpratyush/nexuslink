package com.nexuslink.ui.jms;

import com.nexuslink.protocol.jms.JmsService;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * JMS client tab — connect to a broker (ActiveMQ Artemis / generic JMS over the core wire), send
 * Text/Bytes/Map messages with custom string properties, browse a queue non-destructively, and
 * consume (receive) one message at a time. A DLQ shortcut peeks the broker's dead-letter address.
 * {@code ${VAR}} placeholders in every field are resolved against the active environment at send /
 * browse / consume time. Built on {@link JmsService}.
 */
public final class JmsView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final JmsService service = new JmsService();

    private final TextField urlField = new TextField("tcp://localhost:61616");
    private final TextField userField = new TextField();
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // Send tab
    private final TextField sendQueue = new TextField();
    private final ComboBox<JmsService.MessageType> typeBox =
            new ComboBox<>(FXCollections.observableArrayList(JmsService.MessageType.values()));
    private final TextArea sendBody = new TextArea();
    private final ObservableList<Prop> props = FXCollections.observableArrayList();
    private final Label sendStatus = new Label();

    // Browse / consume tab
    private final TextField browseQueue = new TextField();
    private final Spinner<Integer> maxSpinner = new Spinner<>(1, 5000, 50, 10);
    private final TableView<JmsService.JmsMessage> browseTable = new TableView<>();
    private final TextArea detailArea = new TextArea();
    private final TextField consumeTimeout = new TextField("2000");

    private final TextArea messageLog = new TextArea();

    private Consumer<String> logger = s -> {};

    public JmsView() {
        getStyleClass().add("jms-view");
        typeBox.setValue(JmsService.MessageType.TEXT);
        setTop(buildBar());
        setCenter(buildTabs());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the broker URL (and optional credentials) when opening a saved/sample connection. */
    public void prefill(String url, String user, String password) {
        if (url != null && !url.isBlank()) urlField.setText(url);
        if (user != null && !user.isBlank()) userField.setText(user);
        if (password != null && !password.isBlank()) passField.setText(password);
    }

    // ---- top connection bar ----

    private VBox buildBar() {
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("tcp://host:61616  ·  (tcp)://host:port");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        userField.getStyleClass().add("nl-field");
        userField.setPromptText("user (optional)");
        userField.setPrefWidth(140);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(140);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        com.nexuslink.ui.hint.HelpButton help = new com.nexuslink.ui.hint.HelpButton("rabbitmq", "JMS messaging help");

        HBox row1 = new HBox(8, label("Broker:"), urlField, connectBtn, help);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Auth:"), userField, passField);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, statusRow);
    }

    private TabPane buildTabs() {
        Tab send = new Tab("Send", buildSend());
        send.setClosable(false);
        Tab browse = new Tab("Browse & Consume", buildBrowse());
        browse.setClosable(false);

        TabPane tabs = new TabPane(send, browse);
        messageLog.setEditable(false);
        messageLog.getStyleClass().add("code-area");
        messageLog.setPrefRowCount(6);
        TitledPane logPane = new TitledPane("Activity", messageLog);
        logPane.setExpanded(true);

        BorderPane wrap = new BorderPane(tabs);
        wrap.setBottom(logPane);
        return new TabPane(new Tab("JMS", wrap) {{ setClosable(false); }});
    }

    // ---- Send tab ----

    private VBox buildSend() {
        sendQueue.getStyleClass().add("nl-field");
        sendQueue.setPromptText("queue name  (e.g. orders  ·  ${QUEUE})");
        typeBox.setPrefWidth(120);
        sendBody.getStyleClass().add("code-area");
        sendBody.setPromptText("Message body. For Map type, one key=value per line.");
        sendBody.setPrefRowCount(8);

        HBox top = new HBox(8, label("Queue:"), sendQueue, label("Type:"), typeBox);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sendQueue, Priority.ALWAYS);

        Button sendBtn = new Button("Send");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> send());
        sendStatus.getStyleClass().add("meta-label");
        HBox actions = new HBox(10, sendBtn, sendStatus);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, top, label("Body:"), sendBody,
                buildPropsEditor(), actions);
        box.setPadding(new Insets(12));
        VBox.setVgrow(sendBody, Priority.ALWAYS);
        return box;
    }

    /** JMS standard + custom string properties as an editable key/value table. */
    private VBox buildPropsEditor() {
        TableView<Prop> table = new TableView<>(props);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(120);
        table.setPlaceholder(new Label("No properties — add JMS or custom headers (e.g. JMSXGroupID, priority)"));

        TableColumn<Prop, String> keyCol = new TableColumn<>("Property");
        keyCol.setCellValueFactory(new PropertyValueFactory<>("key"));
        keyCol.setCellFactory(TextFieldTableCell.forTableColumn());
        keyCol.setOnEditCommit(ev -> ev.getRowValue().setKey(ev.getNewValue()));

        TableColumn<Prop, String> valCol = new TableColumn<>("Value");
        valCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valCol.setCellFactory(TextFieldTableCell.forTableColumn());
        valCol.setOnEditCommit(ev -> ev.getRowValue().setValue(ev.getNewValue()));

        table.getColumns().add(keyCol);
        table.getColumns().add(valCol);

        Button add = new Button("+ Property");
        add.getStyleClass().add("btn-secondary");
        add.setOnAction(e -> props.add(new Prop("", "")));
        Button remove = new Button("Remove");
        remove.getStyleClass().add("btn-secondary");
        remove.setOnAction(e -> {
            Prop sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) props.remove(sel);
        });
        HBox bar = new HBox(8, label("Properties:"), add, remove);
        bar.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, bar, table);
    }

    private void send() {
        if (!ensureConnected(sendStatus)) return;
        String queue = Env.resolve(sendQueue.getText().trim());
        if (queue.isEmpty()) { warn(sendStatus, "Enter a queue name"); return; }
        JmsService.MessageType type = typeBox.getValue();
        String body = Env.resolve(sendBody.getText());
        Map<String, String> properties = new LinkedHashMap<>();
        for (Prop p : props) {
            if (!p.getKey().isBlank()) properties.put(Env.resolve(p.getKey().trim()), Env.resolve(p.getValue()));
        }
        sendStatus.getStyleClass().setAll("meta-label");
        sendStatus.setText("Sending…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                return service.sendMessage(queue, type, body, properties);
            }
        };
        task.setOnSucceeded(e -> {
            sendStatus.getStyleClass().setAll("status-2xx");
            sendStatus.setText("✓ sent  id=" + task.getValue());
            append("▶ " + type + " → " + queue + "  (" + body.length() + " chars, "
                    + properties.size() + " props)");
            logger.accept("JMS sent " + type + " → " + queue);
        });
        task.setOnFailed(e -> {
            sendStatus.getStyleClass().setAll("status-err");
            sendStatus.setText("✖ " + task.getException().getMessage());
            append("⚠ send failed: " + task.getException().getMessage());
        });
        runBg(task, "jms-send");
    }

    // ---- Browse / consume tab ----

    private VBox buildBrowse() {
        browseQueue.getStyleClass().add("nl-field");
        browseQueue.setPromptText("queue name to peek / receive");
        HBox.setHgrow(browseQueue, Priority.ALWAYS);
        maxSpinner.setEditable(true);
        maxSpinner.setPrefWidth(90);

        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> browse());
        Button dlqBtn = new Button("Browse DLQ");
        dlqBtn.getStyleClass().add("btn-secondary");
        dlqBtn.setTooltip(new Tooltip("Peek the broker's dead-letter address (Artemis default: DLQ)"));
        dlqBtn.setOnAction(e -> { browseQueue.setText("DLQ"); browse(); });

        consumeTimeout.setPrefWidth(80);
        Button receiveBtn = new Button("Receive one");
        receiveBtn.getStyleClass().add("btn-primary");
        receiveBtn.setTooltip(new Tooltip("Consume (remove) one message, waiting up to the timeout"));
        receiveBtn.setOnAction(e -> receive());

        HBox top = new HBox(8, label("Queue:"), browseQueue, label("Max:"), maxSpinner, browseBtn, dlqBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox consumeRow = new HBox(8, label("Receive timeout (ms):"), consumeTimeout, receiveBtn);
        consumeRow.setAlignment(Pos.CENTER_LEFT);

        buildBrowseColumns();
        browseTable.setPlaceholder(com.nexuslink.ui.hint.EmptyState.of("topic", "Nothing browsed yet",
                "Enter a queue and press Browse to peek its messages without consuming them."));
        browseTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> showDetail(nv));
        VBox.setVgrow(browseTable, Priority.ALWAYS);

        detailArea.setEditable(false);
        detailArea.getStyleClass().add("code-area");
        detailArea.setPrefRowCount(6);
        detailArea.setPromptText("Select a message to see its body and properties.");

        SplitPane split = new SplitPane(browseTable, detailArea);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox box = new VBox(10, top, consumeRow, split);
        box.setPadding(new Insets(12));
        return box;
    }

    private void buildBrowseColumns() {
        TableColumn<JmsService.JmsMessage, String> idCol = new TableColumn<>("Message ID");
        idCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().messageId()));
        idCol.setPrefWidth(240);
        TableColumn<JmsService.JmsMessage, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().type()));
        typeCol.setPrefWidth(70);
        TableColumn<JmsService.JmsMessage, String> bodyCol = new TableColumn<>("Body");
        bodyCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(oneLine(c.getValue().body())));
        bodyCol.setPrefWidth(320);
        TableColumn<JmsService.JmsMessage, String> propCol = new TableColumn<>("Props");
        propCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().properties().size())));
        propCol.setPrefWidth(60);
        browseTable.getColumns().add(idCol);
        browseTable.getColumns().add(typeCol);
        browseTable.getColumns().add(bodyCol);
        browseTable.getColumns().add(propCol);
    }

    private void showDetail(JmsService.JmsMessage m) {
        if (m == null) { detailArea.clear(); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("ID:   ").append(m.messageId()).append('\n');
        sb.append("Type: ").append(m.type()).append('\n');
        if (!m.properties().isEmpty()) {
            sb.append("\nProperties:\n");
            m.properties().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        }
        sb.append("\nBody:\n").append(m.body());
        detailArea.setText(sb.toString());
    }

    private void browse() {
        if (!ensureConnected(statusLabel)) return;
        String queue = Env.resolve(browseQueue.getText().trim());
        if (queue.isEmpty()) { append("⚠ enter a queue to browse"); return; }
        int max = maxSpinner.getValue();
        Task<java.util.List<JmsService.JmsMessage>> task = new Task<>() {
            @Override protected java.util.List<JmsService.JmsMessage> call() throws Exception {
                return service.browse(queue, max);
            }
        };
        task.setOnSucceeded(e -> {
            browseTable.getItems().setAll(task.getValue());
            append("👁 browsed " + queue + " — " + task.getValue().size() + " message(s)");
        });
        task.setOnFailed(e -> append("⚠ browse failed: " + task.getException().getMessage()));
        runBg(task, "jms-browse");
    }

    private void receive() {
        if (!ensureConnected(statusLabel)) return;
        String queue = Env.resolve(browseQueue.getText().trim());
        if (queue.isEmpty()) { append("⚠ enter a queue to receive from"); return; }
        long timeout = parseLong(consumeTimeout.getText(), 2000);
        Task<JmsService.JmsMessage> task = new Task<>() {
            @Override protected JmsService.JmsMessage call() throws Exception {
                return service.receive(queue, timeout);
            }
        };
        task.setOnSucceeded(e -> {
            JmsService.JmsMessage m = task.getValue();
            if (m == null) { append("… no message on " + queue + " within " + timeout + "ms"); return; }
            showDetail(m);
            append("◀ received from " + queue + "  id=" + m.messageId());
            logger.accept("JMS received ← " + queue);
        });
        task.setOnFailed(e -> append("⚠ receive failed: " + task.getException().getMessage()));
        runBg(task, "jms-receive");
    }

    // ---- connection ----

    private void toggleConnect() {
        if (service.isConnected()) {
            service.close();
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("Disconnected");
            connectBtn.setText("Connect");
            append("⇆ disconnected");
            return;
        }
        String url = Env.resolve(urlField.getText().trim());
        if (url.isEmpty()) { statusLabel.setText("Enter a broker URL"); return; }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("JMS connect → " + url);
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                service.connect(url, Env.resolve(userField.getText().trim()), Env.resolve(passField.getText()));
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + url);
            connectBtn.setText("Disconnect");
            connectBtn.setDisable(false);
            append("⇆ connected to " + url);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            connectBtn.setDisable(false);
            append("⚠ connect failed: " + task.getException().getMessage());
        });
        runBg(task, "jms-connect");
    }

    private boolean ensureConnected(Label status) {
        if (service.isConnected()) return true;
        warn(status, "Not connected");
        return false;
    }

    // ---- helpers ----

    private void warn(Label status, String msg) {
        status.getStyleClass().setAll("status-err");
        status.setText(msg);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static long parseLong(String s, long dflt) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return dflt; }
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void append(String line) {
        String stamped = LocalTime.now().format(TIME) + "  " + line + "\n";
        if (Platform.isFxApplicationThread()) messageLog.appendText(stamped);
        else Platform.runLater(() -> messageLog.appendText(stamped));
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    /** A mutable JMS property row for the editable table. */
    public static final class Prop {
        private String key;
        private String value;
        public Prop(String key, String value) { this.key = key; this.value = value; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
