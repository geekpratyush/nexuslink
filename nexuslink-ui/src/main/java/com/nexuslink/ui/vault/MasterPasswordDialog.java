package com.nexuslink.ui.vault;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Modal prompts for the credential vault's master password — one to create a new vault
 * (with confirmation + a simple strength hint) and one to unlock an existing vault (with an
 * optional error message for retries). The dialog returns the entered password; verification
 * against the vault is the caller's job ({@link VaultSession}).
 */
public final class MasterPasswordDialog {

    private MasterPasswordDialog() {}

    /** Prompt to set a new master password. Empty if the user cancels. */
    public static Optional<char[]> create(Window owner) {
        Dialog<ButtonType> dialog = baseDialog(owner, "Create vault master password",
                "Secrets you save are encrypted with this password (AES-256-GCM). "
                        + "There is no recovery if you forget it.");

        PasswordField pw = new PasswordField();
        pw.setPromptText("Master password");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Confirm password");
        Label strength = new Label("");
        strength.getStyleClass().add("meta-label");

        GridPane grid = grid();
        grid.addRow(0, new Label("Password:"), pw);
        grid.addRow(1, new Label("Confirm:"), confirm);
        grid.add(strength, 1, 2);
        dialog.getDialogPane().setContent(grid);

        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setDisable(true);
        Runnable validate = () -> {
            strength.setText(strengthHint(pw.getText()));
            boolean match = !pw.getText().isEmpty() && pw.getText().equals(confirm.getText());
            ok.setDisable(!match);
        };
        pw.textProperty().addListener((o, a, b) -> validate.run());
        confirm.textProperty().addListener((o, a, b) -> validate.run());
        javafx.application.Platform.runLater(pw::requestFocus);

        return dialog.showAndWait()
                .filter(b -> b == ButtonType.OK)
                .map(b -> pw.getText().toCharArray());
    }

    /** Prompt to unlock an existing vault. {@code error} (nullable) is shown for retries. */
    public static Optional<char[]> unlock(Window owner, String error) {
        Dialog<ButtonType> dialog = baseDialog(owner, "Unlock credential vault",
                error != null ? error : "Enter your master password to access saved secrets.");
        if (error != null) dialog.getDialogPane().getStyleClass().add("vault-error");

        PasswordField pw = new PasswordField();
        pw.setPromptText("Master password");
        GridPane grid = grid();
        grid.addRow(0, new Label("Password:"), pw);
        dialog.getDialogPane().setContent(grid);

        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setDisable(true);
        pw.textProperty().addListener((o, a, b) -> ok.setDisable(b.isEmpty()));
        javafx.application.Platform.runLater(pw::requestFocus);

        return dialog.showAndWait()
                .filter(b -> b == ButtonType.OK)
                .map(b -> pw.getText().toCharArray());
    }

    private static Dialog<ButtonType> baseDialog(Window owner, String title, String header) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return dialog;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12, 4, 4, 4));
        return grid;
    }

    private static String strengthHint(String pw) {
        if (pw.isEmpty()) return "";
        int score = 0;
        if (pw.length() >= 8) score++;
        if (pw.length() >= 12) score++;
        if (pw.matches(".*[A-Z].*") && pw.matches(".*[a-z].*")) score++;
        if (pw.matches(".*\\d.*")) score++;
        if (pw.matches(".*[^A-Za-z0-9].*")) score++;
        return "Strength: " + (score <= 2 ? "weak" : score == 3 ? "fair" : score == 4 ? "good" : "strong");
    }
}
