package com.nexuslink.ui.sql;

import com.nexuslink.protocol.db.DriverInfo;
import com.nexuslink.protocol.db.ExternalDriverLoader;
import com.nexuslink.protocol.db.JarDriverInspector;
import com.nexuslink.protocol.db.JdbcDriverRegistry;
import com.nexuslink.protocol.db.MavenCommandHelp;
import com.nexuslink.protocol.db.MavenCommandHelp.Shell;
import com.nexuslink.protocol.db.UserDriver;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The driver manager: one list of every JDBC driver NexusLink knows about, showing at a glance
 * which are ready to use, plus the three ways to make one ready — download it, fetch it with the
 * user's own Maven, or attach a jar from disk.
 *
 * <p>The middle option is what makes this work inside a locked-down organisation. Rather than
 * trying to reproduce the company's Artifactory credentials, mirror and proxy configuration inside
 * the app, we hand the user the exact {@code mvn} command for <em>their</em> shell and let Maven —
 * already configured on their machine — do the fetching. They then attach the resulting jar here,
 * and it is registered immediately, with no restart.
 */
final class DriverManagerDialog {

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ListView<DriverInfo> list = new ListView<>();
    private final Label statusLabel = new Label();

    // Detail pane, rebuilt whenever the selection changes.
    private final VBox detail = new VBox(10);
    private final ComboBox<Shell> shellCombo = new ComboBox<>(FXCollections.observableArrayList(Shell.values()));
    private final TextArea commandArea = new TextArea();
    private final TextArea helpArea = new TextArea();

