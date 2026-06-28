package com.nexuslink.ui.settings;

import com.nexuslink.core.config.SettingsService;
import com.nexuslink.ui.theme.ThemeManager;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * The application Preferences dialog. Reads and writes user settings through
 * {@link SettingsService}; the theme control delegates to {@link ThemeManager}
 * (the live themer) so a change applies immediately to every open scene.
 */
public final class PreferencesDialog {

    private PreferencesDialog() {}

    /** Opens the modal Preferences dialog, applying changes on OK. */
    public static void open(Window owner) {
        SettingsService settings = Settings.service();

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Preferences");
        dialog.setHeaderText("Application settings. Defaults apply to newly opened tabs.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<ThemeManager.Theme> theme = new ComboBox<>();
        theme.getItems().addAll(ThemeManager.Theme.values());
        theme.setValue(ThemeManager.get().current());

        TextField connectTimeout = new TextField(String.valueOf(settings.getConnectTimeoutMs()));
        connectTimeout.getStyleClass().add("nl-field");
        TextField readTimeout = new TextField(String.valueOf(settings.getReadTimeoutMs()));
        readTimeout.getStyleClass().add("nl-field");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(14, 6, 6, 6));
        grid.addRow(0, meta("Theme"), theme);
        grid.addRow(1, meta("Default connect timeout (ms)"), connectTimeout);
        grid.addRow(2, meta("Default read timeout (ms)"), readTimeout);
        Label hint = new Label("Timeouts seed new REST tabs; the theme applies immediately.");
        hint.getStyleClass().add("meta-label");
        grid.add(hint, 0, 3, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.setOnShown(ev -> {
            if (dialog.getDialogPane().getScene() != null) {
                ThemeManager.get().register(dialog.getDialogPane().getScene());
            }
        });

        // Apply the theme live as it is picked, so OK/Cancel both leave a sensible state…
        ThemeManager.Theme original = ThemeManager.get().current();
        theme.valueProperty().addListener((o, ov, nv) -> { if (nv != null) ThemeManager.get().set(nv); });

        if (dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
            settings.setConnectTimeoutMs(parsePositive(connectTimeout.getText(), settings.getConnectTimeoutMs()));
            settings.setReadTimeoutMs(parsePositive(readTimeout.getText(), settings.getReadTimeoutMs()));
            settings.setTheme(theme.getValue().name().toLowerCase());
        } else {
            // …and revert a previewed theme change on Cancel.
            ThemeManager.get().set(original);
        }
    }

    private static Label meta(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private static int parsePositive(String text, int fallback) {
        try {
            int v = Integer.parseInt(text.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
