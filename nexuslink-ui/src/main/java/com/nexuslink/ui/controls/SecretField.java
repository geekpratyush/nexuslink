package com.nexuslink.ui.controls;

import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/**
 * A field for a secret that is masked by default and can be revealed deliberately.
 *
 * <p>A plain {@link TextField} leaves a token or an API key on screen for the whole session; a plain
 * {@link PasswordField} hides it so completely that a pasted value cannot be checked, which is why
 * people reach for the text field in the first place. This is both: a masked field with an eye
 * toggle, sharing one value, so the default is safe and verifying is one click.
 */
public final class SecretField extends HBox {

    private final PasswordField masked = new PasswordField();
    private final TextField revealed = new TextField();
    private final ToggleButton reveal = new ToggleButton("👁");

    public SecretField() {
        this("");
    }

    public SecretField(String promptText) {
        super(4);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("secret-field");

        masked.getStyleClass().add("nl-field");
        revealed.getStyleClass().add("nl-field");
        masked.setPromptText(promptText);
        revealed.setPromptText(promptText);

        // One value, two views: binding both ways keeps them in step whichever is on screen.
        revealed.textProperty().bindBidirectional(masked.textProperty());
        revealed.setVisible(false);
        revealed.setManaged(false);

        reveal.getStyleClass().add("btn-secondary");
        reveal.setTooltip(new Tooltip("Show the value"));
        reveal.setFocusTraversable(false);
        reveal.selectedProperty().addListener((o, was, on) -> {
            masked.setVisible(!on);
            masked.setManaged(!on);
            revealed.setVisible(on);
            revealed.setManaged(on);
            reveal.setTooltip(new Tooltip(on ? "Hide the value" : "Show the value"));
            (on ? revealed : masked).requestFocus();
        });

        StackPane fields = new StackPane(masked, revealed);
        HBox.setHgrow(fields, Priority.ALWAYS);
        getChildren().addAll(fields, reveal);
    }

    /** The secret itself. */
    public StringProperty textProperty() { return masked.textProperty(); }

    public String getText() { return masked.getText(); }

    public void setText(String text) { masked.setText(text == null ? "" : text); }

    public void setPromptText(String prompt) {
        masked.setPromptText(prompt);
        revealed.setPromptText(prompt);
    }

    /** {@code true} while the value is on screen in the clear. */
    public boolean isRevealed() { return reveal.isSelected(); }

    /** Hides the value again — called when a dialog closes or a profile is loaded. */
    public void hide() { reveal.setSelected(false); }

    public void setPrefColumnCount(int columns) {
        masked.setPrefColumnCount(columns);
        revealed.setPrefColumnCount(columns);
    }
}
