package com.nexuslink.ui.rabbitmq;

import com.nexuslink.protocol.rabbitmq.BindingInfo;
import com.nexuslink.protocol.rabbitmq.ExchangeInfo;
import com.nexuslink.protocol.rabbitmq.OverviewInfo;
import com.nexuslink.protocol.rabbitmq.QueueInfo;
import com.nexuslink.protocol.rabbitmq.RabbitMqManagementClient;
import com.nexuslink.protocol.rabbitmq.RabbitMqService;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * RabbitMQ client tab — connect to a broker (AMQP 0.9.1), declare exchanges/queues/bindings,
 * publish to an exchange + routing key, and consume deliveries from a queue into a live log.
 * Built on the official {@code amqp-client}. {@code ${VAR}} placeholders in every field are
 * resolved against the active environment at connect/declare/publish/consume time.
 */
public final class RabbitMqView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final RabbitMqService service = new RabbitMqService();

    private final TextField brokerField = new TextField("amqp://localhost:5672");
    private final TextField userField = new TextField("guest");
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final TextField exchangeName = new TextField();
    private final ComboBox<String> exchangeType = new ComboBox<>();
    private final TextField queueName = new TextField();
    private final CheckBox durable = new CheckBox("Durable");
    private final TextField bindRoutingKey = new TextField();

    private final TextField pubExchange = new TextField();
    private final TextField pubRoutingKey = new TextField();
    private final TextArea pubPayload = new TextArea();
    private final Label pubStatus = new Label();

    private final TextField consumeQueue = new TextField();
    private final Button consumeBtn = new Button("Consume");
    private volatile String consumerTag;

    private final TextArea messageLog = new TextArea();

    // Management dashboard (HTTP management API on port 15672).
    private final TextField mgmtHost = new TextField("localhost");
    private final TextField mgmtPort = new TextField(String.valueOf(RabbitMqManagementClient.DEFAULT_PORT));
    private final Button refreshBtn = new Button("Refresh");
    private final Button purgeBtn = new Button("Purge selected queue");
    private final Label mgmtStatus = new Label("Not loaded");
    // Overview stats strip — the headline cluster totals.
    private final Label statConnections = statValue();
    private final Label statChannels = statValue();
    private final Label statQueues = statValue();
    private final Label statMessages = statValue();
    private final Label statConsumers = statValue();
    private final Label overviewInfo = new Label();
    private final TableView<QueueInfo> queuesTable = new TableView<>();
    private final TableView<ExchangeInfo> exchangesTable = new TableView<>();
    private final TableView<BindingInfo> bindingsTable = new TableView<>();

    private Consumer<String> logger = s -> {};

    public RabbitMqView() {
        getStyleClass().add("rabbitmq-view");
        setTop(buildBar());
        setCenter(buildTabs());
    }

    private TabPane buildTabs() {
        Tab messaging = new Tab("Messaging", buildBody());
        messaging.setClosable(false);
        Tab management = new Tab("Management", buildManagement());
        management.setClosable(false);
        return new TabPane(messaging, management);
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
        brokerField.setPromptText("amqp://host:5672  ·  amqps://host:5671  ·  host:port");
        HBox.setHgrow(brokerField, Priority.ALWAYS);
        userField.getStyleClass().add("nl-field");
        userField.setPromptText("user");
        userField.setPrefWidth(130);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(130);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleConnect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("rabbitmq"));

        HBox row1 = new HBox(8, label("Broker:"), brokerField, connectBtn, helpBtn);
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

    private VBox buildBody() {
        // Topology row — declare exchange / queue / binding
        exchangeName.getStyleClass().add("nl-field");
        exchangeName.setPromptText("exchange");
        HBox.setHgrow(exchangeName, Priority.ALWAYS);
        exchangeType.getItems().addAll("direct", "fanout", "topic", "headers");
        exchangeType.setValue("direct");
        Button declExchange = new Button("Declare exchange");
        declExchange.getStyleClass().add("btn-secondary");
        declExchange.setOnAction(e -> declareExchange());

        queueName.getStyleClass().add("nl-field");
        queueName.setPromptText("queue");
        HBox.setHgrow(queueName, Priority.ALWAYS);
        durable.setSelected(true);
        Button declQueue = new Button("Declare queue");
        declQueue.getStyleClass().add("btn-secondary");
        declQueue.setOnAction(e -> declareQueue());

        bindRoutingKey.getStyleClass().add("nl-field");
        bindRoutingKey.setPromptText("routing key");
        bindRoutingKey.setPrefWidth(160);
        Button bindBtn = new Button("Bind");
        bindBtn.getStyleClass().add("btn-secondary");
        bindBtn.setOnAction(e -> bind());

        HBox exRow = new HBox(8, label("Exchange:"), exchangeName, exchangeType, declExchange);
        exRow.setAlignment(Pos.CENTER_LEFT);
        HBox qRow = new HBox(8, label("Queue:"), queueName, durable, declQueue);
        qRow.setAlignment(Pos.CENTER_LEFT);
        HBox bindRow = new HBox(8, label("Bind queue→exchange w/ key:"), bindRoutingKey, bindBtn);
        bindRow.setAlignment(Pos.CENTER_LEFT);

        // Publish panel
        pubExchange.getStyleClass().add("nl-field");
        pubExchange.setPromptText("exchange (blank = default)");
        pubRoutingKey.getStyleClass().add("nl-field");
        pubRoutingKey.setPromptText("routing key / queue name");
        HBox.setHgrow(pubRoutingKey, Priority.ALWAYS);
        pubPayload.getStyleClass().add("code-area");
        pubPayload.setPromptText("message payload");
        pubPayload.setPrefRowCount(4);
        pubStatus.getStyleClass().add("meta-label");
        Button pubBtn = new Button("Publish");
        pubBtn.getStyleClass().add("btn-primary");
        pubBtn.setOnAction(e -> publish());
        HBox pubRow = new HBox(8, label("Publish:"), pubExchange, pubRoutingKey, pubBtn, pubStatus);
        pubRow.setAlignment(Pos.CENTER_LEFT);

        // Consume panel
        consumeQueue.getStyleClass().add("nl-field");
        consumeQueue.setPromptText("queue to consume");
        HBox.setHgrow(consumeQueue, Priority.ALWAYS);
        consumeBtn.getStyleClass().add("btn-secondary");
        consumeBtn.setOnAction(e -> toggleConsume());
        HBox consumeRow = new HBox(8, label("Consume:"), consumeQueue, consumeBtn);
        consumeRow.setAlignment(Pos.CENTER_LEFT);

        // Message log
        messageLog.getStyleClass().add("code-area");
        messageLog.setEditable(false);
        messageLog.setPromptText("Deliveries, publish acks and topology changes appear here…");
        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> messageLog.clear());
        HBox logHeader = new HBox(8, label("Messages:"), clear);
        logHeader.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, exRow, qRow, bindRow, new Separator(),
                pubRow, pubPayload, new Separator(), consumeRow, new Separator(),
                logHeader, messageLog);
        box.setPadding(new Insets(10));
        VBox.setVgrow(messageLog, Priority.ALWAYS);
        return box;
    }

    // ------------------------------------------------------------------
    // Management dashboard — RabbitMQ HTTP management API (port 15672).
    // ------------------------------------------------------------------

    private VBox buildManagement() {
        mgmtHost.getStyleClass().add("nl-field");
        mgmtHost.setPromptText("management host");
        HBox.setHgrow(mgmtHost, Priority.ALWAYS);
        mgmtPort.getStyleClass().add("nl-field");
        mgmtPort.setPromptText("port");
        mgmtPort.setPrefWidth(90);
        refreshBtn.getStyleClass().add("btn-primary");
        refreshBtn.setOnAction(e -> refreshManagement());
        HBox connRow = new HBox(8, label("Manager:"), mgmtHost, label("Port:"), mgmtPort, refreshBtn);
        connRow.setAlignment(Pos.CENTER_LEFT);

        mgmtStatus.getStyleClass().add("meta-label");
        Label credHint = label("Uses the Auth user/password from the bar above.");
        HBox infoRow = new HBox(12, mgmtStatus, credHint);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        overviewInfo.getStyleClass().add("meta-label");

        buildQueuesColumns();
        buildExchangesColumns();
        buildBindingsColumns();

        purgeBtn.getStyleClass().add("btn-secondary");
        purgeBtn.setOnAction(e -> purgeSelectedQueue());
        HBox queueHeader = new HBox(8, label("Queues:"), purgeBtn);
        queueHeader.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(queuesTable, Priority.ALWAYS);
        VBox.setVgrow(exchangesTable, Priority.ALWAYS);
        VBox.setVgrow(bindingsTable, Priority.ALWAYS);

        VBox box = new VBox(8, connRow, infoRow, buildStatsStrip(), overviewInfo, new Separator(),
                queueHeader, queuesTable, new Separator(),
                label("Exchanges:"), exchangesTable, new Separator(),
                label("Bindings:"), bindingsTable);
        box.setPadding(new Insets(10));
        return box;
    }

    /** A horizontal strip of headline cluster stats (populated on every Refresh). */
    private HBox buildStatsStrip() {
        HBox strip = new HBox(0,
                statCell("Connections", statConnections),
                statSep(),
                statCell("Channels", statChannels),
                statSep(),
                statCell("Queues", statQueues),
                statSep(),
                statCell("Consumers", statConsumers),
                statSep(),
                statCell("Messages", statMessages));
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(4, 0, 4, 0));
        return strip;
    }

    private static Label statValue() {
        Label l = new Label("–");
        l.getStyleClass().add("sidebar-title");
        return l;
    }

    private VBox statCell(String caption, Label value) {
        VBox cell = new VBox(2, value, label(caption));
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.setPadding(new Insets(0, 18, 0, 0));
        cell.setMinWidth(90);
        return cell;
    }

    private static Separator statSep() {
        Separator s = new Separator(javafx.geometry.Orientation.VERTICAL);
        HBox.setMargin(s, new Insets(0, 18, 0, 0));
        return s;
    }

    private void buildQueuesColumns() {
        queuesTable.getColumns().setAll(
                col("Name", QueueInfo::name),
                col("Vhost", QueueInfo::vhost),
                numCol("Ready", QueueInfo::messagesReady),
                numCol("Unacked", QueueInfo::messagesUnacknowledged),
                numCol("Total", QueueInfo::messages),
                numCol("Consumers", QueueInfo::consumers),
                col("State", QueueInfo::state));
        queuesTable.setPlaceholder(new Label("No queues loaded"));
        queuesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    private void buildExchangesColumns() {
        exchangesTable.getColumns().setAll(
                col("Name", ex -> ex.name() == null || ex.name().isEmpty() ? "(default)" : ex.name()),
                col("Vhost", ExchangeInfo::vhost),
                col("Type", ExchangeInfo::type),
                col("Durable", ex -> ex.durable() ? "yes" : "no"));
        exchangesTable.setPlaceholder(new Label("No exchanges loaded"));
        exchangesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    private void buildBindingsColumns() {
        bindingsTable.getColumns().setAll(
                col("Source", b -> b.source() == null || b.source().isEmpty() ? "(default)" : b.source()),
                col("Destination", BindingInfo::destination),
                col("Type", BindingInfo::destinationType),
                col("Routing key", BindingInfo::routingKey));
        bindingsTable.setPlaceholder(new Label("No bindings loaded"));
        bindingsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
    }

    private static <T> TableColumn<T, String> col(String title, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        return c;
    }

    /** A right-aligned numeric column that sorts by value (not lexicographically). */
    private static <T> TableColumn<T, Long> numCol(String title, java.util.function.ToLongFunction<T> value) {
        TableColumn<T, Long> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(value.applyAsLong(cd.getValue())));
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    /** Builds a management client from the dashboard host/port and the shared Auth credentials. */
    private RabbitMqManagementClient newManagementClient() {
        String host = Env.resolve(mgmtHost.getText().trim());
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Enter a management host");
        }
        int port = RabbitMqManagementClient.DEFAULT_PORT;
        String portText = mgmtPort.getText().trim();
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port: " + portText);
            }
        }
        String user = Env.resolve(userField.getText().trim());
        String pass = Env.resolve(passField.getText());
        return new RabbitMqManagementClient(host, port, user, pass);
    }

    private void refreshManagement() {
        final RabbitMqManagementClient client;
        try {
            client = newManagementClient();
        } catch (RuntimeException ex) {
            mgmtStatus.getStyleClass().setAll("meta-label", "status-err");
            mgmtStatus.setText(ex.getMessage());
            return;
        }
        refreshBtn.setDisable(true);
        mgmtStatus.getStyleClass().setAll("meta-label");
        mgmtStatus.setText("Loading…");
        logger.accept("RabbitMQ management refresh → " + Env.resolve(mgmtHost.getText().trim()));

        Task<RabbitMqManagementClient.Dashboard> task = new Task<>() {
            @Override protected RabbitMqManagementClient.Dashboard call() {
                return client.dashboard();
            }
        };
        task.setOnSucceeded(e -> {
            RabbitMqManagementClient.Dashboard snap = task.getValue();
            applyOverview(snap.overview());
            queuesTable.setItems(FXCollections.observableArrayList(snap.queues()));
            exchangesTable.setItems(FXCollections.observableArrayList(snap.exchanges()));
            bindingsTable.setItems(FXCollections.observableArrayList(snap.bindings()));
            mgmtStatus.getStyleClass().setAll("meta-label", "status-2xx");
            mgmtStatus.setText("Loaded — " + snap.queues().size() + " queue(s), "
                    + snap.exchanges().size() + " exchange(s), " + snap.bindings().size() + " binding(s)");
            refreshBtn.setDisable(false);
            logger.accept("RabbitMQ management loaded");
        });
        task.setOnFailed(e -> {
            Throwable err = task.getException();
            mgmtStatus.getStyleClass().setAll("meta-label", "status-err");
            mgmtStatus.setText("Refresh failed: " + err.getMessage());
            logger.accept("RabbitMQ management refresh FAILED: " + err.getMessage());
            append("⚠ management refresh failed: " + err.getMessage());
            refreshBtn.setDisable(false);
        });
        runBg(task, "rabbitmq-mgmt-refresh");
    }

    /** Populates the overview stats strip and the cluster/version sub-label. */
    private void applyOverview(OverviewInfo o) {
        statConnections.setText(o == null ? "–" : String.valueOf(o.connections()));
        statChannels.setText(o == null ? "–" : String.valueOf(o.channels()));
        statQueues.setText(o == null ? "–" : String.valueOf(o.queues()));
        statConsumers.setText(o == null ? "–" : String.valueOf(o.consumers()));
        statMessages.setText(o == null ? "–"
                : o.messages() + " (" + o.messagesReady() + " ready, " + o.messagesUnacknowledged() + " unacked)");
        overviewInfo.setText(o == null ? ""
                : "Cluster " + describe(o.clusterName()) + " · RabbitMQ " + describe(o.rabbitmqVersion())
                        + " · Erlang " + describe(o.erlangVersion()) + " · " + o.exchanges() + " exchange(s)");
    }

    private void purgeSelectedQueue() {
        QueueInfo selected = queuesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            mgmtStatus.getStyleClass().setAll("meta-label", "status-err");
            mgmtStatus.setText("Select a queue to purge");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Purge all messages from queue \"" + selected.name() + "\" (vhost " + selected.vhost() + ")?\n"
                        + "This permanently discards " + selected.messages() + " message(s).",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Purge queue");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        final RabbitMqManagementClient client;
        try {
            client = newManagementClient();
        } catch (RuntimeException ex) {
            mgmtStatus.getStyleClass().setAll("meta-label", "status-err");
            mgmtStatus.setText(ex.getMessage());
            return;
        }
        purgeBtn.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                client.purgeQueue(selected.vhost(), selected.name());
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            append("⊖ purged queue " + selected.name());
            logger.accept("RabbitMQ purged queue " + selected.name());
            purgeBtn.setDisable(false);
            refreshManagement();
        });
        task.setOnFailed(e -> {
            Throwable err = task.getException();
            mgmtStatus.getStyleClass().setAll("meta-label", "status-err");
            mgmtStatus.setText("Purge failed: " + err.getMessage());
            append("⚠ purge failed: " + err.getMessage());
            purgeBtn.setDisable(false);
        });
        runBg(task, "rabbitmq-mgmt-purge");
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void toggleConnect() {
        if (service.isConnected()) {
            consumerTag = null;
            consumeBtn.setText("Consume");
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
        logger.accept("RabbitMQ connect → " + broker);

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                service.connect(broker, Env.resolve(userField.getText().trim()), Env.resolve(passField.getText()));
                service.setListener(new RabbitMqService.MessageListener() {
                    @Override public void onMessage(RabbitMqService.Incoming m) {
                        Platform.runLater(() -> append("◀ " + describe(m.exchange()) + " / "
                                + describe(m.routingKey()) + "  " + m.body()));
                    }
                    @Override public void onCancelled(String tag) {
                        Platform.runLater(() -> {
                            consumerTag = null;
                            consumeBtn.setText("Consume");
                            append("⚠ consumer cancelled by broker");
                        });
                    }
                });
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + broker);
            logger.accept("RabbitMQ connected — " + broker);
            connectBtn.setText("Disconnect");
            connectBtn.setDisable(false);
            append("⇆ connected to " + broker);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("RabbitMQ connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task, "rabbitmq-connect");
    }

    private void declareExchange() {
        String name = Env.resolve(exchangeName.getText().trim());   // resolve ${VAR} in the exchange name
        if (name.isEmpty() || !service.isConnected()) return;
        String type = exchangeType.getValue();
        boolean dur = durable.isSelected();
        runAction(() -> service.declareExchange(name, type, dur),
                () -> append("⊕ exchange " + name + " (" + type + (dur ? ", durable" : "") + ")"),
                err -> append("⚠ declare exchange failed: " + err.getMessage()));
    }

    private void declareQueue() {
        String name = Env.resolve(queueName.getText().trim());   // resolve ${VAR} in the queue name
        if (name.isEmpty() || !service.isConnected()) return;
        boolean dur = durable.isSelected();
        runAction(() -> service.declareQueue(name, dur),
                () -> append("⊕ queue " + name + (dur ? " (durable)" : "")),
                err -> append("⚠ declare queue failed: " + err.getMessage()));
    }

    private void bind() {
        String queue = Env.resolve(queueName.getText().trim());
        String exchange = Env.resolve(exchangeName.getText().trim());
        String key = Env.resolve(bindRoutingKey.getText().trim());   // resolve ${VAR} in the routing key
        if (queue.isEmpty() || exchange.isEmpty() || !service.isConnected()) {
            append("⚠ bind needs a connected broker plus a queue and exchange name");
            return;
        }
        runAction(() -> service.bind(queue, exchange, key),
                () -> append("⊕ bound " + queue + " → " + exchange + " (key " + describe(key) + ")"),
                err -> append("⚠ bind failed: " + err.getMessage()));
    }

    private void publish() {
        if (!service.isConnected()) { pubStatus.setText("Not connected"); return; }
        String exchange = Env.resolve(pubExchange.getText().trim());   // resolve ${VAR} in exchange/key/payload
        String key = Env.resolve(pubRoutingKey.getText().trim());
        if (exchange.isEmpty() && key.isEmpty()) { pubStatus.setText("Enter an exchange or routing key"); return; }
        String payload = Env.resolve(pubPayload.getText());
        pubStatus.getStyleClass().setAll("meta-label");
        pubStatus.setText("Publishing…");
        runAction(() -> service.publish(exchange, key, payload),
                () -> {
                    pubStatus.getStyleClass().setAll("status-2xx");
                    pubStatus.setText("✓ sent");
                    append("▶ " + describe(exchange) + " / " + describe(key) + "  " + payload);
                    logger.accept("RabbitMQ published → " + describe(exchange) + "/" + describe(key));
                },
                err -> {
                    pubStatus.getStyleClass().setAll("status-err");
                    pubStatus.setText("✖ " + err.getMessage());
                });
    }

    private void toggleConsume() {
        if (!service.isConnected()) { append("⚠ connect first"); return; }
        if (consumerTag != null) {
            String tag = consumerTag;
            runAction(() -> service.cancel(tag),
                    () -> { consumerTag = null; consumeBtn.setText("Consume"); append("⊖ stopped consuming"); },
                    err -> append("⚠ cancel failed: " + err.getMessage()));
            return;
        }
        String queue = Env.resolve(consumeQueue.getText().trim());   // resolve ${VAR} in the queue name
        if (queue.isEmpty()) { append("⚠ enter a queue to consume"); return; }
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return service.consume(queue, false); }
        };
        task.setOnSucceeded(e -> {
            consumerTag = task.getValue();
            consumeBtn.setText("Stop");
            append("⊕ consuming " + queue);
            logger.accept("RabbitMQ consuming ← " + queue);
        });
        task.setOnFailed(e -> append("⚠ consume failed: " + task.getException().getMessage()));
        runBg(task, "rabbitmq-consume");
    }

    private String describe(String value) {
        return value == null || value.isEmpty() ? "(default)" : value;
    }

    /** Runs a throwing broker action on a background thread, applying results on the FX thread. */
    private void runAction(ThrowingRunnable action, Runnable onOk, Consumer<Throwable> onErr) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { action.run(); return null; }
        };
        task.setOnSucceeded(e -> onOk.run());
        task.setOnFailed(e -> onErr.accept(task.getException()));
        runBg(task, "rabbitmq-action");
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
