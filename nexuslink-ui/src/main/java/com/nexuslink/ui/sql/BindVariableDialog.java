package com.nexuslink.ui.sql;

import com.nexuslink.protocol.db.SqlBindVariables.Kind;
import com.nexuslink.protocol.db.SqlBindVariables.Variable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prompts for the values of the {@code :name} / {@code &name} parameters in a statement, the way
 * SQL Developer does when you run a query containing them.
 *
 * <p>The two kinds are labelled differently on purpose: a bind variable is sent to the database as
 * a parameter, while a substitution variable is pasted into the SQL text — the user should be able
 * to see which of the two they are about to do, because only one of them can change what the
 * statement means.
 */
final class BindVariableDialog {

    private BindVariableDialog() {}

    /**
     * Shows the prompt and returns the entered values keyed by bare parameter name, or empty if the
     * user cancelled.
     *
     * @param previous values from earlier in the session, used to pre-fill the fields
     */
    static Optional<Map<String, String>> prompt(Window owner, List<Variable> variables,
                                                Map<String, String> previous) {
        if (variables.isEmpty()) return Optional.of(Map.of());

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Enter Parameter Values");
        dialog.setHeaderText(variables.size() == 1
                ? "This statement uses one parameter."
                : "This statement uses " + variables.size() + " parameters.");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));

        Map<String, TextField> fields = new LinkedHashMap<>();
        int row = 0;
        for (Variable v : variables) {
            Label name = new Label(v.display());
            name.getStyleClass().add("meta-label");

            TextField field = new TextField(previous.getOrDefault(v.name(), ""));
            field.getStyleClass().add("nl-field");
            field.setPrefColumnCount(28);
            field.setPromptText(v.kind() == Kind.BIND ? "value" : "text to substitute");
            fields.put(v.name(), field);

            Label kind = new Label(v.kind() == Kind.BIND ? "bind" : "substitution");
            kind.getStyleClass().add("meta-label");
            kind.setTooltip(new Tooltip(v.kind() == Kind.BIND
                    ? "Sent to the database as a JDBC parameter — the SQL text is not modified."
                    : "Pasted into the SQL text before it runs, like SQL*Plus substitution."
                      + (v.sticky() ? "\n\"&&\" keeps this value for the rest of the session." : "")));

            grid.addRow(row++, name, field, kind);
        }

        Label note = new Label("Bind values (:name) are passed as parameters. "
                + "Substitutions (&name) are inserted into the statement text.");
        note.getStyleClass().add("meta-label");
        note.setWrapText(true);
        note.setMaxWidth(460);
        GridPane.setColumnSpan(note, 3);
        grid.addRow(row, note);

        VBox content = new VBox(grid);
        content.setAlignment(Pos.CENTER_LEFT);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        if (!fields.isEmpty()) {
            TextField first = fields.values().iterator().next();
            javafx.application.Platform.runLater(first::requestFocus);
        }

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((name, field) -> values.put(name, field.getText()));
        return Optional.of(values);
    }
}
