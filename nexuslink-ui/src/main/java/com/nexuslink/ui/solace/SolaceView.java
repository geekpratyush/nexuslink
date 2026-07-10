package com.nexuslink.ui.solace;

import com.nexuslink.protocol.solace.SolaceConnectionProfile;
import com.nexuslink.protocol.solace.SolaceJcsmpService;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Solace PubSub+ client tab — connect to a Message VPN over the SMF wire (JCSMP) and exercise both
 * Solace delivery models:
 *
 * <ul>
 *   <li><b>Direct</b> — best-effort pub/sub on topics. Add a topic subscription (with {@code *}/{@code >}
 *       wildcards) and watch matching messages stream into a table, or publish a Direct message.</li>
 *   <li><b>Guaranteed</b> — persistent messaging via a queue endpoint. Provision a queue, publish a
 *       persistent message to it, browse it non-destructively, or replay already-delivered messages
 *       from the broker's replay log.</li>
 * </ul>
 *
 * {@code ${VAR}} placeholders in fields resolve against the active environment. Built on
 * {@link SolaceJcsmpService}; subscription callbacks arrive on JCSMP's consumer thread and are hopped
 * back to the FX thread here.
 */
public final class SolaceView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final SolaceJcsmpService service = new SolaceJcsmpService();

    private final TextField hostField = new TextField("tcp://localhost:55555");
    private final TextField vpnField = new TextField("default");
    private final TextField userField = new TextField("admin");
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // Direct tab
    private final TextField subFilter = new TextField();
    private final TextField directTopic = new TextField();
    private final TextArea directBody = new TextArea();
    private final ObservableList<SolaceJcsmpService.SolaceMessage> received = FXCollections.observableArrayList();
    private final TableView<SolaceJcsmpService.SolaceMessage> directTable = new TableView<>(received);
    private final Label directStatus = new Label();

    // Guaranteed tab
    private final TextField queueField = new TextField();
    private final TextArea guaranteedBody = new TextArea();
    private final Spinner<Integer> maxSpinner = new Spinner<>(1, 5000, 50, 10);
    private final ObservableList<SolaceJcsmpService.SolaceMessage> spooled = FXCollections.observableArrayList();
    private final TableView<SolaceJcsmpService.SolaceMessage> queueTable = new TableView<>(spooled);
    private final Label guaranteedStatus = new Label();

    private final TextArea messageLog = new TextArea();

    private Consumer<String> logger = s -> {};

    public SolaceView() {
        getStyleClass().add("solace-view");
        setTop(buildBar());
        setCenter(buildTabs());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the connection coordinates when opening a saved/sample connection. */
    public void prefill(String host, String vpn, String user) {
        if (host != null && !host.isBlank()) hostField.setText(host);
        if (vpn != null && !vpn.isBlank()) vpnField.setText(vpn);
        if (user != null && !user.isBlank()) userField.setText(user);
    }

    // ---- top connection bar ----

    private VBox buildBar() {
        hostField.getStyleClass().add("nl-field");
        hostField.setPromptText("host  (e.g. tcp://localhost:55555)");
        HBox.setHgrow(hostField, Priority.ALWAYS);
        style(vpnField, "message VPN", 140);
        style(userField, "client username", 140);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(140);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        com.nexuslink.ui.hint.HelpButton help = new com.nexuslink.ui.hint.HelpButton("rabbitmq", "Solace PubSub+ help");

        HBox row1 = new HBox(8, label("Host:"), hostField, connectBtn, help);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("VPN:"), vpnField, label("Auth:"), userField, passField);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, statusRow);
    }

    private TabPane buildTabs() {
        Tab direct = new Tab("Direct (topics)", buildDirect());
        direct.setClosable(false);
        Tab guaranteed = new Tab("Guaranteed (queues)", buildGuaranteed());
        guaranteed.setClosable(false);

        TabPane tabs = new TabPane(direct, guaranteed);
        messageLog.setEditable(false);
        messageLog.getStyleClass().add("code-area");
        messageLog.setPrefRowCount(6);
        TitledPane logPane = new TitledPane("Activity", messageLog);
        logPane.setExpanded(true);

        BorderPane wrap = new BorderPane(tabs);
        wrap.setBottom(logPane);
        return new TabPane(new Tab("Solace", wrap) {{ setClosable(false); }});
    }

    // ---- Direct (topics) tab ----

    private Region buildDirect() {
        subFilter.getStyleClass().add("nl-field");
        subFilter.setPromptText("topic filter  (e.g. orders/>  ·  a/*/c)");
        HBox.setHgrow(subFilter, Priority.ALWAYS);
        Button subBtn = new Button("Subscribe");
        subBtn.getStyleClass().add("btn-secondary");
        subBtn.setOnAction(e -> subscribe());
        Button unsubBtn = new Button("Unsubscribe");
        unsubBtn.getStyleClass().add("btn-secondary");
        unsubBtn.setOnAction(e -> unsubscribe());
        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> received.clear());
        HBox subRow = new HBox(8, label("Subscribe:"), subFilter, subBtn, unsubBtn, clearBtn);
        subRow.setAlignment(Pos.CENTER_LEFT);

        buildColumns(directTable);
        directTable.setPlaceholder(com.nexuslink.ui.hint.EmptyState.of("topic", "No messages yet",
                "Subscribe to a topic filter; Direct messages published while subscribed appear here."));
        VBox.setVgrow(directTable, Priority.ALWAYS);

        directTopic.getStyleClass().add("nl-field");
        directTopic.setPromptText("topic to publish to  (e.g. orders/eu/new)");
        HBox.setHgrow(directTopic, Priority.ALWAYS);
        directBody.getStyleClass().add("code-area");
        directBody.setPromptText("Direct (best-effort) message body");
        directBody.setPrefRowCount(3);
        Button pubBtn = new Button("Publish Direct");
        pubBtn.getStyleClass().add("btn-primary");
        pubBtn.setOnAction(e -> publishDirect());
        directStatus.getStyleClass().add("meta-label");
        HBox pubRow = new HBox(8, label("Publish:"), directTopic, pubBtn, directStatus);
        pubRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, subRow, directTable, pubRow, directBody);
        box.setPadding(new Insets(12));
        return box;
    }

    // ---- Guaranteed (queues) tab ----

    private Region buildGuaranteed() {
        queueField.getStyleClass().add("nl-field");
        queueField.setPromptText("queue name  (e.g. Q/orders)");
        HBox.setHgrow(queueField, Priority.ALWAYS);
        maxSpinner.setEditable(true);
        maxSpinner.setPrefWidth(90);

        Button provisionBtn = new Button("Provision");
        provisionBtn.getStyleClass().add("btn-secondary");
        provisionBtn.setTooltip(new Tooltip("Create a durable exclusive queue if it does not already exist"));
        provisionBtn.setOnAction(e -> provision());
        Button browseBtn = new Button("Browse");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> browse());
        Button replayBtn = new Button("Replay");
        replayBtn.getStyleClass().add("btn-secondary");
        replayBtn.setTooltip(new Tooltip("Replay already-delivered messages from the broker's replay log (from the beginning)"));
        replayBtn.setOnAction(e -> replay());

        HBox top = new HBox(8, label("Queue:"), queueField, label("Max:"), maxSpinner,
                provisionBtn, browseBtn, replayBtn);
        top.setAlignment(Pos.CENTER_LEFT);

        buildColumns(queueTable);
        queueTable.setPlaceholder(com.nexuslink.ui.hint.EmptyState.of("topic", "Nothing browsed yet",
                "Provision a queue, publish to it, then Browse to peek its spooled messages."));
        VBox.setVgrow(queueTable, Priority.ALWAYS);

        guaranteedBody.getStyleClass().add("code-area");
        guaranteedBody.setPromptText("Guaranteed (persistent) message body");
        guaranteedBody.setPrefRowCount(3);
        Button pubBtn = new Button("Publish Guaranteed");
        pubBtn.getStyleClass().add("btn-primary");
        pubBtn.setOnAction(e -> publishGuaranteed());
        guaranteedStatus.getStyleClass().add("meta-label");
        HBox pubRow = new HBox(8, pubBtn, guaranteedStatus);
        pubRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, top, queueTable, pubRow, guaranteedBody);
        box.setPadding(new Insets(12));
        return box;
    }

    private void buildColumns(TableView<SolaceJcsmpService.SolaceMessage> table) {
        TableColumn<SolaceJcsmpService.SolaceMessage, String> destCol = new TableColumn<>("Destination");
        destCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().destination()));
        destCol.setPrefWidth(200);
        TableColumn<SolaceJcsmpService.SolaceMessage, String> modeCol = new TableColumn<>("Delivery");
        modeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().deliveryMode()));
        modeCol.setPrefWidth(90);
        TableColumn<SolaceJcsmpService.SolaceMessage, String> prioCol = new TableColumn<>("Prio");
        prioCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().priority())));
        prioCol.setPrefWidth(45);
        TableColumn<SolaceJcsmpService.SolaceMessage, String> bodyCol = new TableColumn<>("Body");
        bodyCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(oneLine(c.getValue().body())));
        bodyCol.setPrefWidth(320);
        table.getColumns().add(destCol);
        table.getColumns().add(modeCol);
        table.getColumns().add(prioCol);
        table.getColumns().add(bodyCol);
    }

    // ---- Direct actions ----

    private void subscribe() {
        if (!ensureConnected(directStatus)) return;
        String filter = Env.resolve(subFilter.getText().trim());
        if (filter.isEmpty()) { warn(directStatus, "Enter a topic filter"); return; }
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                service.subscribe(filter, msg -> Platform.runLater(() -> received.add(0, msg)));
                return null;
            }
        };
        task.setOnSucceeded(e -> { ok(directStatus, "subscribed " + filter); append("＋ subscribed " + filter); });
        task.setOnFailed(e -> { warn(directStatus, task.getException().getMessage());
            append("⚠ subscribe failed: " + task.getException().getMessage()); });
        runBg(task, "solace-sub");
    }

    private void unsubscribe() {
        if (!ensureConnected(directStatus)) return;
        String filter = Env.resolve(subFilter.getText().trim());
        if (filter.isEmpty()) { warn(directStatus, "Enter a topic filter"); return; }
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { service.unsubscribe(filter); return null; }
        };
        task.setOnSucceeded(e -> { ok(directStatus, "unsubscribed " + filter); append("－ unsubscribed " + filter); });
        task.setOnFailed(e -> warn(directStatus, task.getException().getMessage()));
        runBg(task, "solace-unsub");
    }

    private void publishDirect() {
        if (!ensureConnected(directStatus)) return;
        String topic = Env.resolve(directTopic.getText().trim());
        if (topic.isEmpty()) { warn(directStatus, "Enter a topic to publish to"); return; }
        String body = Env.resolve(directBody.getText());
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { service.publishDirect(topic, body); return null; }
        };
        task.setOnSucceeded(e -> { ok(directStatus, "published → " + topic);
            append("▶ Direct → " + topic + "  (" + body.length() + " chars)"); logger.accept("Solace Direct → " + topic); });
        task.setOnFailed(e -> { warn(directStatus, task.getException().getMessage());
            append("⚠ publish failed: " + task.getException().getMessage()); });
        runBg(task, "solace-pub-direct");
    }

    // ---- Guaranteed actions ----

    private void provision() {
        if (!ensureConnected(guaranteedStatus)) return;
        String queue = Env.resolve(queueField.getText().trim());
        if (queue.isEmpty()) { warn(guaranteedStatus, "Enter a queue name"); return; }
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { service.provisionQueue(queue); return null; }
        };
        task.setOnSucceeded(e -> { ok(guaranteedStatus, "provisioned " + queue); append("🗄 provisioned queue " + queue); });
        task.setOnFailed(e -> { warn(guaranteedStatus, task.getException().getMessage());
            append("⚠ provision failed: " + task.getException().getMessage()); });
        runBg(task, "solace-provision");
    }

    private void publishGuaranteed() {
        if (!ensureConnected(guaranteedStatus)) return;
        String queue = Env.resolve(queueField.getText().trim());
        if (queue.isEmpty()) { warn(guaranteedStatus, "Enter a queue name"); return; }
        String body = Env.resolve(guaranteedBody.getText());
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { service.publishGuaranteed(queue, body); return null; }
        };
        task.setOnSucceeded(e -> { ok(guaranteedStatus, "published → " + queue);
            append("▶ Guaranteed → " + queue + "  (" + body.length() + " chars)"); logger.accept("Solace Guaranteed → " + queue); });
        task.setOnFailed(e -> { warn(guaranteedStatus, task.getException().getMessage());
            append("⚠ publish failed: " + task.getException().getMessage()); });
        runBg(task, "solace-pub-guaranteed");
    }

    private void browse() {
        if (!ensureConnected(guaranteedStatus)) return;
        String queue = Env.resolve(queueField.getText().trim());
        if (queue.isEmpty()) { warn(guaranteedStatus, "Enter a queue name"); return; }
        int max = maxSpinner.getValue();
        Task<java.util.List<SolaceJcsmpService.SolaceMessage>> task = new Task<>() {
            @Override protected java.util.List<SolaceJcsmpService.SolaceMessage> call() throws Exception {
                return service.browseQueue(queue, max, 1000);
            }
        };
        task.setOnSucceeded(e -> { spooled.setAll(task.getValue());
            append("👁 browsed " + queue + " — " + task.getValue().size() + " message(s)"); });
        task.setOnFailed(e -> append("⚠ browse failed: " + task.getException().getMessage()));
        runBg(task, "solace-browse");
    }

    private void replay() {
        if (!ensureConnected(guaranteedStatus)) return;
        String queue = Env.resolve(queueField.getText().trim());
        if (queue.isEmpty()) { warn(guaranteedStatus, "Enter a queue name"); return; }
        int max = maxSpinner.getValue();
        spooled.clear();
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                return service.replayFromQueue(queue, null, max, 1000,
                        msg -> Platform.runLater(() -> spooled.add(msg)));
            }
        };
        task.setOnSucceeded(e -> append("⟲ replayed " + task.getValue() + " message(s) from " + queue));
        task.setOnFailed(e -> append("⚠ replay failed: " + task.getException().getMessage()));
        runBg(task, "solace-replay");
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
        SolaceConnectionProfile profile;
        try {
            profile = SolaceConnectionProfile.single(
                    Env.resolve(hostField.getText().trim()),
                    Env.resolve(vpnField.getText().trim()),
                    Env.resolve(userField.getText().trim()),
                    Env.resolve(passField.getText()));
        } catch (RuntimeException bad) {
            warn(statusLabel, bad.getMessage());
            return;
        }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("Solace connect → " + profile.hostList());
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { service.connect(profile); return null; }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + profile.vpn() + " @ " + profile.hostList());
            connectBtn.setText("Disconnect");
            connectBtn.setDisable(false);
            append("⇆ connected to VPN " + profile.vpn() + " @ " + profile.hostList());
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            connectBtn.setDisable(false);
            append("⚠ connect failed: " + task.getException().getMessage());
        });
        runBg(task, "solace-connect");
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

    private void ok(Label status, String msg) {
        status.getStyleClass().setAll("status-2xx");
        status.setText("✓ " + msg);
    }

    private void warn(Label status, String msg) {
        status.getStyleClass().setAll("status-err");
        status.setText("✖ " + msg);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
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
}