    private DriverManagerDialog(Window owner) {
        dialog.initOwner(owner);
        dialog.setTitle("JDBC Drivers");
        dialog.setResizable(true);

        list.setCellFactory(lv -> driverCell());
        list.setPrefWidth(280);
        list.getSelectionModel().selectedItemProperty().addListener((o, old, d) -> showDetail(d));

        shellCombo.setValue(MavenCommandHelp.detect());
        shellCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Shell s) { return s == null ? "" : s.label(); }
            @Override public Shell fromString(String s) { return null; }
        });
        shellCombo.valueProperty().addListener((o, old, s) -> showDetail(list.getSelectionModel().getSelectedItem()));

        commandArea.setEditable(false);
        commandArea.getStyleClass().add("nl-field");
        commandArea.setPrefRowCount(2);
        commandArea.setWrapText(true);

        helpArea.setEditable(false);
        helpArea.setWrapText(true);
        helpArea.getStyleClass().add("meta-label");
        helpArea.setPrefRowCount(12);
        VBox.setVgrow(helpArea, Priority.ALWAYS);

        statusLabel.getStyleClass().add("meta-label");
        statusLabel.setWrapText(true);

        detail.setPadding(new Insets(0, 0, 0, 12));
        detail.setPrefWidth(520);

        Button addBtn = new Button("Add from JAR…");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setTooltip(new Tooltip("Register a driver from a jar already on this machine"));
        addBtn.setOnAction(e -> addFromJar());

        Button removeBtn = new Button("Remove");
        removeBtn.getStyleClass().add("btn-secondary");
        removeBtn.setTooltip(new Tooltip("Remove a driver you added (built-in drivers can't be removed)"));
        removeBtn.setOnAction(e -> removeSelected());

        HBox buttons = new HBox(8, addBtn, removeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox left = new VBox(8, new Label("Drivers"), list, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);

        HBox body = new HBox(left, detail);
        body.setPadding(new Insets(14));
        HBox.setHgrow(detail, Priority.ALWAYS);

        VBox root = new VBox(6, body, statusLabel);
        root.setPadding(new Insets(0, 14, 10, 14));
        root.setPrefHeight(560);

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        refresh(null);
    }

    /** Opens the driver manager. Returns the id of the driver left selected, for the caller to reselect. */
    static Optional<String> open(Window owner) {
        DriverManagerDialog d = new DriverManagerDialog(owner);
        d.dialog.showAndWait();
        DriverInfo selected = d.list.getSelectionModel().getSelectedItem();
        return Optional.ofNullable(selected).map(DriverInfo::id);
    }

    // ---- list ---------------------------------------------------------------

    /** Reloads the list, keeping (or setting) the selection on {@code selectId}. */
    private void refresh(String selectId) {
        List<DriverInfo> drivers = JdbcDriverRegistry.allIncludingUser();
        // Loading a cached jar here is what makes a driver fetched via mvn show as ready without
        // the user having to attach it a second time on the next app start.
        drivers.forEach(ExternalDriverLoader::ensureLoaded);
        list.setItems(FXCollections.observableArrayList(drivers));

        String target = selectId != null ? selectId
                : Optional.ofNullable(list.getSelectionModel().getSelectedItem()).map(DriverInfo::id).orElse(null);
        drivers.stream().filter(d -> d.id().equals(target)).findFirst()
                .ifPresentOrElse(list.getSelectionModel()::select,
                        () -> list.getSelectionModel().selectFirst());
    }

    private ListCell<DriverInfo> driverCell() {
        return new ListCell<>() {
            @Override protected void updateItem(DriverInfo d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean ready = JdbcDriverRegistry.isAvailable(d);
                Label dot = new Label(ready ? "●" : "○");
                dot.getStyleClass().setAll(ready ? "status-2xx" : "meta-label");
                Label name = new Label(d.displayName());
                Label tag = new Label(d.bundled() ? "bundled"
                        : JdbcDriverRegistry.isUserDriver(d.id()) ? "added by you"
                        : ready ? "installed" : "not installed");
                tag.getStyleClass().add("meta-label");

                HBox row = new HBox(8, dot, name, new Region(), tag);
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(row.getChildren().get(2), Priority.ALWAYS);
                setGraphic(row);
                setText(null);
            }
        };
    }

    // ---- detail -------------------------------------------------------------

    private void showDetail(DriverInfo d) {
        detail.getChildren().clear();
        if (d == null) return;

        Label title = new Label(d.displayName());
        title.getStyleClass().add("section-title");

        boolean ready = JdbcDriverRegistry.isAvailable(d);
        Label state = new Label(ready ? "● Ready to use" : "○ Not installed");
        state.getStyleClass().setAll(ready ? "status-2xx" : "status-4xx");

        VBox facts = new VBox(4,
                fact("Driver class", d.driverClass()),
                fact("Sample URL", d.sampleUrl() == null || d.sampleUrl().isBlank() ? "—" : d.sampleUrl()));

        detail.getChildren().addAll(title, state, facts);

        if (JdbcDriverRegistry.isUserDriver(d.id())) {
            JdbcDriverRegistry.userDrivers().byId(d.id()).ifPresent(ud ->
                    detail.getChildren().add(fact("JAR", ud.jarPath)));
            helpArea.setText("""
                    You added this driver from a jar on this machine. NexusLink references the file in
                    place — it is not copied — so if the jar is moved or deleted the driver will stop
                    loading. Use Remove to take it off this list; the jar itself is left untouched.
                    """);
            detail.getChildren().add(helpArea);
            return;
        }

        if (d.bundled()) {
            helpArea.setText("""
                    This driver ships with NexusLink and is always available — there is nothing to
                    download or configure.
                    """);
            detail.getChildren().add(helpArea);
            return;
        }

        // On-demand catalog driver: show both routes to installing it.
        Button downloadBtn = new Button("Try direct download");
        downloadBtn.getStyleClass().add("btn-secondary");
        downloadBtn.setTooltip(new Tooltip("Works if this machine can reach Maven Central or your "
                + "configured internal repository"));
        downloadBtn.setOnAction(e -> download(d));

        Button copyBtn = new Button("Copy command");
        copyBtn.getStyleClass().add("btn-secondary");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(commandArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
            status("Command copied — paste it into " + shellCombo.getValue().label() + ".", false);
        });

        Label mavenHeading = new Label("Get it with your own Maven");
        mavenHeading.getStyleClass().add("section-title");

        HBox shellRow = new HBox(8, new Label("Shell:"), shellCombo, copyBtn);
        shellRow.setAlignment(Pos.CENTER_LEFT);

        Shell shell = shellCombo.getValue();
        commandArea.setText(MavenCommandHelp.script(d.mavenCoords(), shell));
        helpArea.setText(MavenCommandHelp.instructions(d.displayName(), d.mavenCoords(), shell));

        detail.getChildren().addAll(
                fact("Maven coordinates", d.mavenCoords()),
                downloadBtn,
                new Separator(),
                mavenHeading, shellRow, commandArea, helpArea);
    }

    private Node fact(String label, String value) {
        Label l = new Label(label + ":");
        l.getStyleClass().add("meta-label");
        l.setMinWidth(120);
        TextField v = new TextField(value);
        v.setEditable(false);
        v.getStyleClass().add("nl-field");
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(8, l, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ---- actions ------------------------------------------------------------

    private void download(DriverInfo d) {
        status("Downloading " + d.mavenCoords() + "…", false);
        try {
            ExternalDriverLoader.downloadAndLoad(d.mavenCoords(), d.driverClass());
            refresh(d.id());
            status(d.displayName() + " is ready to use.", false);
        } catch (RuntimeException e) {
            // Expected in a locked-down network — the mvn route below the button is the answer.
            status(e.getMessage(), true);
        }
    }

    /**
     * Attaches a jar the user picked. The driver class is read out of the jar, so in the common
     * case the user only has to confirm the name.
     */
    private void addFromJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a JDBC driver JAR");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR files", "*.jar"));
        File file = chooser.showOpenDialog(dialog.getOwner());
        if (file == null) return;

        List<String> candidates;
        try {
            candidates = JarDriverInspector.findDriverClasses(file.toPath());
        } catch (IOException e) {
            status("Couldn't read " + file.getName() + ": " + e.getMessage(), true);
            return;
        }
        if (candidates.isEmpty()) {
            status(file.getName() + " doesn't contain a JDBC driver. Pick the driver jar itself, "
                    + "not a dependency or a sources/javadoc jar.", true);
            return;
        }

        DriverInfo selected = list.getSelectionModel().getSelectedItem();
        Optional<UserDriver> entered = AddDriverDialog.prompt(
                dialog.getOwner(), file.toPath(), candidates,
                selected != null && !selected.bundled() ? selected : null);
        if (entered.isEmpty()) return;

        try {
            UserDriver added = JdbcDriverRegistry.userDrivers().add(entered.get());
            // Register immediately: the whole point is that the driver is usable without a restart.
            ExternalDriverLoader.loadFromJar(Path.of(added.jarPath), added.driverClass);
            refresh(added.id);
            status(added.displayName + " added and loaded — you can connect with it now.", false);
        } catch (RuntimeException e) {
            status("Couldn't add the driver: " + e.getMessage(), true);
        }
    }

    private void removeSelected() {
        DriverInfo d = list.getSelectionModel().getSelectedItem();
        if (d == null) return;
        if (!JdbcDriverRegistry.isUserDriver(d.id())) {
            status("“" + d.displayName() + "” is part of NexusLink's built-in catalog and can't be "
                    + "removed. Only drivers you added from a jar can.", true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove “" + d.displayName() + "” from the driver list?\n\n"
                        + "The jar file on disk is not deleted.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(dialog.getOwner());
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        JdbcDriverRegistry.userDrivers().remove(d.id());
        ExternalDriverLoader.unload(d.driverClass());
        refresh(null);
        status("Removed " + d.displayName() + ".", false);
    }

    private void status(String message, boolean error) {
        statusLabel.getStyleClass().setAll(error ? "status-err" : "meta-label");
        statusLabel.setText(message);
    }
}
