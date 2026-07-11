package com.nexuslink.ui.servicebus;

import com.nexuslink.protocol.servicebus.ServiceBusService;
import com.nexuslink.ui.env.Env;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * Azure Service Bus client tab — connect with a connection string (a real {@code *.servicebus.windows.net}
 * namespace or the local emulator via {@code UseDevelopmentEmulator=true}) and work with queues
 * (list/create/delete, send, peek-lock receive incl. the dead-letter sub-queue) and topics/subscriptions
 * (list/create/delete, send to topic, receive from a subscription incl. its dead-letter sub-queue).
 *
 * <p>The emulator has no management (HTTP) API — its entities are pre-provisioned from the emulator
 * config — so the list/create/delete buttons apply only to a real namespace; send/receive work against
 * both. All blocking SDK calls run off the FX thread.
 */
public final class ServiceBusView extends BorderPane {

    private final ServiceBusService service = new ServiceBusService();

    private final TextField connField = new TextField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // Queues
    private final ListView<String> queueList = new ListView<>();
    private final TextArea queueBody = new TextArea();
    private final CheckBox queueDlq = new CheckBox("Dead-letter sub-queue");
    private final Spinner<Integer> queueMax = new Spinner<>(1, 100, 10);
    private final ObservableList<ServiceBusService.ReceivedMessage> queueMsgs = FXCollections.observableArrayList();
    private final TableView<ServiceBusService.ReceivedMessage> queueTable = new TableView<>(queueMsgs);

    // Topics / subscriptions
    private final ListView<String> topicList = new ListView<>();
    private final ListView<String> subList = new ListView<>();
    private final TextArea topicBody = new TextArea();
    private final CheckBox subDlq = new CheckBox("Dead-letter sub-queue");
    private final Spinner<Integer> subMax = new Spinner<>(1, 100, 10);
    private final ObservableList<ServiceBusService.ReceivedMessage> subMsgs = FXCollections.observableArrayList();
    private final TableView<ServiceBusService.ReceivedMessage> subTable = new TableView<>(subMsgs);

    private Consumer<String> logger = s -> {};

    public ServiceBusView() {
        getStyleClass().add("servicebus-view");
        setTop(buildBar());
        TabPane tabs = new TabPane(
                new Tab("Queues", buildQueuesPane()),
                new Tab("Topics", buildTopicsPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);
        setDisabledConnectedControls(true);
    }

    public void setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> {} : logger; }

    /** Pre-fills the connection string (used when opening a saved/sample connection). */
    public void prefill(String connectionString) {
        if (connectionString != null && !connectionString.isBlank()) connField.setText(connectionString);
    }

    // ---- connect bar ----

    private VBox buildBar() {
        connField.getStyleClass().add("nl-field");
        connField.setPromptText("Endpoint=sb://…;SharedAccessKeyName=…;SharedAccessKey=…");
        HBox.setHgrow(connField, Priority.ALWAYS);
        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());
        statusLabel.getStyleClass().add("meta-label");

        Label hint = new Label("Emulator: append UseDevelopmentEmulator=true (queues/topics are pre-provisioned; "
                + "management calls need a real namespace).");
        hint.getStyleClass().add("meta-label");

