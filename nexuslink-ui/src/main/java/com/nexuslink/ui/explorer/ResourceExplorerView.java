package com.nexuslink.ui.explorer;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.ui.icons.Icons;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reusable object browser: a lazy {@link TreeView} of {@link ResourceNode}s (each rendered with
 * its bespoke icon) above a property/details table. Protocols supply a {@link ResourceExplorer};
 * the view loads roots on {@link #load()} and fetches each level's children on demand as the user
 * expands nodes — all off the UI thread.
 */
public final class ResourceExplorerView extends BorderPane {

    private final TreeView<ResourceNode> tree = new TreeView<>();
    private final TableView<Map.Entry<String, String>> details = new TableView<>();
    private final Label status = new Label("Not connected");
    private final Label title;

    private ResourceExplorer explorer;
    private Consumer<String> logger = s -> {};
    private Consumer<ResourceNode> onSelect = n -> {};
    private Consumer<ResourceNode> onActivate = n -> {};

    public ResourceExplorerView(String titleText) {
        getStyleClass().add("explorer-view");
        title = new Label(titleText.toUpperCase());
        title.getStyleClass().add("sidebar-title");

        tree.setShowRoot(false);
        tree.getStyleClass().add("explorer-tree");
        tree.getSelectionModel().selectedItemProperty().addListener((o, ov, item) -> {
            ResourceNode n = item == null ? null : item.getValue();
            showDetails(n);
            if (n != null) onSelect.accept(n);
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<ResourceNode> sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() != null) onActivate.accept(sel.getValue());
            }
        });

        buildDetailsTable();
        status.getStyleClass().add("meta-label");
        status.setPadding(new Insets(4, 8, 4, 8));

        VBox top = new VBox(title, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);

        Label detailsTitle = new Label("DETAILS");
        detailsTitle.getStyleClass().add("sidebar-title");
        VBox bottom = new VBox(detailsTitle, details, status);
        VBox.setVgrow(details, Priority.ALWAYS);

        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        setCenter(split);
    }

    public void setLogger(Consumer<String> l) { this.logger = l == null ? s -> {} : l; }

    public void setExplorer(ResourceExplorer explorer) { this.explorer = explorer; }

    /** Fired when a node is selected (single click / keyboard). */
    public void setOnSelect(Consumer<ResourceNode> c) { this.onSelect = c == null ? n -> {} : c; }

    /** Fired when a node is activated (double click). */
    public void setOnActivate(Consumer<ResourceNode> c) { this.onActivate = c == null ? n -> {} : c; }

    /** (Re)loads the top-level nodes from the current explorer. */
    public void load() {
        details.getItems().clear();
        if (explorer == null) { tree.setRoot(null); status.setText("Not connected"); return; }
        status.setText("Loading…");
        TreeItem<ResourceNode> hidden = new TreeItem<>(null);
        tree.setRoot(hidden);
        runBg(explorer::roots, roots -> {
            for (ResourceNode n : roots) hidden.getChildren().add(new LazyItem(n));
            status.setText(roots.size() + " item(s)");
            if (!hidden.getChildren().isEmpty()) hidden.getChildren().get(0).setExpanded(true);
        });
    }

    public void clear() {
        tree.setRoot(null);
        details.getItems().clear();
        status.setText("Not connected");
    }

    private void showDetails(ResourceNode node) {
        details.getItems().clear();
        if (node == null) return;
        details.getItems().addAll(node.details().entrySet());
    }

    @SuppressWarnings("unchecked")
    private void buildDetailsTable() {
        details.getStyleClass().add("details-table");
        details.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        details.setPlaceholder(new Label("Select an object to see its details"));

        TableColumn<Map.Entry<String, String>, String> k = new TableColumn<>("Property");
        k.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getKey()));
        k.setMaxWidth(140);
        k.setMinWidth(110);

        TableColumn<Map.Entry<String, String>, String> v = new TableColumn<>("Value");
        v.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getValue()));
        v.setCellFactory(col -> new TableCell<>() {
            private final Label label = new Label();
            { label.setWrapText(true); setGraphic(label); setPrefHeight(Control.USE_COMPUTED_SIZE); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                label.setText(empty ? null : item);
            }
        });

        details.getColumns().setAll(k, v);
    }

    /** TreeItem that fetches its children the first time it is expanded. */
    private final class LazyItem extends TreeItem<ResourceNode> {
        private boolean loaded = false;

        LazyItem(ResourceNode value) {
            super(value);
            setGraphic(Icons.of(value.iconHint(), 15));
            expandedProperty().addListener((o, was, now) -> {
                if (now && !loaded) {
                    loaded = true;
                    getChildren().setAll(new TreeItem<>(loadingPlaceholder()));
                    runBg(() -> explorer.children(getValue()), kids -> {
                        getChildren().clear();
                        for (ResourceNode n : kids) getChildren().add(new LazyItem(n));
                    });
                }
            });
        }

        @Override public boolean isLeaf() {
            ResourceNode v = getValue();
            return v != null && !v.hasChildren();
        }
    }

    private static ResourceNode loadingPlaceholder() {
        return ResourceNode.leaf("…", "loading…", ResourceNode.Kind.GENERIC);
    }

    // ---- background helper ----
    private <T> void runBg(ThrowingSupplier<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() {
            @Override protected T call() throws Exception { return work.get(); }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = ex == null ? "error" : ex.getMessage();
            Platform.runLater(() -> status.setText("✖ " + msg));
            logger.accept("Explorer error: " + msg);
        });
        Thread t = new Thread(task, "explorer-load");
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
