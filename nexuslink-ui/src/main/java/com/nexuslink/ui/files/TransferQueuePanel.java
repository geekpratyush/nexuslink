package com.nexuslink.ui.files;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A collapsible panel showing the live {@link TransferQueue}: a table with per-row progress bars,
 * an overall progress bar, status counts and a "Clear completed" button. All queue events arrive on
 * a worker thread and are marshalled onto the JavaFX thread here.
 */
public final class TransferQueuePanel extends TitledPane implements TransferQueue.Listener {

    private final TransferQueue queue;
    private final ObservableList<TransferItem> rows = FXCollections.observableArrayList();
    private final TableView<TransferItem> table = new TableView<>(rows);
    private final ProgressBar overall = new ProgressBar(0);
    private final Label counts = new Label();
    private final AtomicBoolean repaintPending = new AtomicBoolean();

    public TransferQueuePanel(TransferQueue queue) {
        this.queue = queue;
        getStyleClass().add("transfer-queue-panel");
        setText("Transfer Queue");
        setCollapsible(true);
        setExpanded(false);
        setContent(buildContent());
        queue.addListener(this);
        refreshNow();
    }

    private VBox buildContent() {
        buildTable();

        overall.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(overall, Priority.ALWAYS);
        counts.getStyleClass().add("meta-label");
        counts.setMinWidth(260);

        Button pauseBtn = new Button("Pause");
        pauseBtn.getStyleClass().add("btn-secondary");
        pauseBtn.setOnAction(e -> {
            if (queue.isPaused()) { queue.resume(); pauseBtn.setText("Pause"); }
            else { queue.pause(); pauseBtn.setText("Resume"); }
        });

        Label limitLbl = new Label("Limit:");
        limitLbl.getStyleClass().add("meta-label");
        ComboBox<String> throttle = new ComboBox<>(FXCollections.observableArrayList(
                "Unlimited", "512 KB/s", "1 MB/s", "5 MB/s", "10 MB/s"));
        throttle.setValue("Unlimited");
        throttle.setOnAction(e -> queue.setMaxBytesPerSecond(bytesForPreset(throttle.getValue())));

        ToggleButton verify = new ToggleButton("Verify");
        verify.getStyleClass().add("btn-secondary");
        verify.setTooltip(new Tooltip("Verify each completed file's size on the destination; a mismatch marks it Failed"));
        verify.setSelected(queue.isVerifyIntegrity());
        verify.setOnAction(e -> queue.setVerifyIntegrity(verify.isSelected()));

        ToggleButton autoRetry = new ToggleButton("Auto-retry");
        autoRetry.getStyleClass().add("btn-secondary");
        autoRetry.setTooltip(new Tooltip("Automatically retry transfers that fail with a transient network error "
                + "(timeout, connection reset), backing off between attempts"));
        autoRetry.setSelected(queue.autoRetryPolicy().enabled());
        autoRetry.setOnAction(e -> queue.setAutoRetry(
                autoRetry.isSelected() ? RetryPolicy.defaultPolicy() : RetryPolicy.none()));

        Label parallelLbl = new Label("Parallel:");
        parallelLbl.getStyleClass().add("meta-label");
        Spinner<Integer> parallel = new Spinner<>(1, 8, queue.concurrency());
        parallel.setPrefWidth(64);
        parallel.setTooltip(new Tooltip("Number of files to transfer at once. Changing it restarts the queue workers "
                + "(in-flight transfers finish first)."));
        parallel.valueProperty().addListener((o, was, now) -> {
            if (now == null || now.equals(queue.concurrency())) return;
            queue.stopWorker();               // in-flight transfers finish; workers wind down
            queue.setConcurrency(now);
            queue.startWorker();              // respawn the pool at the new width
        });

        Button retryFailed = new Button("Retry failed");
        retryFailed.getStyleClass().add("btn-secondary");
        retryFailed.setOnAction(e -> queue.retryAllFailed());

        Button clear = new Button("Clear completed");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> queue.clearCompleted());

