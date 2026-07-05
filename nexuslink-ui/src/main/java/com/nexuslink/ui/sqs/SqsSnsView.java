package com.nexuslink.ui.sqs;

import com.nexuslink.protocol.sqs.SnsService;
import com.nexuslink.protocol.sqs.SqsService;
import com.nexuslink.ui.env.Env;
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
 * AWS SQS + SNS client tab — connect to any SQS/SNS-compatible endpoint (real AWS or LocalStack) and
 * manage queues (list/create/delete, send, long-poll receive, delete, purge, DLQ redrive, FIFO) and
 * topics (list/create/delete, publish, subscriptions). Blocking SDK calls run off the FX thread.
 */
public final class SqsSnsView extends BorderPane {

    private final SqsService sqs = new SqsService();
    private final SnsService sns = new SnsService();

    private final TextField endpointField = new TextField("http://localhost:4566");
    private final TextField regionField = new TextField("us-east-1");
    private final TextField accessKeyField = new TextField("test");
    private final PasswordField secretKeyField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // SQS
    private final ListView<String> queueList = new ListView<>();
    private final ObservableList<SqsService.Message> received = FXCollections.observableArrayList();
    private final TableView<SqsService.Message> messages = new TableView<>(received);
    private final TextArea sendBody = new TextArea();
    private final TextField fifoGroup = new TextField();

    // SNS
    private final ListView<String> topicList = new ListView<>();
    private final ObservableList<SnsService.Subscription> subs = FXCollections.observableArrayList();
    private final TableView<SnsService.Subscription> subsTable = new TableView<>(subs);
    private final TextField snsSubject = new TextField();
    private final TextArea snsMessage = new TextArea();

    private Consumer<String> logger = s -> {};

    public SqsSnsView() {
        getStyleClass().add("sqs-view");
        secretKeyField.setText("test");
        setTop(buildBar());
        TabPane tabs = new TabPane(
                new Tab("Queues (SQS)", buildSqsPane()),
                new Tab("Topics (SNS)", buildSnsPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);
        setDisabledConnectedControls(true);
    }

    public void setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> {} : logger; }

    /** Pre-fills endpoint + credentials (used when opening a saved/sample connection). */
    public void prefill(String endpoint, String accessKey, String secretKey) {
        if (endpoint != null && !endpoint.isBlank()) endpointField.setText(endpoint);
        if (accessKey != null && !accessKey.isBlank()) accessKeyField.setText(accessKey);
        if (secretKey != null && !secretKey.isBlank()) secretKeyField.setText(secretKey);
    }

    // ---- connect bar ----

    private VBox buildBar() {
        endpointField.getStyleClass().add("nl-field");
        endpointField.setPromptText("http://localhost:4566  or  https://sqs.us-east-1.amazonaws.com");
        HBox.setHgrow(endpointField, Priority.ALWAYS);
        regionField.getStyleClass().add("nl-field"); regionField.setPrefWidth(110);
        accessKeyField.getStyleClass().add("nl-field"); accessKeyField.setPrefWidth(140);
        secretKeyField.getStyleClass().add("nl-field"); secretKeyField.setPrefWidth(140);
        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());
        statusLabel.getStyleClass().add("meta-label");

