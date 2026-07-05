package com.nexuslink.ui.sql;

import com.nexuslink.protocol.db.SqlQueryBuilder;
import com.nexuslink.protocol.db.SqlQueryBuilder.Condition;
import com.nexuslink.protocol.db.SqlQueryBuilder.Direction;
import com.nexuslink.protocol.db.SqlQueryBuilder.Operator;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Visual SELECT builder. The user picks a table, ticks columns, adds {@code WHERE} rows (column /
 * operator / value), chooses an {@code ORDER BY} + direction and a {@code LIMIT}; a live, read-only
 * highlighted preview shows the SQL as it is assembled by the pure {@link SqlQueryBuilder}. On OK the
 * generated statement is handed back to the caller to drop into the editor.
 *
 * <p>All database metadata (table list, columns) is supplied by the caller — fetched off the UI
 * thread by {@link SqlClientView} — so this dialog performs no blocking JDBC work itself.
 */
final class QueryBuilderDialog {

    /** How the dialog fetches a table's column names (already stripped of type text). Runs on the FX thread. */
    interface ColumnProvider {
        void columnsFor(String table, Consumer<List<String>> onColumns);
    }

    private final Dialog<ButtonType> dialog = new Dialog<>();

    private final ComboBox<String> tableCombo = new ComboBox<>();
    private final VBox columnList = new VBox(4);
    private final VBox conditionRows = new VBox(6);
    private final ComboBox<String> orderCombo = new ComboBox<>();
    private final ComboBox<Direction> orderDirCombo = new ComboBox<>(FXCollections.observableArrayList(Direction.values()));
    private final TextField limitField = new TextField();
    private final CodeArea preview = SqlHighlighter.area();

    private final ColumnProvider columnProvider;
    private final List<CheckBox> columnBoxes = new ArrayList<>();
    private final List<ConditionRow> rows = new ArrayList<>();
    // Columns of the currently selected table, shared by the column pickers, WHERE combos and ORDER BY.
    private List<String> currentColumns = new ArrayList<>();

    QueryBuilderDialog(javafx.stage.Window owner, List<String> tables, ColumnProvider columnProvider) {
        this.columnProvider = columnProvider;
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Query Builder");
        dialog.setHeaderText("Assemble a SELECT visually — the SQL updates live below.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);
        dialog.setOnShown(ev -> {
            if (dialog.getDialogPane().getScene() != null)
                com.nexuslink.ui.theme.ThemeManager.get().register(dialog.getDialogPane().getScene());
        });

        tableCombo.getItems().setAll(tables);
        tableCombo.valueProperty().addListener((o, ov, nv) -> onTableSelected(nv));

        orderDirCombo.getSelectionModel().select(Direction.ASC);
        orderCombo.setPromptText("(none)");
        orderCombo.valueProperty().addListener((o, ov, nv) -> updatePreview());
        orderDirCombo.valueProperty().addListener((o, ov, nv) -> updatePreview());
        limitField.getStyleClass().add("nl-field");
        limitField.setPromptText("e.g. 100");
        limitField.setPrefWidth(100);
        limitField.textProperty().addListener((o, ov, nv) -> updatePreview());

        Button addCond = new Button("+ Add condition");
        addCond.getStyleClass().add("btn-secondary");
        addCond.setOnAction(e -> addConditionRow());

        preview.setEditable(false);
        VirtualizedScrollPane<CodeArea> previewScroll = new VirtualizedScrollPane<>(preview);
        previewScroll.setPrefHeight(90);

        Label tableL = meta("Table:");
        Label colsL = meta("Columns  (none ticked = all):");
        Label whereL = meta("Where  (combined with AND):");
        Label orderL = meta("Order by:");
        Label limitL = meta("Limit:");

        ScrollPane colScroll = new ScrollPane(columnList);
        colScroll.setFitToWidth(true);
        colScroll.setPrefViewportHeight(120);
        colScroll.setMinHeight(120);

        HBox orderRow = new HBox(8, orderL, orderCombo, orderDirCombo, new Region(), limitL, limitField);
        orderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(orderRow.getChildren().get(3), Priority.ALWAYS);

        VBox form = new VBox(10,
                new HBox(8, tableL, tableCombo),
                new Separator(),
                colsL, colScroll,
                new Separator(),
                whereL, conditionRows, addCond,
                new Separator(),
                orderRow,
                new Separator(),
                meta("SQL preview:"), previewScroll);
        form.setPadding(new Insets(12));
        form.setPrefWidth(620);
        ((HBox) form.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);

        dialog.getDialogPane().setContent(form);
        updatePreview();

        // Disable OK until a table is chosen (the builder requires one).
        javafx.application.Platform.runLater(() -> {
            var ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
            if (ok != null) ok.disableProperty().bind(tableCombo.valueProperty().isNull());
        });

        if (!tables.isEmpty()) tableCombo.getSelectionModel().select(0);
    }

    /** Shows the dialog and returns the built SQL if the user confirmed with a table selected. */
    Optional<String> showAndBuild() {
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK || tableCombo.getValue() == null) return Optional.empty();
        return Optional.of(buildSql());
    }

