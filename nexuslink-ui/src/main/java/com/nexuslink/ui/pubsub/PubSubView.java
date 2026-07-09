package com.nexuslink.ui.pubsub;

import com.nexuslink.protocol.pubsub.PubSubService;
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
 * Google Cloud Pub/Sub client tab — connect to a project (real GCP via ADC, or the local emulator when
 * {@code PUBSUB_EMULATOR_HOST} is set, exactly like the GCS tab) and manage topics (list/create/delete,
 * publish) and subscriptions (list/create/delete, pull-with-ack). Blocking gRPC calls run off the FX thread.
 */
public final class PubSubView extends BorderPane {

    private final PubSubService service = new PubSubService();

    private final TextField projectField = new TextField("nexus-it");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    // Topics
    private final ListView<String> topicList = new ListView<>();
    private final TextArea publishBody = new TextArea();

    // Subscriptions
    private final ListView<String> subList = new ListView<>();
    private final ObservableList<PubSubService.PulledMessage> pulled = FXCollections.observableArrayList();
    private final TableView<PubSubService.PulledMessage> messages = new TableView<>(pulled);
    private final Spinner<Integer> maxPull = new Spinner<>(1, 100, 10);

    private Consumer<String> logger = s -> {};

    public PubSubView() {
        getStyleClass().add("pubsub-view");
        setTop(buildBar());
        TabPane tabs = new TabPane(
                new Tab("Topics", buildTopicsPane()),
                new Tab("Subscriptions", buildSubsPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);
        setDisabledConnectedControls(true);
    }

    public void setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> {} : logger; }

    /** Pre-fills the project id (used when opening a saved/sample connection). */
    public void prefill(String project) {
        if (project != null && !project.isBlank()) projectField.setText(project);
    }

    // ---- connect bar ----

    private VBox buildBar() {
        projectField.getStyleClass().add("nl-field");
        projectField.setPromptText("GCP project id");
        projectField.setPrefWidth(220);
        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());
        statusLabel.getStyleClass().add("meta-label");

        Label hint = new Label("Emulator: set PUBSUB_EMULATOR_HOST (e.g. localhost:8085) before launch; else uses ADC.");
        hint.getStyleClass().add("meta-label");

