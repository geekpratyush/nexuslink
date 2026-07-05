package com.nexuslink.ui.connection;

import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.core.connection.ConnectionStore;
import com.nexuslink.core.connection.ProfileImportExport;
import com.nexuslink.core.connection.SampleCatalog;
import com.nexuslink.ui.icons.Icons;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Left-sidebar connection manager. Shows two groups — the user's <b>Saved</b> connections (from
 * {@link ConnectionStore}) and bundled <b>Samples (public)</b> (from {@link SampleCatalog}) — each
 * leaf rendered with its protocol icon. Double-click (or the context menu) opens a profile;
 * saved entries can be deleted and samples hidden, so corporate users can clear the samples.
 */
public final class ConnectionsPanel extends VBox {

    private final ConnectionStore store;
    private final TreeView<Object> tree = new TreeView<>();
    private Consumer<ConnectionProfile> onOpen = p -> {};

    public ConnectionsPanel(ConnectionStore store) {
        this.store = store;
        tree.getStyleClass().add("connection-tree");
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new ProfileCell());
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ConnectionProfile p = selectedProfile();
                if (p != null) onOpen.accept(p);
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().addAll(buildToolbar(), tree);
        refresh();
    }

    /** A small Export/Import bar for sharing the Saved connections as an encrypted bundle. */
    private HBox buildToolbar() {
        Button export = new Button("Export…");
        export.getStyleClass().add("btn-secondary");
        export.setTooltip(new Tooltip("Export saved connections to a passphrase-encrypted bundle"));
        export.setOnAction(e -> exportConnections());
        Button importBtn = new Button("Import…");
        importBtn.getStyleClass().add("btn-secondary");
        importBtn.setTooltip(new Tooltip("Import connections from an encrypted bundle"));
        importBtn.setOnAction(e -> importConnections());
        HBox bar = new HBox(6, export, importBtn);
        bar.setPadding(new Insets(4, 4, 6, 4));
        return bar;
    }

    private void exportConnections() {
        List<ConnectionProfile> profiles = store.saved();
        if (profiles.isEmpty()) { info("Nothing to export", "There are no saved connections yet."); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export connections");
        fc.setInitialFileName("nexuslink-connections.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encrypted bundle (*.json)", "*.json"));
        java.io.File file = fc.showSaveDialog(window());
        if (file == null) return;
        Optional<char[]> pass = passphrasePrompt("Export connections", "Set a passphrase to encrypt " + profiles.size() + " connection(s):");
        if (pass.isEmpty()) return;
        try {
            String bundle = new ProfileImportExport().export(profiles, pass.get());
            Files.writeString(file.toPath(), bundle);
            info("Export complete", "Encrypted " + profiles.size() + " connection(s) to\n" + file.getName());
        } catch (Exception ex) {
            error("Export failed", ex.getMessage());
        }
    }

    private void importConnections() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import connections");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encrypted bundle (*.json)", "*.json"));
        java.io.File file = fc.showOpenDialog(window());
        if (file == null) return;
        Optional<char[]> pass = passphrasePrompt("Import connections", "Enter the bundle's passphrase:");
        if (pass.isEmpty()) return;
        try {
            String bundle = Files.readString(Path.of(file.getPath()));
            List<ConnectionProfile> imported = new ProfileImportExport().importBundle(bundle, pass.get());
            for (ConnectionProfile p : imported) { p.sample = false; store.save(p); }
            refresh();
            info("Import complete", "Imported " + imported.size() + " connection(s).");
        } catch (Exception ex) {
            error("Import failed", ex.getMessage());
        }
    }

    /** A passphrase prompt backed by a masked field; returns the entered characters, or empty if cancelled. */
    private Optional<char[]> passphrasePrompt(String title, String header) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        if (window() != null) dialog.initOwner(window());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        PasswordField pf = new PasswordField();
        pf.setPromptText("passphrase");
        VBox box = new VBox(pf);
        box.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
        javafx.application.Platform.runLater(pf::requestFocus);
        dialog.setResultConverter(bt -> bt == ButtonType.OK && !pf.getText().isEmpty() ? pf.getText().toCharArray() : null);
        return dialog.showAndWait();
    }

    private javafx.stage.Window window() { return getScene() == null ? null : getScene().getWindow(); }

    private void info(String header, String content) { alert(Alert.AlertType.INFORMATION, header, content); }
    private void error(String header, String content) { alert(Alert.AlertType.ERROR, header, content); }

    private void alert(Alert.AlertType type, String header, String content) {
        Alert a = new Alert(type, content, ButtonType.OK);
        a.setHeaderText(header);
        if (window() != null) a.initOwner(window());
        a.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
        a.showAndWait();
    }

    public void setOnOpen(Consumer<ConnectionProfile> onOpen) {
        this.onOpen = onOpen == null ? p -> {} : onOpen;
    }

    /** Persists a profile under "Saved" and refreshes. */
    public void saveProfile(ConnectionProfile profile) {
        store.save(profile);
        refresh();
    }

    /** Rebuilds the tree from the store + sample catalog (hidden samples excluded). */
    public void refresh() {
        TreeItem<Object> root = new TreeItem<>("root");

        TreeItem<Object> saved = group("Saved", true);
        for (ConnectionProfile p : store.saved()) saved.getChildren().add(new TreeItem<>(p));
        if (saved.getChildren().isEmpty()) {
            saved.getChildren().add(new TreeItem<>(new Hint("No saved connections yet")));
        }

        // Samples ship collapsed so the (long) public catalogue doesn't flood the sidebar on open;
        // the user expands it on demand. Saved connections stay expanded — that's their workspace.
        TreeItem<Object> samples = group("Samples (public)", false);
        int sampleCount = 0;
        for (ConnectionProfile p : SampleCatalog.all()) {
            if (!store.isSampleHidden(p.id)) { samples.getChildren().add(new TreeItem<>(p)); sampleCount++; }
        }
        samples.setValue(new Group("Samples (public)  (" + sampleCount + ")"));

        root.getChildren().addAll(saved, samples);
        tree.setRoot(root);
    }

    private TreeItem<Object> group(String name, boolean expanded) {
        TreeItem<Object> g = new TreeItem<>(new Group(name));
        g.setExpanded(expanded);
        return g;
    }

    private ConnectionProfile selectedProfile() {
        TreeItem<Object> sel = tree.getSelectionModel().getSelectedItem();
        return (sel != null && sel.getValue() instanceof ConnectionProfile p) ? p : null;
    }

    // Marker types so the cell can distinguish group headers / hints from profiles.
    private record Group(String name) {}
    private record Hint(String text) {}

    private final class ProfileCell extends TreeCell<Object> {
        @Override protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            if (empty || item == null) { setText(null); setGraphic(null); getStyleClass().remove("sidebar-title"); return; }

            getStyleClass().remove("sidebar-title");
            if (item instanceof Group g) {
                setText(g.name().toUpperCase());
                setGraphic(null);
                if (!getStyleClass().contains("sidebar-title")) getStyleClass().add("sidebar-title");
                if (g.name().startsWith("Samples")) setContextMenu(samplesGroupMenu());
            } else if (item instanceof Hint h) {
                setText(h.text());
                setGraphic(null);
            } else if (item instanceof ConnectionProfile p) {
                setText(p.name + "   " + p.protocol);
                setGraphic(Icons.of(p.iconHint(), 15));
                setContextMenu(profileMenu(p));
            }
        }

        private ContextMenu profileMenu(ConnectionProfile p) {
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> onOpen.accept(p));
            ContextMenu menu = new ContextMenu(open);
            if (p.sample) {
                MenuItem hide = new MenuItem("Hide this sample");
                hide.setOnAction(e -> { store.hideSample(p.id); refresh(); });
                menu.getItems().add(hide);
            } else {
                MenuItem delete = new MenuItem("Delete");
                delete.setOnAction(e -> { store.delete(p.id); refresh(); });
                menu.getItems().add(delete);
            }
            return menu;
        }

        private ContextMenu samplesGroupMenu() {
            MenuItem reset = new MenuItem("Restore hidden samples");
            reset.setOnAction(e -> { store.resetSamples(); refresh(); });
            return new ContextMenu(reset);
        }
    }
}