    private void onTableSelected(String table) {
        columnList.getChildren().clear();
        columnBoxes.clear();
        conditionRows.getChildren().clear();
        rows.clear();
        orderCombo.getItems().clear();
        currentColumns = new ArrayList<>();
        if (table == null) { updatePreview(); return; }
        columnProvider.columnsFor(table, cols -> {
            currentColumns = new ArrayList<>(cols);
            for (String c : cols) {
                CheckBox cb = new CheckBox(c);
                cb.selectedProperty().addListener((o, ov, nv) -> updatePreview());
                columnBoxes.add(cb);
                columnList.getChildren().add(cb);
            }
            orderCombo.getItems().setAll(cols);
            for (ConditionRow r : rows) r.setColumns(cols);
            updatePreview();
        });
        updatePreview();
    }

    private void addConditionRow() {
        ConditionRow row = new ConditionRow(currentColumns, () -> removeConditionRow(null), this::updatePreview);
        row.remove.setOnAction(e -> removeConditionRow(row));
        rows.add(row);
        conditionRows.getChildren().add(row.node);
        updatePreview();
    }

    private void removeConditionRow(ConditionRow row) {
        if (row == null) return;
        rows.remove(row);
        conditionRows.getChildren().remove(row.node);
        updatePreview();
    }

    private void updatePreview() {
        try {
            preview.replaceText(buildSql());
        } catch (RuntimeException ex) {
            preview.replaceText("-- pick a table to start");
        }
    }

    private String buildSql() {
        SqlQueryBuilder b = new SqlQueryBuilder().table(tableCombo.getValue());
        for (CheckBox cb : columnBoxes) if (cb.isSelected()) b.column(cb.getText());
        for (ConditionRow r : rows) {
            Condition c = r.toCondition();
            if (c != null) b.where(c);
        }
        if (orderCombo.getValue() != null) b.orderBy(orderCombo.getValue(), orderDirCombo.getValue());
        Integer lim = parseLimit(limitField.getText());
        if (lim != null) b.limit(lim);
        return b.build() + ";";
    }

    private static Integer parseLimit(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Label meta(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    /** One WHERE row: a column combo, an operator combo, a value field, and a remove button. */
    private static final class ConditionRow {
        final HBox node;
        final ComboBox<String> column = new ComboBox<>();
        final ComboBox<Operator> operator = new ComboBox<>(FXCollections.observableArrayList(Operator.values()));
        final TextField value = new TextField();
        final Button remove = new Button("✕");

        ConditionRow(List<String> columns, Runnable ignored, Runnable onChange) {
            column.getItems().setAll(columns);
            if (!columns.isEmpty()) column.getSelectionModel().select(0);
            operator.getSelectionModel().select(Operator.EQ);
            value.getStyleClass().add("nl-field");
            value.setPromptText("value");
            HBox.setHgrow(value, Priority.ALWAYS);
            remove.getStyleClass().add("btn-secondary");

            // IS NULL / IS NOT NULL take no value — grey the field out for them.
            operator.valueProperty().addListener((o, ov, nv) -> {
                boolean needsValue = nv == null || nv.takesValue();
                value.setDisable(!needsValue);
                onChange.run();
            });
            column.valueProperty().addListener((o, ov, nv) -> onChange.run());
            value.textProperty().addListener((o, ov, nv) -> onChange.run());

            node = new HBox(8, column, operator, value, remove);
            node.setAlignment(Pos.CENTER_LEFT);
        }

        void setColumns(List<String> columns) {
            column.getItems().setAll(columns);
            if (!columns.isEmpty()) column.getSelectionModel().select(0);
        }

        Condition toCondition() {
            String col = column.getValue();
            Operator op = operator.getValue();
            if (col == null || col.isBlank() || op == null) return null;
            return new Condition(col, op, op.takesValue() ? value.getText() : null);
        }
    }
}
