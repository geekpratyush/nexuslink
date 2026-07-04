package com.nexuslink.ui.files;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares the two panes' current directories and lets the user turn the difference into a sync plan.
 * The table (name · left · right · status) and the plan are produced entirely by the tested pure
 * {@link DirectoryDiff} and {@link SyncPlanner}; this class is only the UI over them. Accepting "Run
 * sync" returns the chosen {@link SyncPlanner.Action} list for the container to execute (copies via
 * the transfer queue, deletes via the file system); cancelling returns empty.
 */
final class DirectoryCompareDialog {

    private final List<FileItem> left;
    private final List<FileItem> right;
    private final String leftName;
    private final String rightName;
    private final List<DirectoryDiff.Entry> diff;

    private final TableView<DirectoryDiff.Entry> table = new TableView<>();
    private final CheckBox showSame = new CheckBox("Show identical");
    private final ComboBox<Mode> mode = new ComboBox<>();
    private final Label planLabel = new Label();

    /** The four sync directions, labelled in the left/right = local/remote vocabulary of the panes. */
    private enum Mode {
        MIRROR_TO_RIGHT("Mirror → right (make right match left)", SyncPlanner.Mode.MIRROR_TO_RIGHT),
        MIRROR_TO_LEFT("Mirror ← left (make left match right)", SyncPlanner.Mode.MIRROR_TO_LEFT),
        UPDATE_RIGHT("Update right (copy new/changed →, no delete)", SyncPlanner.Mode.UPDATE_RIGHT),
        UPDATE_LEFT("Update left (copy new/changed ←, no delete)", SyncPlanner.Mode.UPDATE_LEFT);
        final String label;
        final SyncPlanner.Mode planner;
        Mode(String label, SyncPlanner.Mode planner) { this.label = label; this.planner = planner; }
        @Override public String toString() { return label; }
    }

    private DirectoryCompareDialog(List<FileItem> left, List<FileItem> right, String leftName, String rightName) {
        this.left = left;
        this.right = right;
        this.leftName = leftName;
        this.rightName = rightName;
        this.diff = DirectoryDiff.compare(left, right);
    }

    static Optional<List<SyncPlanner.Action>> open(Window owner, List<FileItem> left, List<FileItem> right,
                                                   String leftName, String rightName) {
        return new DirectoryCompareDialog(left, right, leftName, rightName).show(owner);
    }

    private Optional<List<SyncPlanner.Action>> show(Window owner) {
        Dialog<List<SyncPlanner.Action>> dialog = new Dialog<>();
        dialog.setTitle("Compare directories");
        dialog.setHeaderText(summaryText());
        if (owner != null) dialog.initOwner(owner);
        ButtonType run = new ButtonType("Run sync", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(run, ButtonType.CANCEL);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });

        buildTable();
        showSame.setOnAction(e -> applyFilter());
        mode.getItems().setAll(Mode.values());
        mode.setValue(Mode.UPDATE_RIGHT);
        mode.setOnAction(e -> updatePlanLabel());
        planLabel.getStyleClass().add("meta-label");

        HBox modeRow = new HBox(8, new Label("Sync:"), mode, planLabel);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, showSame, table, new Separator(), modeRow);
        content.setPadding(new Insets(12));
        content.setPrefSize(660, 420);
        dialog.getDialogPane().setContent(content);

        applyFilter();
        updatePlanLabel();

        dialog.setResultConverter(bt ->
                bt == run ? SyncPlanner.plan(diff, mode.getValue().planner) : null);
        // An empty plan (nothing to do) is treated as no-op even if "Run sync" is pressed.
        return dialog.showAndWait().filter(plan -> !plan.isEmpty());
    }

    private String summaryText() {
        Map<DirectoryDiff.Status, Integer> s = DirectoryDiff.summary(diff);
        return String.format("%s  vs  %s   —   %d new left · %d new right · %d differ · %d identical",
                leftName, rightName,
                s.get(DirectoryDiff.Status.LEFT_ONLY), s.get(DirectoryDiff.Status.RIGHT_ONLY),
                s.get(DirectoryDiff.Status.DIFFERENT), s.get(DirectoryDiff.Status.SAME));
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        TableColumn<DirectoryDiff.Entry, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().directory() ? "📁 " : "📄 ") + c.getValue().name()));
        name.setPrefWidth(240);

        TableColumn<DirectoryDiff.Entry, String> leftCol = new TableColumn<>("Left");
        leftCol.setCellValueFactory(c -> new SimpleStringProperty(sideText(c.getValue().left())));
        leftCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        leftCol.setPrefWidth(120);

        TableColumn<DirectoryDiff.Entry, String> rightCol = new TableColumn<>("Right");
        rightCol.setCellValueFactory(c -> new SimpleStringProperty(sideText(c.getValue().right())));
        rightCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        rightCol.setPrefWidth(120);

        TableColumn<DirectoryDiff.Entry, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new SimpleStringProperty(statusText(c.getValue().status())));
        status.setPrefWidth(150);

        table.getColumns().setAll(name, leftCol, rightCol, status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // Tint each row by its diff status so new/changed entries stand out from identical ones.
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(DirectoryDiff.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(empty || item == null ? "" : "-fx-text-fill: " + colour(item.status()) + ";");
            }
        });
    }

    private void applyFilter() {
        boolean withSame = showSame.isSelected();
        List<DirectoryDiff.Entry> rows = diff.stream()
                .filter(e -> withSame || e.status() != DirectoryDiff.Status.SAME)
                .toList();
        table.setItems(FXCollections.observableArrayList(rows));
    }

    private void updatePlanLabel() {
        Map<SyncPlanner.Op, Integer> s = SyncPlanner.summary(SyncPlanner.plan(diff, mode.getValue().planner));
        int copyR = s.get(SyncPlanner.Op.COPY_LEFT_TO_RIGHT);
        int copyL = s.get(SyncPlanner.Op.COPY_RIGHT_TO_LEFT);
        int del = s.get(SyncPlanner.Op.DELETE_LEFT) + s.get(SyncPlanner.Op.DELETE_RIGHT);
        planLabel.setText(String.format("Plan: %d copy → · %d copy ← · %d delete", copyR, copyL, del));
    }

    private static String sideText(FileItem f) {
        if (f == null) return "—";
        return f.directory() ? "<dir>" : FileItem.humanSize(f.size());
    }

    private static String statusText(DirectoryDiff.Status s) {
        return switch (s) {
            case LEFT_ONLY -> "New on left";
            case RIGHT_ONLY -> "New on right";
            case DIFFERENT -> "Differs";
            case SAME -> "Identical";
        };
    }

    private static String colour(DirectoryDiff.Status s) {
        return switch (s) {
            case LEFT_ONLY -> "#4a9e5c";     // green — present only on the left
            case RIGHT_ONLY -> "#3f83c7";    // blue — present only on the right
            case DIFFERENT -> "#c78a3f";     // amber — changed
            case SAME -> "-fx-text-base-color";
        };
    }
}
