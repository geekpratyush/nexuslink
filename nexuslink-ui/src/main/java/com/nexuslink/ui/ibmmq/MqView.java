package com.nexuslink.ui.ibmmq;

import com.nexuslink.protocol.ibmmq.MqConnectionProfile;
import com.nexuslink.protocol.ibmmq.MqNativeService;
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
 * IBM MQ client tab — connect to a queue manager in {@code CLIENT} binding mode over its native
 * (MQI) wire, put text messages with custom {@code usr}-folder properties (carried as an RFH2
 * header), browse a queue non-destructively, get (consume) one message at a time, read a queue's
 * depth, and peek the queue manager's dead-letter queue. {@code ${VAR}} placeholders in every field
 * resolve against the active environment at action time. Built on {@link MqNativeService}.
 *
 * <p>Unlike the JMS tab this surfaces MQ's MQMD fields — message/correlation id (hex), format,
 * priority, persistence, put time, backout count — plus the parsed RFH2 header when present.</p>
 */
public final class MqView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final MqNativeService service = new MqNativeService();

    private final TextField qmgrField = new TextField("QM1");
    private final TextField channelField = new TextField("DEV.APP.SVRCONN");
    private final TextField hostField = new TextField("localhost");
    private final TextField portField = new TextField(String.valueOf(MqConnectionProfile.DEFAULT_PORT));
    private final TextField userField = new TextField("app");
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // Put tab
    private final TextField putQueue = new TextField();
    private final TextArea putBody = new TextArea();
    private final ObservableList<Prop> props = FXCollections.observableArrayList();
    private final Label putStatus = new Label();

    // Browse / get tab
    private final TextField browseQueue = new TextField();
    private final Spinner<Integer> maxSpinner = new Spinner<>(1, 5000, 50, 10);
    private final TableView<MqNativeService.MqMessage> browseTable = new TableView<>();
    private final TextArea detailArea = new TextArea();
    private final TextField getTimeout = new TextField("2000");

    private final TextArea messageLog = new TextArea();

    private Consumer<String> logger = s -> {};

    public MqView() {
        getStyleClass().add("mq-view");
        setTop(buildBar());
        setCenter(buildTabs());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the connection coordinates when opening a saved/sample connection. */
    public void prefill(String queueManager, String channel, String host, int port, String user) {
        if (queueManager != null && !queueManager.isBlank()) qmgrField.setText(queueManager);
        if (channel != null && !channel.isBlank()) channelField.setText(channel);
        if (host != null && !host.isBlank()) hostField.setText(host);
        if (port > 0) portField.setText(String.valueOf(port));
        if (user != null && !user.isBlank()) userField.setText(user);
    }

    // ---- top connection bar ----

    private VBox buildBar() {
        style(qmgrField, "queue manager (e.g. QM1)", 120);
        style(channelField, "SVRCONN channel", 180);
        style(hostField, "host", 140);
        style(portField, "port", 70);
        style(userField, "user (optional)", 120);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(120);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        com.nexuslink.ui.hint.HelpButton help = new com.nexuslink.ui.hint.HelpButton("rabbitmq", "IBM MQ messaging help");

        HBox row1 = new HBox(8, label("Queue mgr:"), qmgrField, label("Channel:"), channelField,
                connectBtn, help);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Host:"), hostField, label("Port:"), portField,
                label("Auth:"), userField, passField);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, statusRow);
    }

    private TabPane buildTabs() {
        Tab put = new Tab("Put", buildPut());
        put.setClosable(false);
        Tab browse = new Tab("Browse & Get", buildBrowse());
        browse.setClosable(false);

        TabPane tabs = new TabPane(put, browse);
        messageLog.setEditable(false);
        messageLog.getStyleClass().add("code-area");
        messageLog.setPrefRowCount(6);
        TitledPane logPane = new TitledPane("Activity", messageLog);
        logPane.setExpanded(true);

        BorderPane wrap = new BorderPane(tabs);
        wrap.setBottom(logPane);
        return new TabPane(new Tab("IBM MQ", wrap) {{ setClosable(false); }});
    }

    // ---- Put tab ----

    private VBox buildPut() {
        putQueue.getStyleClass().add("nl-field");
        putQueue.setPromptText("queue name  (e.g. DEV.QUEUE.1  ·  ${QUEUE})");
        putBody.getStyleClass().add("code-area");
        putBody.setPromptText("Message body. Sent as MQSTR (UTF-8); add properties below to carry an RFH2 usr folder.");
        putBody.setPrefRowCount(8);

        HBox top = new HBox(8, label("Queue:"), putQueue);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(putQueue, Priority.ALWAYS);

        Button putBtn = new Button("Put");
        putBtn.getStyleClass().add("btn-primary");
        putBtn.setOnAction(e -> put());
        putStatus.getStyleClass().add("meta-label");
        HBox actions = new HBox(10, putBtn, putStatus);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, top, label("Body:"), putBody, buildPropsEditor(), actions);
        box.setPadding(new Insets(12));
        VBox.setVgrow(putBody, Priority.ALWAYS);
        return box;
    }

    /** Custom application (usr-folder) message properties as an editable key/value table. */
    private VBox buildPropsEditor() {
        TableView<Prop> table = new TableView<>(props);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(120);
        table.setPlaceholder(new Label("No properties — add usr-folder message properties (written as an RFH2 header)"));

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

    private void put() {
        if (!ensureConnected(putStatus)) return;
        String queue = Env.resolve(putQueue.getText().trim());
        if (queue.isEmpty()) { warn(putStatus, "Enter a queue name"); return; }
        String body = Env.resolve(putBody.getText());
        Map<String, String> properties = new LinkedHashMap<>();
        for (Prop p : props) {
            if (!p.getKey().isBlank()) properties.put(Env.resolve(p.getKey().trim()), Env.resolve(p.getValue()));
        }
        putStatus.getStyleClass().setAll("meta-label");
        putStatus.setText("Putting…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                return service.put(queue, body, properties);
            }
        };
        task.setOnSucceeded(e -> {
            putStatus.getStyleClass().setAll("status-2xx");
            putStatus.setText("✓ put  id=" + task.getValue());
            append("▶ → " + queue + "  (" + body.length() + " chars, " + properties.size() + " props)");
            logger.accept("MQ put → " + queue);
        });
        task.setOnFailed(e -> {
            putStatus.getStyleClass().setAll("status-err");
            putStatus.setText("✖ " + task.getException().getMessage());
            append("⚠ put failed: " + task.getException().getMessage());
        });
        runBg(task, "mq-put");
    }

    // ---- Browse / get tab ----

    private VBox buildBrowse() {
        browseQueue.getStyleClass().add("nl-field");
        browseQueue.setPromptText("queue name to peek / get");
        HBox.setHgrow(browseQueue, Priority.ALWAYS);
        maxSpinner.setEditable(true);
        maxSpinner.setPrefWidth(90);

        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> browse());
        Button depthBtn = new Button("Depth");
        depthBtn.getStyleClass().add("btn-secondary");
        depthBtn.setTooltip(new Tooltip("Report the queue's current message depth"));
        depthBtn.setOnAction(e -> depth());
        Button dlqBtn = new Button("Browse DLQ");
        dlqBtn.getStyleClass().add("btn-secondary");
        dlqBtn.setTooltip(new Tooltip("Peek the queue manager's dead-letter queue"));
        dlqBtn.setOnAction(e -> browseDlq());

        getTimeout.setPrefWidth(80);
        Button getBtn = new Button("Get one");
        getBtn.getStyleClass().add("btn-primary");
        getBtn.setTooltip(new Tooltip("Get (remove) one message, waiting up to the timeout"));
        getBtn.setOnAction(e -> get());

        HBox top = new HBox(8, label("Queue:"), browseQueue, label("Max:"), maxSpinner,
                browseBtn, depthBtn, dlqBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox getRow = new HBox(8, label("Get timeout (ms):"), getTimeout, getBtn);
        getRow.setAlignment(Pos.CENTER_LEFT);

        buildBrowseColumns();
        browseTable.setPlaceholder(com.nexuslink.ui.hint.EmptyState.of("topic", "Nothing browsed yet",
                "Enter a queue and press Browse to peek its messages without consuming them."));
        browseTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> showDetail(nv));
        VBox.setVgrow(browseTable, Priority.ALWAYS);

        detailArea.setEditable(false);
        detailArea.getStyleClass().add("code-area");
        detailArea.setPrefRowCount(7);
        detailArea.setPromptText("Select a message to see its MQMD fields, RFH2 properties, and body.");

        SplitPane split = new SplitPane(browseTable, detailArea);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox box = new VBox(10, top, getRow, split);
        box.setPadding(new Insets(12));
        return box;
    }

    private void buildBrowseColumns() {
        TableColumn<MqNativeService.MqMessage, String> idCol = new TableColumn<>("Message ID");
        idCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().messageId()));
        idCol.setPrefWidth(230);
        TableColumn<MqNativeService.MqMessage, String> fmtCol = new TableColumn<>("Format");
        fmtCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().format()));
        fmtCol.setPrefWidth(70);
        TableColumn<MqNativeService.MqMessage, String> prioCol = new TableColumn<>("Prio");
        prioCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().priority())));
        prioCol.setPrefWidth(45);
        TableColumn<MqNativeService.MqMessage, String> persCol = new TableColumn<>("Persist");
        persCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().persistent() ? "yes" : "no"));
        persCol.setPrefWidth(55);
        TableColumn<MqNativeService.MqMessage, String> bodyCol = new TableColumn<>("Body");
        bodyCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(oneLine(c.getValue().body())));
        bodyCol.setPrefWidth(300);
        browseTable.getColumns().add(idCol);
        browseTable.getColumns().add(fmtCol);
        browseTable.getColumns().add(prioCol);
        browseTable.getColumns().add(persCol);
        browseTable.getColumns().add(bodyCol);
    }

    private void showDetail(MqNativeService.MqMessage m) {
        if (m == null) { detailArea.clear(); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("Message ID:     ").append(m.messageId()).append('\n');
        sb.append("Correlation ID: ").append(m.correlationId()).append('\n');
        sb.append("Format:         ").append(m.format()).append('\n');
        sb.append("Priority:       ").append(m.priority()).append('\n');
        sb.append("Persistent:     ").append(m.persistent()).append('\n');
        sb.append("Put time:       ").append(m.putTime()).append('\n');
        sb.append("Backout count:  ").append(m.backoutCount()).append('\n');
        if (m.hasRfh2() && !m.rfh2().fields().isEmpty()) {
            sb.append("\nRFH2 properties:\n");
            m.rfh2().fields().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        }
        sb.append("\nBody:\n").append(m.body());
        detailArea.setText(sb.toString());
    }

    private void browse() {
        if (!ensureConnected(statusLabel)) return;
        String queue = Env.resolve(browseQueue.getText().trim());
        if (queue.isEmpty()) { append("⚠ enter a queue to browse"); return; }
        int max = maxSpinner.getValue();
        Task<java.util.List<MqNativeService.MqMessage>> task = new Task<>() {
            @Override protected java.util.List<MqNativeService.MqMessage> call() throws Exception {
                return service.browse(queue, max);
            }
        };
        task.setOnSucceeded(e -> {
            browseTable.getItems().setAll(task.getValue());
            append("👁 browsed " + queue + " — " + task.getValue().size() + " message(s)");
        });
        task.setOnFailed(e -> append("⚠ browse failed: " + task.getException().getMessage()));
        runBg(task, "mq-browse");
    }

    private void browseDlq() {
        if (!ensureConnected(statusLabel)) return;
        Task<java.util.List<MqNativeService.MqMessage>> task = new Task<>() {
            @Override protected java.util.List<MqNativeService.MqMessage> call() throws Exception {
                return service.browseDeadLetterQueue(maxSpinner.getValue());
            }
        };
        task.setOnSucceeded(e -> {
            browseTable.getItems().setAll(task.getValue());
            append("💀 browsed dead-letter queue — " + task.getValue().size() + " message(s)");
        });
        task.setOnFailed(e -> append("⚠ DLQ browse failed: " + task.getException().getMessage()));
        runBg(task, "mq-dlq");
    }

    private void depth() {
        if (!ensureConnected(statusLabel)) return;
        String queue = Env.resolve(browseQueue.getText().trim());
        if (queue.isEmpty()) { append("⚠ enter a queue to inspect"); return; }
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception { return service.depth(queue); }
        };
        task.setOnSucceeded(e -> append("📏 " + queue + " depth = " + task.getValue()));
        task.setOnFailed(e -> append("⚠ depth failed: " + task.getException().getMessage()));
        runBg(task, "mq-depth");
    }

    private void get() {
        if (!ensureConnected(statusLabel)) return;
        String queue = Env.resolve(browseQueue.getText().trim());
        if (queue.isEmpty()) { append("⚠ enter a queue to get from"); return; }
        long timeout = parseLong(getTimeout.getText(), 2000);
        Task<MqNativeService.MqMessage> task = new Task<>() {
            @Override protected MqNativeService.MqMessage call() throws Exception {
                return service.get(queue, timeout);
            }
        };
        task.setOnSucceeded(e -> {
            MqNativeService.MqMessage m = task.getValue();
            if (m == null) { append("… no message on " + queue + " within " + timeout + "ms"); return; }
            showDetail(m);
            append("◀ got from " + queue + "  id=" + m.messageId());
            logger.accept("MQ got ← " + queue);
        });
        task.setOnFailed(e -> append("⚠ get failed: " + task.getException().getMessage()));
        runBg(task, "mq-get");
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
        MqConnectionProfile profile;
        try {
            profile = MqConnectionProfile.plain(
                    Env.resolve(qmgrField.getText().trim()),
                    Env.resolve(channelField.getText().trim()),
                    Env.resolve(hostField.getText().trim()),
                    (int) parseLong(portField.getText(), MqConnectionProfile.DEFAULT_PORT),
                    Env.resolve(userField.getText().trim()),
                    Env.resolve(passField.getText()));
        } catch (RuntimeException bad) {
            warn(statusLabel, bad.getMessage());
            return;
        }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("MQ connect → " + profile.connectionName());
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { service.connect(profile); return null; }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + profile.queueManager() + " @ " + profile.connectionName());
            connectBtn.setText("Disconnect");
            connectBtn.setDisable(false);
            append("⇆ connected to " + profile.queueManager() + " @ " + profile.connectionName());
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            connectBtn.setDisable(false);
            append("⚠ connect failed: " + task.getException().getMessage());
        });
        runBg(task, "mq-connect");
    }

    private boolean ensureConnected(Label status) {
        if (service.isConnected()) return true;
        warn(status, "Not connected");
        return false;
    }

    // ---- helpers ----

    private void style(TextField field, String prompt, double width) {
        field.getStyleClass().add("nl-field");
        field.setPromptText(prompt);
        field.setPrefWidth(width);
    }

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

    /** A mutable message-property row for the editable table. */
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
