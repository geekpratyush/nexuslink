package com.nexuslink.ui.kafka;

import com.nexuslink.protocol.kafka.OffsetResetPlanner.ResetRow;
import com.nexuslink.protocol.kafka.OffsetResetPlanner.Strategy;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * A consumer-group offset-reset tool: the user picks a strategy (earliest / latest / specific offset /
 * timestamp / shift-by), previews the resulting per-partition target offsets (computed by the pure
 * {@link com.nexuslink.protocol.kafka.OffsetResetPlanner} via the broker fetch), then applies them.
 * The dialog owns its background tasks so the broker round-trips never block the FX thread; the actual
 * fetch/plan and the {@code alterConsumerGroupOffsets} apply are supplied as callbacks so this class
 * carries no Kafka types beyond the plain {@link ResetRow}.
 */
final class OffsetResetDialog {

    /** Fetches offsets and returns the planned rows for a strategy (runs off the FX thread). */
    interface PreviewFn { List<ResetRow> run(Strategy strategy, long arg, long timestampMillis) throws Exception; }

    /** Commits the planned rows via alterConsumerGroupOffsets (runs off the FX thread). */
    interface ApplyFn { void run(List<ResetRow> rows) throws Exception; }

    private final Window owner;
    private final String group;
    private final PreviewFn preview;
    private final ApplyFn apply;
    private final Runnable onApplied;

    private final ComboBox<Strategy> strategy = new ComboBox<>();
    private final TextField argField = new TextField();
    private final TextField tsField = new TextField();
    private final Label status = new Label();
    private final TableView<ResetRow> table = new TableView<>();

    private List<ResetRow> previewed = List.of();
    private Button applyBtn;

    OffsetResetDialog(Window owner, String group, PreviewFn preview, ApplyFn apply, Runnable onApplied) {
        this.owner = owner;
        this.group = group;
        this.preview = preview;
        this.apply = apply;
        this.onApplied = onApplied == null ? () -> {} : onApplied;
    }

    void show() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Reset offsets");
        dialog.setHeaderText("Reset committed offsets for consumer group \"" + group + "\"");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });

        strategy.getItems().setAll(Strategy.values());
        strategy.setValue(Strategy.EARLIEST);
        strategy.setOnAction(e -> updateFieldState());
        argField.getStyleClass().add("nl-field");
        argField.setPromptText("offset / delta");
        argField.setPrefWidth(120);
        tsField.getStyleClass().add("nl-field");
        tsField.setPromptText("epoch millis");
        tsField.setPrefWidth(160);

        Button previewBtn = new Button("Preview");
        previewBtn.getStyleClass().add("btn-secondary");
        previewBtn.setOnAction(e -> doPreview());
        applyBtn = new Button("Apply");
        applyBtn.getStyleClass().add("btn-primary");
        applyBtn.setDisable(true);
        applyBtn.setOnAction(e -> doApply());
        status.getStyleClass().add("meta-label");

        HBox controls = new HBox(8, new Label("Strategy:"), strategy,
                new Label("Value:"), argField, new Label("Time:"), tsField, previewBtn, applyBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        updateFieldState();

        VBox content = new VBox(10, controls, table, status);
        content.setPadding(new Insets(12));
        content.setPrefSize(620, 380);
        VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    /** Enables the offset/delta field only for SPECIFIC/SHIFT and the timestamp field only for TIMESTAMP. */
    private void updateFieldState() {
        Strategy s = strategy.getValue();
        argField.setDisable(!(s == Strategy.SPECIFIC_OFFSET || s == Strategy.SHIFT_BY));
        tsField.setDisable(s != Strategy.TIMESTAMP);
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Preview a strategy to see the per-partition target offsets."));
        TableColumn<ResetRow, String> topic = new TableColumn<>("Topic");
        topic.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().topic()));
        TableColumn<ResetRow, Number> part = numeric("Partition", r -> r.partition());
        TableColumn<ResetRow, Number> current = numeric("Current", ResetRow::current);
        TableColumn<ResetRow, Number> target = numeric("Target", ResetRow::target);
        TableColumn<ResetRow, Number> delta = numeric("Δ", ResetRow::delta);
        table.getColumns().setAll(topic, part, current, target, delta);
    }

    private TableColumn<ResetRow, Number> numeric(String title, java.util.function.ToLongFunction<ResetRow> get) {
        TableColumn<ResetRow, Number> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleLongProperty(get.applyAsLong(c.getValue())));
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    private void doPreview() {
        long arg = 0, ts = 0;
        Strategy s = strategy.getValue();
        try {
            if (s == Strategy.SPECIFIC_OFFSET || s == Strategy.SHIFT_BY) arg = Long.parseLong(argField.getText().trim());
            if (s == Strategy.TIMESTAMP) ts = Long.parseLong(tsField.getText().trim());
        } catch (NumberFormatException ex) {
            status.setText("Enter a valid " + (s == Strategy.TIMESTAMP ? "epoch-millis timestamp" : "integer offset/delta"));
            return;
        }
        applyBtn.setDisable(true);
        status.setText("Fetching offsets…");
        long fArg = arg, fTs = ts;
        Task<List<ResetRow>> task = new Task<>() {
            @Override protected List<ResetRow> call() throws Exception { return preview.run(s, fArg, fTs); }
        };
        task.setOnSucceeded(e -> {
            previewed = task.getValue();
            table.setItems(FXCollections.observableArrayList(previewed));
            long moves = previewed.stream().filter(r -> r.delta() != 0).count();
            status.setText(previewed.size() + " partition(s) · " + moves + " will move");
            applyBtn.setDisable(previewed.isEmpty() || moves == 0);
        });
        task.setOnFailed(e -> status.setText("✖ " + task.getException().getMessage()));
        run(task);
    }

    private void doApply() {
        if (previewed.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Commit new offsets for " + previewed.size() + " partition(s) of group \"" + group + "\"? "
                        + "The group must have no active members or the broker will reject it.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Apply offset reset");
        if (owner != null) confirm.initOwner(owner);
        confirm.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        applyBtn.setDisable(true);
        status.setText("Applying…");
        List<ResetRow> rows = previewed;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception { apply.run(rows); return null; }
        };
        task.setOnSucceeded(e -> { status.setText("✔ Applied to " + rows.size() + " partition(s)"); onApplied.run(); });
        task.setOnFailed(e -> {
            status.setText("✖ " + task.getException().getMessage());
            applyBtn.setDisable(false);
        });
        run(task);
    }

    private void run(Task<?> task) {
        Thread t = new Thread(task, "kafka-offset-reset");
        t.setDaemon(true);
        t.start();
    }
}
