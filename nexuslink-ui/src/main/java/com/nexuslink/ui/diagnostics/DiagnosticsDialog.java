package com.nexuslink.ui.diagnostics;

import com.nexuslink.core.diagnostics.ConnectionDiagnostics;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.stage.Window;

import java.util.List;

/**
 * A reusable connection-diagnostics dialog: runs a list of {@link ConnectionDiagnostics.Step}s on a
 * background thread and fills a live Step/Status/Detail/Time table as each step completes (via the
 * runner's per-step callback). The pure sequencing/stop-on-failure logic lives in
 * {@link ConnectionDiagnostics}; this class is only the UI over it, so any protocol view can offer a
 * "Diagnose" action by handing it {@code NetworkProbes.basicSteps(...)} (plus any protocol steps).
 */
public final class DiagnosticsDialog {

    private DiagnosticsDialog() {}

    /** Opens the dialog for {@code title} and immediately runs {@code steps} off the FX thread. */
    public static void run(Window owner, String title, List<ConnectionDiagnostics.Step> steps) {
        ObservableList<ConnectionDiagnostics.StepResult> rows = FXCollections.observableArrayList();
        TableView<ConnectionDiagnostics.StepResult> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Running…"));
        com.nexuslink.ui.util.TableContextMenus.installCopy(table);

        TableColumn<ConnectionDiagnostics.StepResult, String> step = new TableColumn<>("Step");
        step.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        step.setPrefWidth(90);
        TableColumn<ConnectionDiagnostics.StepResult, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new SimpleStringProperty(icon(c.getValue().status())));
        status.setPrefWidth(90);
        TableColumn<ConnectionDiagnostics.StepResult, String> detail = new TableColumn<>("Detail");
        detail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detail()));
        TableColumn<ConnectionDiagnostics.StepResult, String> ms = new TableColumn<>("Time");
        ms.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().elapsedMs() + " ms"));
        ms.setStyle("-fx-alignment: CENTER-RIGHT;");
        ms.setPrefWidth(70);
        table.getColumns().add(step);
        table.getColumns().add(status);
        table.getColumns().add(detail);
        table.getColumns().add(ms);
        table.setPrefSize(560, 260);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Connection diagnostics");
        dialog.setHeaderText(title);
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().setContent(table);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });

        Thread t = new Thread(() -> ConnectionDiagnostics.run(steps,
                result -> Platform.runLater(() -> {
                    rows.add(result);
                    table.setPlaceholder(new Label("No steps"));
                })), "diagnostics");
        t.setDaemon(true);
        t.start();

        dialog.showAndWait();
    }

    private static String icon(ConnectionDiagnostics.Status s) {
        return switch (s) {
            case PASSED -> "✔ passed";
            case FAILED -> "✘ failed";
            case SKIPPED -> "– skipped";
        };
    }
}