        HBox row1 = new HBox(8, label("Project:"), projectField, connectBtn, statusLabel);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, hint);
        row2.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2);
    }

    // ---- Topics pane ----

    private Region buildTopicsPane() {
        topicList.setPlaceholder(new Label("Connect, then Refresh to list topics."));
        Button refresh = btn("Refresh", this::refreshTopics);
        Button create = btn("Create…", this::createTopic);
        Button delete = btn("Delete", this::deleteTopic);
        VBox left = new VBox(6, new HBox(6, refresh, create), topicList, delete);
        left.setPadding(new Insets(8));
        VBox.setVgrow(topicList, Priority.ALWAYS);
        left.setPrefWidth(320);

        publishBody.setPromptText("message body");
        publishBody.setPrefRowCount(4);
        Button publish = btn("Publish", this::publish);
        VBox right = new VBox(6, new Label("Publish to selected topic"), publishBody, publish);
        right.setPadding(new Insets(8));
        VBox.setVgrow(publishBody, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.34);
        return sp;
    }

    // ---- Subscriptions pane ----

    private Region buildSubsPane() {
        subList.setPlaceholder(new Label("Connect, then Refresh to list subscriptions."));
        Button refresh = btn("Refresh", this::refreshSubs);
        Button create = btn("Create…", this::createSubscription);
        Button delete = btn("Delete", this::deleteSubscription);
        VBox left = new VBox(6, new HBox(6, refresh, create), subList, delete);
        left.setPadding(new Insets(8));
        VBox.setVgrow(subList, Priority.ALWAYS);
        left.setPrefWidth(320);

        TableColumn<PubSubService.PulledMessage, String> idCol = new TableColumn<>("Message ID");
        idCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().messageId()));
        idCol.setPrefWidth(160);
        TableColumn<PubSubService.PulledMessage, String> dataCol = new TableColumn<>("Data");
        dataCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().data()));
        messages.getColumns().setAll(List.of(idCol, dataCol));
        messages.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        messages.setPlaceholder(new Label("Pull to fetch messages (each pulled message is acknowledged)."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(messages);

        maxPull.setPrefWidth(80);
        Button pull = btn("Pull", this::pull);
        HBox pullBar = new HBox(6, new Label("Max:"), maxPull, pull);
        pullBar.setAlignment(Pos.CENTER_LEFT);
        VBox right = new VBox(6, pullBar, messages);
        right.setPadding(new Insets(8));
        VBox.setVgrow(messages, Priority.ALWAYS);

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.34);
        return sp;
    }

    // ---- connect ----

    private void connect() {
        String project = Env.resolve(projectField.getText().trim());
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        run("connect", () -> { service.connect(project); return null; }, v -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + project);
            setDisabledConnectedControls(false);
            connectBtn.setDisable(false);
            refreshTopics();
            refreshSubs();
        }, ex -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + ex.getMessage());
            connectBtn.setDisable(false);
        });
    }

    // ---- topic actions ----

    private void refreshTopics() {
        run("listTopics", service::listTopics, list -> topicList.getItems().setAll(list), this::fail);
    }

    private void createTopic() {
        prompt("Create topic", "Topic id:", "").ifPresent(name -> {
            if (name.isBlank()) return;
            run("createTopic", () -> service.createTopic(name.trim()), a -> refreshTopics(), this::fail);
        });
    }

    private void deleteTopic() {
        String sel = topicList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("deleteTopic", () -> { service.deleteTopic(sel); return null; }, v -> refreshTopics(), this::fail);
    }

    private void publish() {
        String sel = topicList.getSelectionModel().getSelectedItem();
        if (sel == null || publishBody.getText().isEmpty()) return;
        String body = publishBody.getText();
        run("publish", () -> service.publish(sel, body),
                id -> { logger.accept("Published " + id + " → " + sel); publishBody.clear();
                        statusLabel.setText("Published " + id); }, this::fail);
    }

    // ---- subscription actions ----

    private void refreshSubs() {
        run("listSubs", service::listSubscriptions, list -> subList.getItems().setAll(list), this::fail);
    }

    private void createSubscription() {
        prompt("Create subscription", "Subscription id:", "").ifPresent(sub -> {
            if (sub.isBlank()) return;
            String defaultTopic = topicList.getSelectionModel().getSelectedItem();
            prompt("Create subscription", "Topic to subscribe to:", defaultTopic == null ? "" : defaultTopic)
                    .ifPresent(topic -> {
                        if (topic.isBlank()) return;
                        run("createSub", () -> service.createSubscription(sub.trim(), topic.trim(), 10),
                                a -> refreshSubs(), this::fail);
                    });
        });
    }

    private void deleteSubscription() {
        String sel = subList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        run("deleteSub", () -> { service.deleteSubscription(sel); return null; }, v -> refreshSubs(), this::fail);
    }

    private void pull() {
        String sel = subList.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        int max = maxPull.getValue();
        run("pull", () -> service.pull(sel, max), msgs -> {
            pulled.setAll(msgs);
            statusLabel.setText("Pulled " + msgs.size() + " message(s) from " + sel);
        }, this::fail);
    }

    // ---- helpers ----

    private void setDisabledConnectedControls(boolean disabled) {
        getCenter().setDisable(disabled);
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
        logger.accept("Pub/Sub error: " + ex.getMessage());
    }

    /** Runs {@code work} off the FX thread; delivers success/failure back on the FX thread. */
    private <T> void run(String name, java.util.concurrent.Callable<T> work,
                         Consumer<T> onOk, Consumer<Throwable> onErr) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.call(); }
        };
        task.setOnSucceeded(e -> onOk.accept(task.getValue()));
        task.setOnFailed(e -> onErr.accept(task.getException()));
        Thread t = new Thread(task, "pubsub-" + name);
        t.setDaemon(true);
        t.start();
    }
}
