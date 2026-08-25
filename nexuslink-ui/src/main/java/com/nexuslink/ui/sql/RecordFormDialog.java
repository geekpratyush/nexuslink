package com.nexuslink.ui.sql;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A single-record form over the result grid: one labelled field per column, with navigation
 * through the rows. This is the form-entry view SQL Developer calls "single record view", and it
 * is the practical way to read or edit a row of a wide table, where a grid forces horizontal
 * scrolling and it stops being obvious which value belongs to which column.
 *
 * <p>The dialog never touches JDBC. Saving hands the edited values back to {@link SqlClientView},
 * which routes them through the same SQL-preview "Apply" gate as an in-grid edit, so the user
 * always sees the {@code UPDATE} before it runs.
 */
final class RecordFormDialog {

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final List<String> columns;
    private final List<List<String>> rows;
    private final List<String> primaryKey;
    private final boolean editable;

    /** Called with (original row, edited values by column) when the user saves. */
    private final BiConsumer<List<String>, Map<String, String>> onSave;

    private final GridPane form = new GridPane();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Label position = new Label();
    private final Button prev = new Button("◀ Previous");
    private final Button next = new Button("Next ▶");
    private final Button save = new Button("Save…");

    private int index;

    private RecordFormDialog(Window owner, String title, List<String> columns, List<List<String>> rows,
                             int startIndex, List<String> primaryKey, boolean editable,
                             BiConsumer<List<String>, Map<String, String>> onSave) {
        this.columns = columns;
        this.rows = rows;
        this.primaryKey = primaryKey;
        this.editable = editable;
        this.onSave = onSave;
        this.index = Math.max(0, Math.min(startIndex, rows.size() - 1));

        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setResizable(true);

        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(14));

        buildFields();

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(420);

        prev.getStyleClass().add("btn-secondary");
        prev.setOnAction(e -> move(-1));
        next.getStyleClass().add("btn-secondary");
        next.setOnAction(e -> move(1));
        position.getStyleClass().add("meta-label");

        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            dialog.setResult(ButtonType.CLOSE);
            dialog.close();
            onSave.accept(rows.get(index), currentValues());
        });
        save.setVisible(editable);
        save.setManaged(editable);
        save.setTooltip(new Tooltip("Review the generated UPDATE before it runs"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox nav = new HBox(8, prev, next, position, spacer, save);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.setPadding(new Insets(0, 14, 0, 14));

        Label note = new Label(editable
                ? "Edit any field and choose Save to generate an UPDATE for this row."
                : "Read-only: this result isn't a single-table SELECT with a primary key, "
                  + "so a row can't be identified for update.");
        note.getStyleClass().add("meta-label");
        note.setWrapText(true);
        note.setPadding(new Insets(0, 14, 8, 14));

        VBox root = new VBox(8, scroll, nav, note);
        root.setPrefWidth(560);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        showRow();
    }

    /**
     * Opens the form over {@code rows}, starting at {@code startIndex}.
     *
     * @param primaryKey PK column names, empty when the result isn't editable
     * @param onSave     receives the original row and the edited values; ignored when not editable
     */
    static void open(Window owner, String title, List<String> columns, List<List<String>> rows,
                     int startIndex, List<String> primaryKey, boolean editable,
                     BiConsumer<List<String>, Map<String, String>> onSave) {
        if (columns.isEmpty() || rows.isEmpty()) return;
        new RecordFormDialog(owner, title, columns, rows, startIndex, primaryKey, editable, onSave)
                .dialog.showAndWait();
    }

    private void buildFields() {
        int row = 0;
        for (String column : columns) {
            Label name = new Label(column + (primaryKey.contains(column) ? "  (key)" : ""));
            name.getStyleClass().add("meta-label");
            name.setMinWidth(160);

            TextField field = new TextField();
            field.getStyleClass().add("nl-field");
            // Primary-key columns stay read-only: changing one silently re-targets the UPDATE.
            field.setEditable(editable && !primaryKey.contains(column));
            if (!field.isEditable()) field.setFocusTraversable(false);
            GridPane.setHgrow(field, Priority.ALWAYS);
            fields.put(column, field);

            form.addRow(row++, name, field);
        }
    }

    private void move(int delta) {
        index = Math.max(0, Math.min(index + delta, rows.size() - 1));
        showRow();
    }

    private void showRow() {
        List<String> row = rows.get(index);
        for (int i = 0; i < columns.size(); i++) {
            fields.get(columns.get(i)).setText(i < row.size() ? row.get(i) : "");
        }
        position.setText("Row " + (index + 1) + " of " + rows.size());
        prev.setDisable(index == 0);
        next.setDisable(index == rows.size() - 1);
    }

    private Map<String, String> currentValues() {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((column, field) -> values.put(column, field.getText()));
        return values;
    }
}
