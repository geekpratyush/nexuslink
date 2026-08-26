package com.nexuslink.ui.controls;

import com.nexuslink.core.security.UriRedactor;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/**
 * A connection string shown the way a connection string should be: as a short chip naming what you
 * are connected to, which turns into a full editable field only when you actually want to edit it.
 *
 * <p>Two problems with a permanently expanded text box. It eats the toolbar — a Mongo URI with a
 * replica set and options is longer than the rest of the controls put together, so everything else
 * gets squeezed. And it puts any embedded password on display: {@code mongodb://app:s3cret@…} is a
 * password on every screenshot and screen share. The chip shows
 * {@code db.internal:27017/orders} (or the saved connection's name), keeps the real value intact
 * underneath, and reveals it only on demand.
 *
 * <p>Double-click, Enter, or the edit button opens the editor; Enter commits, Escape cancels back to
 * the value it had. The full string is never shown while collapsed — the tooltip and the copy action
 * are redacted too — so a reveal is always a deliberate act.
 */
public final class ConnectionChip extends StackPane {

    private final Label chip = new Label();
    private final TextField editor = new TextField();
    private final HBox collapsed;
    private final StringProperty value = new SimpleStringProperty("");
    private final StringProperty name = new SimpleStringProperty("");
    private Runnable onCommit = () -> { };

    public ConnectionChip() {
        this("Connection");
    }

    public ConnectionChip(String promptText) {
        getStyleClass().add("connection-chip");

        chip.getStyleClass().add("connection-chip-label");
        chip.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(chip, Priority.ALWAYS);

        Button edit = new Button("✎");
        edit.getStyleClass().add("btn-secondary");
        edit.setTooltip(new Tooltip("Edit the connection string"));
        edit.setOnAction(e -> startEditing());

        collapsed = new HBox(6, chip, edit);
        collapsed.setAlignment(Pos.CENTER_LEFT);
        collapsed.setPadding(new Insets(0));

        editor.getStyleClass().add("nl-field");
        editor.setPromptText(promptText);
        editor.setVisible(false);
        editor.setManaged(false);

        chip.setOnMouseClicked(e -> { if (e.getClickCount() == 2) startEditing(); });
        chip.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) startEditing(); });
        chip.setFocusTraversable(true);
        chip.setContextMenu(buildMenu());

        editor.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> { commit(); e.consume(); }
                case ESCAPE -> { cancel(); e.consume(); }
                default -> { }
            }
        });
        editor.focusedProperty().addListener((o, was, focused) -> { if (was && !focused) commit(); });

        value.addListener((o, ov, nv) -> refresh());
        name.addListener((o, ov, nv) -> refresh());
        refresh();

        getChildren().addAll(collapsed, editor);
    }

    /** The connection string itself — always the real value, never the redacted one. */
    public StringProperty valueProperty() { return value; }

    public String getValue() { return value.get(); }

    public void setValue(String newValue) { value.set(newValue == null ? "" : newValue); }

    /** An optional friendly name (a saved connection's name) shown instead of the host. */
    public StringProperty nameProperty() { return name; }

    public void setName(String newName) { name.set(newName == null ? "" : newName); }

    /** Called when an edit is committed — connect, or mark the profile dirty. */
    public void setOnCommit(Runnable action) { this.onCommit = action == null ? () -> { } : action; }

    /** Opens the editor with the full value selected. */
    public void startEditing() {
        editor.setText(value.get());
        editor.setVisible(true);
        editor.setManaged(true);
        collapsed.setVisible(false);
        collapsed.setManaged(false);
        editor.requestFocus();
        editor.selectAll();
    }

    /** {@code true} while the full string is on screen. */
    public boolean isEditing() { return editor.isVisible(); }

    private void commit() {
        if (!editor.isVisible()) return;
        String edited = editor.getText() == null ? "" : editor.getText().trim();
        collapse();
        if (!edited.equals(value.get())) {
            value.set(edited);
            onCommit.run();
        }
    }

    private void cancel() {
        collapse();
    }

    private void collapse() {
        editor.setVisible(false);
        editor.setManaged(false);
        collapsed.setVisible(true);
        collapsed.setManaged(true);
        chip.requestFocus();
    }

    /** Redraws the chip from the current value: a name if there is one, otherwise a short host label. */
    private void refresh() {
        String text = UriRedactor.displayName(name.get(), value.get());
        chip.setText(text);
        boolean secret = UriRedactor.carriesCredentials(value.get());
        // The tooltip is redacted as well: a hover is not a decision to reveal a password.
        chip.setTooltip(new Tooltip(value.get().isBlank()
                ? "Double-click to enter a connection string"
                : UriRedactor.redact(value.get())
                        + (secret ? "\n(credentials hidden — double-click to edit)" : "")
                        + "\nDouble-click to edit"));
        chip.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("has-credentials"), secret);
    }

    private ContextMenu buildMenu() {
        MenuItem editItem = new MenuItem("Edit…");
        editItem.setOnAction(e -> startEditing());
        MenuItem copyRedacted = new MenuItem("Copy (credentials hidden)");
        copyRedacted.setOnAction(e -> copy(UriRedactor.redact(value.get())));
        MenuItem copyFull = new MenuItem("Copy including credentials");
        copyFull.setOnAction(e -> copy(value.get()));
        return new ContextMenu(editItem, copyRedacted, copyFull);
    }

    private static void copy(String text) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text == null ? "" : text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }
}
