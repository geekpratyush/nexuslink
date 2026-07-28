package com.nexuslink.ui.redis;

import com.nexuslink.protocol.redis.RedisMessageLog;
import com.nexuslink.protocol.redis.RedisService;
import com.nexuslink.protocol.redis.RedisSubscriber;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Redis Pub/Sub panel: subscribe to channels or glob patterns, watch messages stream in, and publish
 * to a channel.
 *
 * <p>Subscribing needs a connection that does nothing else — once a Redis connection enters
 * subscribe mode it accepts only (un)subscribe commands — so this panel opens its own
 * {@link RedisSubscriber} over a dedicated socket rather than borrowing the key browser's
 * {@link RedisService}. Publishing goes through the shared service, which stays usable throughout.
 *
 * <p>{@link RedisSubscriber} invokes its callbacks on its reader thread, so every one of them hops
 * to the FX thread before touching a control.
 */
public final class RedisPubSubPanel extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Supplier<String> uriSupplier;
    private final RedisService service;

    private final RedisMessageLog log = new RedisMessageLog();
    private final ObservableList<RedisMessageLog.Entry> rows = FXCollections.observableArrayList();
    private final TableView<RedisMessageLog.Entry> table = new TableView<>(rows);
    private final ObservableList<String> subscriptions = FXCollections.observableArrayList();
    private final ListView<String> subscriptionList = new ListView<>(subscriptions);

    private final TextField targetField = new TextField();
    private final TextField filterField = new TextField();
    private final TextField publishChannel = new TextField();
    private final TextField publishPayload = new TextField();
    private final Label statusLabel = new Label("Not subscribed");

    private volatile RedisSubscriber subscriber;
    private Consumer<String> logger = s -> {};

    /**
     * @param uriSupplier the connection URI to open the subscriber socket with (the view's URI field)
     * @param service     the shared service, used for publishing
     */
    public RedisPubSubPanel(Supplier<String> uriSupplier, RedisService service) {
        this.uriSupplier = uriSupplier;
        this.service = service;
        setSpacing(6);
        setPadding(new Insets(8));
        getStyleClass().add("redis-pubsub");
        build();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    private void build() {
        targetField.setId("pubsub-target");
        targetField.getStyleClass().add("nl-field");
        targetField.setPromptText("channel, or a pattern like news.*");
        HBox.setHgrow(targetField, Priority.ALWAYS);
        targetField.setOnAction(e -> subscribe(false));

        Button subBtn = new Button("Subscribe");
        subBtn.getStyleClass().add("btn-primary");
        subBtn.setOnAction(e -> subscribe(false));

        Button psubBtn = new Button("Pattern");
        psubBtn.getStyleClass().add("btn-secondary");
        psubBtn.setTooltip(new Tooltip("PSUBSCRIBE — treat the text as a glob pattern"));
        psubBtn.setOnAction(e -> subscribe(true));

        Button unsubBtn = new Button("Unsubscribe all");
        unsubBtn.getStyleClass().add("btn-secondary");
        unsubBtn.setOnAction(e -> unsubscribeAll());

        statusLabel.getStyleClass().add("meta-label");
        HBox subRow = new HBox(8, label("Subscribe:"), targetField, subBtn, psubBtn, unsubBtn);
        subRow.setAlignment(Pos.CENTER_LEFT);

        // --- messages -------------------------------------------------------------------------
        table.setId("pubsub-messages");
        table.getColumns().setAll(
                column("Time", 90, e -> TIME.format(e.timestamp().atZone(ZoneId.systemDefault()))),
                column("Channel", 200, RedisMessageLog.Entry::channel),
                column("Pattern", 130, e -> e.pattern() == null ? "" : e.pattern()),
                column("Payload", 420, RedisMessageLog.Entry::payload));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No messages yet — subscribe to a channel to start listening."));

        filterField.setId("pubsub-filter");
        filterField.getStyleClass().add("nl-field");
        filterField.setPromptText("filter by channel glob, e.g. news.*");
        filterField.textProperty().addListener((o, a, b) -> refreshRows());
        HBox.setHgrow(filterField, Priority.ALWAYS);

        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> { log.clear(); refreshRows(); });

        HBox filterRow = new HBox(8, label("Filter:"), filterField, clearBtn, statusLabel);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        // --- subscriptions --------------------------------------------------------------------
        subscriptionList.setPrefWidth(200);
        subscriptionList.setPlaceholder(new Label("none"));
        VBox subsBox = new VBox(4, label("Subscriptions:"), subscriptionList);
        VBox.setVgrow(subscriptionList, Priority.ALWAYS);

        SplitPane centre = new SplitPane(table, subsBox);
        centre.setDividerPositions(0.76);
        VBox.setVgrow(centre, Priority.ALWAYS);

        // --- publish --------------------------------------------------------------------------
        publishChannel.getStyleClass().add("nl-field");
        publishChannel.setPromptText("channel");
        publishChannel.setPrefWidth(180);
        publishPayload.getStyleClass().add("nl-field");
        publishPayload.setPromptText("message");
        publishPayload.setOnAction(e -> publish());
        HBox.setHgrow(publishPayload, Priority.ALWAYS);
        Button pubBtn = new Button("Publish");
        pubBtn.getStyleClass().add("btn-primary");
        pubBtn.setOnAction(e -> publish());
        HBox pubRow = new HBox(8, label("Publish:"), publishChannel, publishPayload, pubBtn);
        pubRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(subRow, filterRow, centre, new Separator(), pubRow);
    }

    // ------------------------------------------------------------------ subscription lifecycle

    private void subscribe(boolean pattern) {
        String target = Env.resolve(targetField.getText().trim());
        if (target.isEmpty()) {
            status("Enter a channel or pattern", true);
            return;
        }
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                RedisSubscriber active = ensureSubscriber();
                if (pattern) {
                    active.psubscribe(target);
                } else {
                    active.subscribe(target);
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            String entry = (pattern ? "pattern  " : "channel  ") + target;
            if (!subscriptions.contains(entry)) subscriptions.add(entry);
            status(subscriptions.size() + " subscription(s)", false);
            logger.accept("Redis " + (pattern ? "PSUBSCRIBE " : "SUBSCRIBE ") + target);
            targetField.clear();
        });
        task.setOnFailed(e -> {
            status("Subscribe failed: " + task.getException().getMessage(), true);
            logger.accept("Redis subscribe FAILED: " + task.getException().getMessage());
        });
        runBg(task, "redis-subscribe");
    }

    /** Opens the dedicated subscriber socket on first use. Called off the FX thread. */
    private RedisSubscriber ensureSubscriber() {
        RedisSubscriber active = subscriber;
        if (active != null && active.isRunning()) return active;
        String uri = Env.resolve(uriSupplier.get().trim());
        active = RedisSubscriber.connect(uri,
                message -> Platform.runLater(() -> {
                    log.add(message);
                    refreshRows();
                }),
                null,
                error -> Platform.runLater(() -> {
                    status("Subscription lost: " + error.getMessage(), true);
                    subscriptions.clear();
                }));
        subscriber = active;
        return active;
    }

    private void unsubscribeAll() {
        RedisSubscriber active = subscriber;
        if (active == null) {
            status("Not subscribed", false);
            return;
        }
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                active.close();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            subscriber = null;
            subscriptions.clear();
            status("Not subscribed", false);
            logger.accept("Redis unsubscribed from all channels");
        });
        task.setOnFailed(e -> status("Unsubscribe failed: " + task.getException().getMessage(), true));
        runBg(task, "redis-unsubscribe");
    }

    private void publish() {
        String channel = Env.resolve(publishChannel.getText().trim());
        String payload = Env.resolve(publishPayload.getText());
        if (channel.isEmpty()) {
            status("Enter a channel to publish to", true);
            return;
        }
        if (!service.isConnected()) {
            status("Connect first", true);
            return;
        }
        Task<Long> task = new Task<>() {
            @Override protected Long call() { return service.publish(channel, payload); }
        };
        task.setOnSucceeded(e -> {
            status("Published to " + task.getValue() + " subscriber(s)", false);
            logger.accept("Redis PUBLISH " + channel + " → " + task.getValue() + " subscriber(s)");
        });
        task.setOnFailed(e -> status("Publish failed: " + task.getException().getMessage(), true));
        runBg(task, "redis-publish");
    }

    /** Closes the subscriber socket — call when the tab or the connection goes away. */
    public void dispose() {
        RedisSubscriber active = subscriber;
        subscriber = null;
        if (active != null) active.close();
        subscriptions.clear();
    }

    // ------------------------------------------------------------------ helpers

    private void refreshRows() {
        List<RedisMessageLog.Entry> shown = log.matching(filterField.getText());
        rows.setAll(shown);
        if (!rows.isEmpty()) table.scrollTo(rows.size() - 1);
    }

    private void status(String text, boolean error) {
        statusLabel.getStyleClass().setAll(error ? "status-err" : "meta-label");
        statusLabel.setText(text);
    }

    private TableColumn<RedisMessageLog.Entry, String> column(
            String title, double width, Function<RedisMessageLog.Entry, String> value) {
        TableColumn<RedisMessageLog.Entry, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
