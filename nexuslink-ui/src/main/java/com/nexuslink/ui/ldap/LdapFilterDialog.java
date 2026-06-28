package com.nexuslink.ui.ldap;

import com.nexuslink.protocol.ldap.LdapFilterBuilder;
import com.nexuslink.protocol.ldap.LdapFilterBuilder.Condition;
import com.nexuslink.protocol.ldap.LdapFilterBuilder.Operator;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Offline RFC 4515 filter-builder dialog. The user composes rows of (attribute, operator, value)
 * joined by AND/OR, or picks a predefined filter; a live preview shows the result. All composition
 * is delegated to the tested pure {@link LdapFilterBuilder} — this class is only the UI over it.
 * Returns the filter string the dialog produced, or empty if cancelled.
 */
final class LdapFilterDialog {

    private static final String[] OP_LABELS = {"=", "contains", "starts-with", "present", ">=", "<="};

    private final VBox rowsBox = new VBox(6);
    private final List<Row> rows = new ArrayList<>();
    private final ToggleGroup joinGroup = new ToggleGroup();
    private final RadioButton andBtn = new RadioButton("AND");
    private final RadioButton orBtn = new RadioButton("OR");
    private final TextField preview = new TextField();

    private LdapFilterDialog() {
    }

    /** Open the dialog, seeded with nothing, and return the composed filter when accepted. */
    static Optional<String> open(Window owner) {
        return new LdapFilterDialog().show(owner);
    }

    private Optional<String> show(Window owner) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Filter builder");
        dialog.setHeaderText("Compose an LDAP (RFC 4515) search filter");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        andBtn.setToggleGroup(joinGroup);
        orBtn.setToggleGroup(joinGroup);
        andBtn.setSelected(true);
        andBtn.setOnAction(e -> recompute());
        orBtn.setOnAction(e -> recompute());

        Button addRow = new Button("+ Add condition");
        addRow.getStyleClass().add("btn-secondary");
        addRow.setOnAction(e -> { addRow(); recompute(); });

        HBox joinRow = new HBox(8, new Label("Join:"), andBtn, orBtn, addRow);
        joinRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> predefined = new ComboBox<>();
        predefined.getItems().addAll(
                "All persons", "All groups", "All organizational units", "By uid…", "By cn…");
        predefined.setPromptText("Predefined filter…");
        predefined.setOnAction(e -> applyPredefined(predefined.getValue(), owner));

        HBox predefinedRow = new HBox(8, new Label("Predefined:"), predefined);
        predefinedRow.setAlignment(Pos.CENTER_LEFT);

        preview.setEditable(false);
        preview.getStyleClass().add("nl-field");
        HBox.setHgrow(preview, Priority.ALWAYS);
        HBox previewRow = new HBox(8, new Label("Filter:"), preview);
        previewRow.setAlignment(Pos.CENTER_LEFT);

        addRow();   // start with one empty row
        recompute();

        VBox content = new VBox(10, predefinedRow, new Separator(), joinRow, rowsBox,
                new Separator(), previewRow);
        content.setPadding(new Insets(12));
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? preview.getText() : null);
        return dialog.showAndWait();
    }

    private void addRow() {
        Row row = new Row();
        rows.add(row);
        rowsBox.getChildren().add(row.node);
    }

    private void removeRow(Row row) {
        rows.remove(row);
        rowsBox.getChildren().remove(row.node);
        if (rows.isEmpty()) addRow();
        recompute();
    }

    private void recompute() {
        List<Condition> conditions = new ArrayList<>();
        for (Row row : rows) {
            String attr = row.attribute.getEditor().getText();
            if (attr == null || attr.isBlank()) continue;
            conditions.add(Condition.of(attr.trim(), operatorOf(row.operator.getValue()), row.value.getText()));
        }
        preview.setText(LdapFilterBuilder.build(conditions, andBtn.isSelected()));
    }

    private void applyPredefined(String choice, Window owner) {
        if (choice == null) return;
        switch (choice) {
            case "All persons" -> preview.setText(LdapFilterBuilder.allPersons());
            case "All groups" -> preview.setText(LdapFilterBuilder.allGroups());
            case "All organizational units" -> preview.setText(LdapFilterBuilder.allOrganizationalUnits());
            case "By uid…" -> prompt(owner, "uid", v -> preview.setText(LdapFilterBuilder.byUid(v)));
            case "By cn…" -> prompt(owner, "cn", v -> preview.setText(LdapFilterBuilder.byCommonName(v)));
            default -> { }
        }
    }

    private void prompt(Window owner, String label, java.util.function.Consumer<String> onValue) {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog();
        d.setTitle("Predefined filter");
        d.setHeaderText("Value for " + label);
        d.setContentText(label + ":");
        if (owner != null) d.initOwner(owner);
        d.showAndWait().ifPresent(onValue);
    }

    private static Operator operatorOf(String label) {
        return LdapFilterBuilder.operatorOf(label);
    }

    /** One editable condition row in the builder. */
    private final class Row {
        final ComboBox<String> attribute = new ComboBox<>();
        final ComboBox<String> operator = new ComboBox<>();
        final TextField value = new TextField();
        final HBox node;

        Row() {
            attribute.setEditable(true);
            attribute.getItems().addAll("objectClass", "cn", "uid", "sn", "givenName", "mail",
                    "ou", "member", "description");
            attribute.setPromptText("attribute");
            attribute.setPrefWidth(160);
            attribute.getEditor().textProperty().addListener((o, ov, nv) -> recompute());

            operator.getItems().addAll(OP_LABELS);
            operator.setValue("=");
            operator.setPrefWidth(120);
            operator.setOnAction(e -> {
                value.setDisable(operatorOf(operator.getValue()) == Operator.PRESENT);
                recompute();
            });

            value.setPromptText("value");
            HBox.setHgrow(value, Priority.ALWAYS);
            value.textProperty().addListener((o, ov, nv) -> recompute());

            Button remove = new Button("✕");
            remove.getStyleClass().add("btn-secondary");
            remove.setOnAction(e -> removeRow(this));

            node = new HBox(8, attribute, operator, value, remove);
            node.setAlignment(Pos.CENTER_LEFT);
        }
    }
}
