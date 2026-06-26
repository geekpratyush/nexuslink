package com.nexuslink.ui.rabbitmq;

import com.nexuslink.protocol.rabbitmq.RabbitMqService;
import com.nexuslink.ui.env.Env;
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

    private Consumer<String> logger = s -> {};

    public RabbitMqView() {
        getStyleClass().add("rabbitmq-view");
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
