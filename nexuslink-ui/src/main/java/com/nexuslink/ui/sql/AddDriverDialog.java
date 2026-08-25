package com.nexuslink.ui.sql;

import com.nexuslink.protocol.db.DriverInfo;
import com.nexuslink.protocol.db.UserDriver;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Confirms the details of a driver being added from a jar: its display name, the implementation
 * class found inside the jar, and an optional sample connection URL.
 *
 * <p>Everything is pre-filled from what {@link com.nexuslink.protocol.db.JarDriverInspector} read
 * out of the jar, so the usual interaction is to glance at it and press OK. The class is a combo
 * box rather than a label because a few jars declare more than one driver.
 */
final class AddDriverDialog {

    private AddDriverDialog() {}

    /**
     * @param jar        the jar the user picked
     * @param candidates driver classes found inside it, best first (never empty)
     * @param context    the catalog driver selected when the user clicked Add, used to pre-fill the
     *                   name and sample URL when they are attaching a jar for a known database
     */
    static Optional<UserDriver> prompt(Window owner, Path jar, List<String> candidates, DriverInfo context) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Add JDBC Driver");
        dialog.setHeaderText("Confirm the details for " + jar.getFileName() + ".");

        TextField nameField = new TextField(context != null ? context.displayName() : suggestName(jar));
        nameField.getStyleClass().add("nl-field");
        nameField.setPrefColumnCount(30);

        ComboBox<String> classCombo = new ComboBox<>(FXCollections.observableArrayList(candidates));
        classCombo.setEditable(true); // a repackaged jar may need a class we couldn't detect
        classCombo.getSelectionModel().select(preferred(candidates, context));
        classCombo.setPrefWidth(360);

        TextField urlField = new TextField(context != null ? context.sampleUrl() : "");
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("jdbc:vendor://host:port/database   (optional)");

        TextField jarField = new TextField(jar.toString());
        jarField.setEditable(false);
        jarField.getStyleClass().add("nl-field");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        grid.addRow(0, label("Name"), nameField);
        grid.addRow(1, label("Driver class"), classCombo);
        grid.addRow(2, label("Sample URL"), urlField);
        grid.addRow(3, label("JAR"), jarField);

        Label note = new Label("The jar stays where it is — NexusLink references it by path.");
        note.getStyleClass().add("meta-label");
        note.setWrapText(true);
        grid.add(note, 1, 4);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(nameField.textProperty().isEmpty()
                .or(classCombo.getEditor().textProperty().isEmpty()));

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return Optional.empty();

        String driverClass = classCombo.getEditor().getText();
        if (driverClass == null || driverClass.isBlank()) driverClass = classCombo.getValue();
        return Optional.of(new UserDriver(null, nameField.getText().trim(), driverClass.trim(),
                jar.toString(), urlField.getText().trim()));
    }

    private static Label label(String text) {
        Label l = new Label(text + ":");
        l.getStyleClass().add("meta-label");
        return l;
    }

    /** Prefers the class the catalog expects, so attaching an approved build of a known driver just works. */
    private static String preferred(List<String> candidates, DriverInfo context) {
        if (context != null && candidates.contains(context.driverClass())) return context.driverClass();
        return candidates.get(0);
    }

    /** Turns {@code ojdbc11-23.4.0.24.05.jar} into {@code ojdbc11} as a starting point for the name. */
    private static String suggestName(Path jar) {
        String name = jar.getFileName().toString().replaceFirst("\\.jar$", "");
        return name.replaceFirst("-\\d.*$", "");
    }
}