        HBox footer = new HBox(10, counts, overall, pauseBtn, limitLbl, throttle, parallelLbl, parallel,
                verify, autoRetry, retryFailed, clear);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(6, 0, 0, 0));

        VBox box = new VBox(6, table, footer);
        box.setPadding(new Insets(6));
        VBox.setVgrow(table, Priority.ALWAYS);
        table.setPrefHeight(160);
        return box;
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        TableColumn<TransferItem, String> dir = new TableColumn<>("");
        dir.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().direction() == TransferItem.Direction.UPLOAD ? "↑" : "↓"));
        dir.setPrefWidth(28);
        dir.setStyle("-fx-alignment: CENTER;");

        TableColumn<TransferItem, String> name = new TableColumn<>("File");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        name.setPrefWidth(220);

        TableColumn<TransferItem, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source().sizeText()));
        size.setPrefWidth(80);
        size.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<TransferItem, TransferItem> prog = new TableColumn<>("Progress");
        prog.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        prog.setCellFactory(c -> new ProgressCell());
        prog.setPrefWidth(160);

        TableColumn<TransferItem, String> speed = new TableColumn<>("Speed");
        speed.setCellValueFactory(c -> new SimpleStringProperty(speedText(c.getValue())));
        speed.setPrefWidth(120);
        speed.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<TransferItem, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status().name().toLowerCase()));
        status.setPrefWidth(90);

        table.getColumns().setAll(dir, name, size, prog, speed, status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No transfers yet"));
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        installRowContextMenu();
    }

    /** Right-click menu: Cancel queued items, Retry failed/cancelled ones. */
    private void installRowContextMenu() {
        javafx.scene.control.MenuItem cancel = new javafx.scene.control.MenuItem("Cancel");
        cancel.setOnAction(e -> {
            for (TransferItem i : table.getSelectionModel().getSelectedItems()) queue.cancel(i);
        });
        javafx.scene.control.MenuItem retry = new javafx.scene.control.MenuItem("Retry");
        retry.setOnAction(e -> {
            for (TransferItem i : table.getSelectionModel().getSelectedItems()) queue.retry(i);
        });
        javafx.scene.control.MenuItem up = new javafx.scene.control.MenuItem("Move up");
        up.setOnAction(e -> moveSelected(-1));
        javafx.scene.control.MenuItem down = new javafx.scene.control.MenuItem("Move down");
        down.setOnAction(e -> moveSelected(1));
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                cancel, retry, new javafx.scene.control.SeparatorMenuItem(), up, down);
        menu.setOnShowing(e -> {
            var sel = table.getSelectionModel().getSelectedItems();
            boolean anyQueued = sel.stream().anyMatch(i -> i.status() == TransferStatus.QUEUED);
            cancel.setDisable(!anyQueued);
            retry.setDisable(sel.stream().noneMatch(i -> i.status().retryable()));
            up.setDisable(sel.size() != 1 || !anyQueued);
            down.setDisable(sel.size() != 1 || !anyQueued);
        });
        table.setContextMenu(menu);
    }

    /** Moves the single selected queued item by {@code delta} and keeps it selected. */
    private void moveSelected(int delta) {
        TransferItem sel = table.getSelectionModel().getSelectedItem();
        if (sel != null && queue.move(sel, delta)) {
            refreshNow();
            table.getSelectionModel().select(sel);
        }
    }

    // ---- TransferQueue.Listener (called off the FX thread) ----

    @Override public void onQueueChanged() { scheduleRepaint(); }
    @Override public void onItemProgress(TransferItem item) { scheduleRepaint(); }

    /** Coalesces a burst of queue events into a single FX-thread repaint. */
    private void scheduleRepaint() {
        if (repaintPending.compareAndSet(false, true)) {
            Platform.runLater(() -> { repaintPending.set(false); refreshNow(); });
        }
    }

    private void refreshNow() {
        rows.setAll(queue.items());
        table.refresh();
        overall.setProgress(queue.size() == 0 ? 0 : queue.overallProgress());
        String base = String.format("%d queued · %d active · %d done · %d skipped · %d failed",
                queue.count(TransferStatus.QUEUED), queue.count(TransferStatus.ACTIVE),
                queue.count(TransferStatus.DONE), queue.count(TransferStatus.SKIPPED),
                queue.count(TransferStatus.FAILED));
        String rate = TransferItem.formatRate(queue.activeBytesPerSecond(System.nanoTime()));
        counts.setText(rate.isEmpty() ? base : base + "  —  " + rate);
        if (queue.hasPending() && !isExpanded()) setExpanded(true);
    }

    /** Maps a throttle preset label to a bytes/second ceiling ({@code 0} = unlimited). */
    static long bytesForPreset(String preset) {
        if (preset == null) return 0;
        return switch (preset) {
            case "512 KB/s" -> 512L * 1024;
            case "1 MB/s"   -> 1024L * 1024;
            case "5 MB/s"   -> 5L * 1024 * 1024;
            case "10 MB/s"  -> 10L * 1024 * 1024;
            default          -> 0;   // "Unlimited"
        };
    }

    /** Per-row speed · ETA text, shown only while a transfer is active. */
    private static String speedText(TransferItem item) {
        if (item.status() != TransferStatus.ACTIVE) return "";
        long now = System.nanoTime();
        String rate = TransferItem.formatRate(item.bytesPerSecond(now));
        if (rate.isEmpty()) return "";
        long eta = item.etaSeconds(now);
        return eta < 0 ? rate : rate + " · " + TransferItem.formatEta(eta);
    }

    /** Per-row progress bar that also shows a short status note for terminal items. */
    private static final class ProgressCell extends TableCell<TransferItem, TransferItem> {
        private final ProgressBar bar = new ProgressBar(0);
        private final Label note = new Label();
        private final HBox box = new HBox(6, bar, note);

        ProgressCell() {
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);
            box.setAlignment(Pos.CENTER_LEFT);
            note.getStyleClass().add("meta-label");
            note.setMinWidth(Region.USE_PREF_SIZE);
        }

        @Override protected void updateItem(TransferItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); return; }
            bar.setProgress(item.progress());
            note.setText(switch (item.status()) {
                case SKIPPED -> "skipped";
                case FAILED -> "failed";
                case CANCELLED -> "cancelled";
                case DONE -> "✔";
                default -> "";
            });
            setGraphic(box);
        }
    }
}