        HBox row1 = new HBox(8, label("Endpoint:"), endpointField, connectBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Region:"), regionField, label("Access:"), accessKeyField,
                label("Secret:"), secretKeyField, statusLabel);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2);
    }

    // ---- SQS pane ----

    private Region buildSqsPane() {
        queueList.setPlaceholder(new Label("Connect, then Refresh to list queues."));
        queueList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) refreshCount(nv);
        });

        Button refresh = btn("Refresh", () -> refreshQueues());
        Button create = btn("Create…", this::createQueue);
        Button delete = btn("Delete", this::deleteQueue);
        Button purge = btn("Purge", this::purgeQueue);
        Button redrive = btn("Redrive to…", this::redrive);
        VBox left = new VBox(6, new HBox(6, refresh, create), queueList, new HBox(6, delete, purge, redrive));
        left.setPadding(new Insets(8));
        VBox.setVgrow(queueList, Priority.ALWAYS);
        left.setPrefWidth(320);

        TableColumn<SqsService.Message, String> idCol = new TableColumn<>("Message ID");
        idCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().messageId()));
        idCol.setPrefWidth(240);
        TableColumn<SqsService.Message, String> bodyCol = new TableColumn<>("Body");
        bodyCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().body()));
        messages.getColumns().setAll(List.of(idCol, bodyCol));
        messages.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        messages.setPlaceholder(new Label("Receive to fetch messages (long-poll)."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(messages);

        sendBody.setPromptText("message body");
        sendBody.setPrefRowCount(3);
        fifoGroup.setPromptText("FIFO group id (only for .fifo queues)");
        Button send = btn("Send", this::sendMessage);
        Button receive = btn("Receive 10", () -> receiveMessages(10));
        Button deleteMsg = btn("Delete selected", this::deleteSelectedMessage);
        VBox right = new VBox(6,
                new HBox(6, send, receive, deleteMsg),
                sendBody, fifoGroup, messages);
        right.setPadding(new Insets(8));
        VBox.setVgrow(messages, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.32);
        return sp;
    }

    // ---- SNS pane ----

    private Region buildSnsPane() {
        topicList.setPlaceholder(new Label("Connect, then Refresh to list topics."));
        topicList.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) refreshSubs(nv);
        });
        Button refresh = btn("Refresh", this::refreshTopics);
        Button create = btn("Create…", this::createTopic);
        Button delete = btn("Delete", this::deleteTopic);
        VBox left = new VBox(6, new HBox(6, refresh, create), topicList, delete);
        left.setPadding(new Insets(8));
        VBox.setVgrow(topicList, Priority.ALWAYS);
        left.setPrefWidth(320);

        TableColumn<SnsService.Subscription, String> pCol = new TableColumn<>("Protocol");
        pCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().protocol()));
        TableColumn<SnsService.Subscription, String> eCol = new TableColumn<>("Endpoint");
        eCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().endpoint()));
        subsTable.getColumns().setAll(List.of(pCol, eCol));
        subsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        subsTable.setPlaceholder(new Label("Select a topic to list its subscriptions."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(subsTable);

        snsSubject.setPromptText("subject (optional)");
        snsMessage.setPromptText("message");
        snsMessage.setPrefRowCount(3);
        Button publish = btn("Publish", this::publish);
        VBox right = new VBox(6, publish, snsSubject, snsMessage, new Label("Subscriptions"), subsTable);
        right.setPadding(new Insets(8));
        VBox.setVgrow(subsTable, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.32);
        return sp;
    }

    // ---- connect ----

    private void connect() {
        String endpoint = Env.resolve(endpointField.getText().trim());
        String region = Env.resolve(regionField.getText().trim());
        String access = Env.resolve(accessKeyField.getText().trim());
        String secret = Env.resolve(secretKeyField.getText());
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        run("connect", () -> {
            sqs.connect(endpoint, region, access, secret);
            sns.connect(endpoint, region, access, secret);
            return null;
        }, v -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + endpoint);
            setDisabledConnectedControls(false);
            connectBtn.setDisable(false);
            refreshQueues();
            refreshTopics();
        }, ex -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + ex.getMessage());
            connectBtn.setDisable(false);
        });
    }

    // ---- SQS actions ----

    private void refreshQueues() {
        run("listQueues", sqs::listQueues, urls ->
                queueList.getItems().setAll(urls.stream().map(SqsSnsView::shortName).toList()), this::fail);
    }

    private void refreshCount(String shortName) {
        run("count", () -> sqs.approximateCount(sqs.queueUrl(shortName)),
                n -> statusLabel.setText(shortName + " — ~" + n + " message(s)"), this::fail);
    }

    private void createQueue() {
        prompt("Create queue", "Queue name (append .fifo for a FIFO queue):", "").ifPresent(name -> {
            if (name.isBlank()) return;
            run("createQueue", () -> sqs.createQueue(name.trim()), u -> refreshQueues(), this::fail);
        });
    }

    private void deleteQueue() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("deleteQueue", () -> { sqs.deleteQueue(sqs.queueUrl(sel)); return null; }, v -> refreshQueues(), this::fail);
    }

    private void purgeQueue() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("purge", () -> { sqs.purge(sqs.queueUrl(sel)); return null; },
                v -> { received.clear(); refreshCount(sel); }, this::fail);
    }

    private void redrive() {
        String dlq = queueList.getSelectionModel().getSelectedItem();
        if (dlq == null) return;
        prompt("Redrive", "Move messages from '" + dlq + "' to which queue?", "").ifPresent(target -> {
            if (target.isBlank()) return;
            run("redrive", () -> sqs.redrive(sqs.queueUrl(dlq), sqs.queueUrl(target.trim()), 100),
                    n -> { logger.accept("Redrove " + n + " message(s) " + dlq + " → " + target);
                           statusLabel.setText("Redrove " + n + " message(s) to " + target); }, this::fail);
        });
    }

    private void sendMessage() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        if (sel == null || sendBody.getText().isEmpty()) return;
        String body = sendBody.getText();
        String group = fifoGroup.getText().trim();
        run("send", () -> {
            String url = sqs.queueUrl(sel);
            return sel.endsWith(".fifo") || !group.isEmpty()
                    ? sqs.sendFifo(url, body, group.isEmpty() ? "default" : group)
                    : sqs.send(url, body);
        }, id -> { logger.accept("Sent " + id + " → " + sel); sendBody.clear(); refreshCount(sel); }, this::fail);
    }

    private void receiveMessages(int max) {
        String sel = queueList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("receive", () -> sqs.receive(sqs.queueUrl(sel), max, 2),
                msgs -> received.setAll(msgs), this::fail);
    }

    private void deleteSelectedMessage() {
        String sel = queueList.getSelectionModel().getSelectedItem();
        SqsService.Message m = messages.getSelectionModel().getSelectedItem();
        if (sel == null || m == null) return;
        run("deleteMsg", () -> { sqs.delete(sqs.queueUrl(sel), m.receiptHandle()); return null; },
                v -> { received.remove(m); refreshCount(sel); }, this::fail);
    }

    // ---- SNS actions ----

    private void refreshTopics() {
        run("listTopics", sns::listTopics, arns ->
                topicList.getItems().setAll(arns.stream().map(SqsSnsView::shortName).toList()), this::fail);
    }

    private void createTopic() {
        prompt("Create topic", "Topic name:", "").ifPresent(name -> {
            if (name.isBlank()) return;
            run("createTopic", () -> sns.createTopic(name.trim()), a -> refreshTopics(), this::fail);
        });
    }

    private void deleteTopic() {
        String sel = topicList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("deleteTopic", () -> { sns.deleteTopic(resolveTopicArn(sel)); return null; }, v -> refreshTopics(), this::fail);
    }

    private void refreshSubs(String shortName) {
        run("listSubs", () -> sns.listSubscriptions(resolveTopicArn(shortName)),
                list -> subs.setAll(list), this::fail);
    }

    private void publish() {
        String sel = topicList.getSelectionModel().getSelectedItem();
        if (sel == null || snsMessage.getText().isEmpty()) return;
        String subject = snsSubject.getText().trim();
        String msg = snsMessage.getText();
        run("publish", () -> sns.publish(resolveTopicArn(sel), subject.isEmpty() ? null : subject, msg),
                id -> { logger.accept("Published " + id + " → " + sel); snsMessage.clear(); }, this::fail);
    }

    /** SNS needs the full ARN; find the listed ARN whose last segment matches the short name. */
    private String resolveTopicArn(String shortName) {
        return sns.listTopics().stream().filter(a -> shortName(a).equals(shortName)).findFirst().orElse(shortName);
    }

    // ---- helpers ----

    private void setDisabledConnectedControls(boolean disabled) {
        getCenter().setDisable(disabled);
    }

    private static String shortName(String urlOrArn) {
        if (urlOrArn == null) return "";
        int slash = urlOrArn.lastIndexOf('/');
        int colon = urlOrArn.lastIndexOf(':');
        int cut = Math.max(slash, colon);
        return cut >= 0 && cut < urlOrArn.length() - 1 ? urlOrArn.substring(cut + 1) : urlOrArn;
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
        logger.accept("SQS/SNS error: " + ex.getMessage());
    }

    /** Runs {@code work} off the FX thread; delivers success/failure back on the FX thread. */
    private <T> void run(String name, java.util.concurrent.Callable<T> work,
                         Consumer<T> onOk, Consumer<Throwable> onErr) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.call(); }
        };
        task.setOnSucceeded(e -> onOk.accept(task.getValue()));
        task.setOnFailed(e -> onErr.accept(task.getException()));
        Thread t = new Thread(task, "sqs-" + name);
        t.setDaemon(true);
        t.start();
    }
}
