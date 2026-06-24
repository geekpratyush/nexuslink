package com.nexuslink.ui.connection;

import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.core.connection.ConnectionStore;
import com.nexuslink.core.connection.SampleCatalog;
import com.nexuslink.ui.icons.Icons;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
        getChildren().add(tree);
        refresh();
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

        TreeItem<Object> saved = group("Saved");
        for (ConnectionProfile p : store.saved()) saved.getChildren().add(new TreeItem<>(p));
        if (saved.getChildren().isEmpty()) {
            saved.getChildren().add(new TreeItem<>(new Hint("No saved connections yet")));
        }

        TreeItem<Object> samples = group("Samples (public)");
        for (ConnectionProfile p : SampleCatalog.all()) {
            if (!store.isSampleHidden(p.id)) samples.getChildren().add(new TreeItem<>(p));
        }

        root.getChildren().addAll(saved, samples);
        tree.setRoot(root);
    }

    private TreeItem<Object> group(String name) {
        TreeItem<Object> g = new TreeItem<>(new Group(name));
        g.setExpanded(true);
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