        HBox row1 = new HBox(8, label("Connection:"), connField, connectBtn, statusLabel);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, hint);
        row2.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2);
    }

    // ---- Queues pane ----

    private Region buildQueuesPane() {
        queueList.setPlaceholder(new Label("Connect to a namespace, then Refresh to list queues."));
        Button refresh = btn("Refresh", this::refreshQueues);
        Button create = btn("Create…", this::createQueue);
        Button delete = btn("Delete", this::deleteQueue);
        VBox left = new VBox(6, new HBox(6, refresh, create), queueList, delete);
        left.setPadding(new Insets(8));
        VBox.setVgrow(queueList, Priority.ALWAYS);
        left.setPrefWidth(300);

        queueBody.setPromptText("message body");
        queueBody.setPrefRowCount(3);
        Button send = btn("Send", this::sendToQueue);
        VBox sendBox = new VBox(6, new Label("Send to selected queue"), queueBody, send);
        VBox.setVgrow(queueBody, Priority.ALWAYS);

        configureMessageTable(queueTable);
        queueMax.setPrefWidth(80);
        Button receive = btn("Receive", this::receiveFromQueue);
        HBox recvBar = new HBox(8, new Label("Max:"), queueMax, queueDlq, receive);
        recvBar.setAlignment(Pos.CENTER_LEFT);
        VBox recvBox = new VBox(6, new Label("Receive (peek-lock, each completed)"), recvBar, queueTable);
        VBox.setVgrow(queueTable, Priority.ALWAYS);

        VBox right = new VBox(10, sendBox, recvBox);
        right.setPadding(new Insets(8));
        VBox.setVgrow(recvBox, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.32);
        return sp;
    }

    // ---- Topics pane ----

    private Region buildTopicsPane() {
        topicList.setPlaceholder(new Label("Connect to a namespace, then Refresh to list topics."));
        topicList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshSubs());
        Button tRefresh = btn("Refresh", this::refreshTopics);
        Button tCreate = btn("Create…", this::createTopic);
        Button tDelete = btn("Delete", this::deleteTopic);
        VBox topicsBox = new VBox(6, new Label("Topics"), new HBox(6, tRefresh, tCreate), topicList, tDelete);
        VBox.setVgrow(topicList, Priority.ALWAYS);

        subList.setPlaceholder(new Label("Select a topic to list its subscriptions."));
        Button sCreate = btn("Create…", this::createSubscription);
        Button sDelete = btn("Delete", this::deleteSubscription);
        VBox subsBox = new VBox(6, new Label("Subscriptions"), new HBox(6, sCreate, sDelete), subList);
        VBox.setVgrow(subList, Priority.ALWAYS);

        VBox left = new VBox(10, topicsBox, subsBox);
        left.setPadding(new Insets(8));
        left.setPrefWidth(320);

        topicBody.setPromptText("message body");
        topicBody.setPrefRowCount(3);
        Button send = btn("Send", this::sendToTopic);
        VBox sendBox = new VBox(6, new Label("Send to selected topic"), topicBody, send);
        VBox.setVgrow(topicBody, Priority.ALWAYS);

        configureMessageTable(subTable);
        subMax.setPrefWidth(80);
        Button receive = btn("Receive", this::receiveFromSubscription);
        HBox recvBar = new HBox(8, new Label("Max:"), subMax, subDlq, receive);
        recvBar.setAlignment(Pos.CENTER_LEFT);
        VBox recvBox = new VBox(6, new Label("Receive from selected subscription (peek-lock, each completed)"),
                recvBar, subTable);
        VBox.setVgrow(subTable, Priority.ALWAYS);

        VBox right = new VBox(10, sendBox, recvBox);
        right.setPadding(new Insets(8));
        VBox.setVgrow(recvBox, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.34);
        return sp;
    }

    private void configureMessageTable(TableView<ServiceBusService.ReceivedMessage> table) {
        TableColumn<ServiceBusService.ReceivedMessage, String> seqCol = new TableColumn<>("Seq");
        seqCol.setCellValueFactory(c -> new SimpleStringProperty(Long.toString(c.getValue().sequenceNumber())));
        seqCol.setPrefWidth(70);
        TableColumn<ServiceBusService.ReceivedMessage, String> dcCol = new TableColumn<>("Deliv.");
        dcCol.setCellValueFactory(c -> new SimpleStringProperty(Long.toString(c.getValue().deliveryCount())));
        dcCol.setPrefWidth(60);
        TableColumn<ServiceBusService.ReceivedMessage, String> idCol = new TableColumn<>("Message ID");
        idCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().messageId()));
        idCol.setPrefWidth(150);
        TableColumn<ServiceBusService.ReceivedMessage, String> bodyCol = new TableColumn<>("Body");
        bodyCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().body()));
        table.getColumns().setAll(List.of(seqCol, dcCol, idCol, bodyCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Receive to fetch messages (each received message is completed)."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(table);
    }

    // ---- connect ----

    private void connect() {
        String conn = Env.resolve(connField.getText().trim());
        if (conn.isBlank()) { fail(new IllegalArgumentException("a connection string is required")); return; }
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        run("connect", () -> { service.connect(conn); return service.namespace(); }, ns -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + ns + (service.isEmulator() ? " (emulator)" : ""));
            setDisabledConnectedControls(false);
            connectBtn.setDisable(false);
            if (!service.isEmulator()) { refreshQueues(); refreshTopics(); }
        }, ex -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + ex.getMessage());
            connectBtn.setDisable(false);
        });
    }

    // ---- queue actions ----

    private void refreshQueues() {
        run("listQueues", service::listQueues, list -> queueList.getItems().setAll(list), this::fail);
    }

    private void createQueue() {
        prompt("Create queue", "Queue name:", "").ifPresent(name -> {
            if (name.isBlank()) return;
            run("createQueue", () -> { service.createQueue(name.trim()); return null; },
                    v -> refreshQueues(), this::fail);
        });
    }

    private void deleteQueue() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("deleteQueue", () -> { service.deleteQueue(sel); return null; }, v -> refreshQueues(), this::fail);
    }

    private void sendToQueue() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        String target = sel != null ? sel : promptTarget("queue");
        if (target == null || target.isBlank() || queueBody.getText().isEmpty()) return;
        String body = queueBody.getText();
        run("sendQueue", () -> service.sendToQueue(target, body), id -> {
            logger.accept("Sent " + id + " → queue " + target);
            queueBody.clear();
            statusLabel.setText("Sent " + id);
        }, this::fail);
    }

    private void receiveFromQueue() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        String target = sel != null ? sel : promptTarget("queue");
        if (target == null || target.isBlank()) return;
        int max = queueMax.getValue();
        boolean dlq = queueDlq.isSelected();
        run("recvQueue", () -> service.receiveFromQueue(target, max, dlq), msgs -> {
            queueMsgs.setAll(msgs);
            statusLabel.setText("Received " + msgs.size() + " from " + target + (dlq ? " (DLQ)" : ""));
        }, this::fail);
    }

    // ---- topic / subscription actions ----

    private void refreshTopics() {
        run("listTopics", service::listTopics, list -> topicList.getItems().setAll(list), this::fail);
    }

    private void createTopic() {
        prompt("Create topic", "Topic name:", "").ifPresent(name -> {
            if (name.isBlank()) return;
            run("createTopic", () -> { service.createTopic(name.trim()); return null; },
                    v -> refreshTopics(), this::fail);
        });
    }

    private void deleteTopic() {
        String sel = topicList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("deleteTopic", () -> { service.deleteTopic(sel); return null; }, v -> {
            refreshTopics();
            subList.getItems().clear();
        }, this::fail);
    }

    private void refreshSubs() {
        String topic = topicList.getSelectionModel().getSelectedItem();
        if (topic == null) { subList.getItems().clear(); return; }
        run("listSubs", () -> service.listSubscriptions(topic),
                list -> subList.getItems().setAll(list), this::fail);
    }

    private void createSubscription() {
        String topic = topicList.getSelectionModel().getSelectedItem();
        if (topic == null) { statusLabel.setText("Select a topic first"); return; }
        prompt("Create subscription", "Subscription name (topic " + topic + "):", "").ifPresent(name -> {
            if (name.isBlank()) return;
            run("createSub", () -> { service.createSubscription(topic, name.trim()); return null; },
                    v -> refreshSubs(), this::fail);
        });
    }

    private void deleteSubscription() {
        String topic = topicList.getSelectionModel().getSelectedItem();
        String sel = subList.getSelectionModel().getSelectedItem();
        if (topic == null || sel == null) return;
        run("deleteSub", () -> { service.deleteSubscription(topic, sel); return null; },
                v -> refreshSubs(), this::fail);
    }

    private void sendToTopic() {
        String sel = topicList.getSelectionModel().getSelectedItem();
        String target = sel != null ? sel : promptTarget("topic");
        if (target == null || target.isBlank() || topicBody.getText().isEmpty()) return;
        String body = topicBody.getText();
        run("sendTopic", () -> service.sendToTopic(target, body), id -> {
            logger.accept("Sent " + id + " → topic " + target);
            topicBody.clear();
            statusLabel.setText("Sent " + id);
        }, this::fail);
    }

    private void receiveFromSubscription() {
        String topic = topicList.getSelectionModel().getSelectedItem();
        String sub = subList.getSelectionModel().getSelectedItem();
        if (topic == null || sub == null) { statusLabel.setText("Select a topic and subscription"); return; }
        int max = subMax.getValue();
        boolean dlq = subDlq.isSelected();
        run("recvSub", () -> service.receiveFromSubscription(topic, sub, max, dlq), msgs -> {
            subMsgs.setAll(msgs);
            statusLabel.setText("Received " + msgs.size() + " from " + topic + "/" + sub + (dlq ? " (DLQ)" : ""));
        }, this::fail);
    }

    // ---- helpers ----

    private void setDisabledConnectedControls(boolean disabled) {
        getCenter().setDisable(disabled);
    }

    /** On the emulator there is no list to pick from, so prompt for the pre-provisioned entity name. */
    private String promptTarget(String kind) {
        return prompt("Send / receive", "Enter " + kind + " name:", "").orElse(null);
    }

    private Button btn(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-secondary");
        b.setOnAction(e -> action.run());
        return b;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private java.util.Optional<String> prompt(String title, String content, String def) {
        TextInputDialog d = new TextInputDialog(def);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(content);
        return d.showAndWait();
    }

    private void fail(Throwable ex) {
        statusLabel.getStyleClass().setAll("status-err");
        statusLabel.setText("Error: " + ex.getMessage());
        logger.accept("Service Bus error: " + ex.getMessage());
    }

    /** Runs {@code work} off the FX thread; delivers success/failure back on the FX thread. */
    private <T> void run(String name, java.util.concurrent.Callable<T> work,
                         Consumer<T> onOk, Consumer<Throwable> onErr) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.call(); }
        };
        task.setOnSucceeded(e -> onOk.accept(task.getValue()));
        task.setOnFailed(e -> onErr.accept(task.getException()));
        Thread t = new Thread(task, "servicebus-" + name);
        t.setDaemon(true);
        t.start();
    }
}
