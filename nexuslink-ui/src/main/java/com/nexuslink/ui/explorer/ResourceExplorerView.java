package com.nexuslink.ui.explorer;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.ui.icons.Icons;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reusable object browser: a lazy {@link TreeView} of {@link ResourceNode}s (each rendered with
 * its bespoke icon) above a property/details table. Protocols supply a {@link ResourceExplorer};
 * the view loads roots on {@link #load()} and fetches each level's children on demand as the user
 * expands nodes — all off the UI thread.
 *
 * <p>Beneath the details table sits a collapsible <b>Source</b> pane showing the selected object's
 * definition — a routine's stored body, a table's DDL — whatever the protocol's
 * {@link ResourceExplorer#script} returns. It stays collapsed for objects that have none, so the
 * panel is quiet until there is something to read, and remembers whether the user opened it.
 */
public final class ResourceExplorerView extends BorderPane {

    private final TreeView<ResourceNode> tree = new TreeView<>();
    private final TableView<Map.Entry<String, String>> details = new TableView<>();
    private final Label status = new Label("Not connected");
    private final TextArea source = new TextArea();
    private final TitledPane sourcePane = new TitledPane("Source", null);
    private final Label title;

    private ResourceExplorer explorer;
    // The Source pane opens itself the first time there is something to read, then leaves the
    // expanded/collapsed choice to the user.
    private boolean sourceEverShown = false;
    private Consumer<String> logger = s -> {};
    private Consumer<ResourceNode> onSelect = n -> {};
    private Consumer<ResourceNode> onActivate = n -> {};
    private Function<ResourceNode, ContextMenu> contextMenuFactory = n -> null;

    public ResourceExplorerView(String titleText) {
        getStyleClass().add("explorer-view");
        title = new Label(titleText.toUpperCase());
        title.getStyleClass().add("sidebar-title");

        tree.setShowRoot(false);
        tree.getStyleClass().add("explorer-tree");
        // Custom cell so nodes can carry a right-click menu (built per-node by the host view).
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override protected void updateItem(ResourceNode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); setContextMenu(null); return; }
                setText(item.label());
                setGraphic(getTreeItem() == null ? null : getTreeItem().getGraphic());
                setContextMenu(contextMenuFactory.apply(item));
            }
        });
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
        VBox bottom = new VBox(detailsTitle, details, buildSourcePane(), status);
        VBox.setVgrow(details, Priority.ALWAYS);
        // An expanded Source pane shares the panel with the details table; collapsed, it shrinks
        // back to its header so the table keeps the whole height.
        sourcePane.expandedProperty().addListener((o, was, open) ->
                VBox.setVgrow(sourcePane, open ? Priority.ALWAYS : Priority.NEVER));

        SplitPane split = new SplitPane(top, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        setCenter(split);
    }

    /** The collapsible definition pane: a read-only, copyable code view with a Copy button. */
    private TitledPane buildSourcePane() {
        source.setEditable(false);
        source.getStyleClass().add("code-area");
        source.setPrefRowCount(10);
        source.setMinHeight(120);
        source.setWrapText(false);

        Button copy = new Button("Copy");
        copy.getStyleClass().add("btn-secondary");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(source.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            status.setText("Definition copied");
        });
        HBox tools = new HBox(6, copy);
        tools.setPadding(new Insets(4, 0, 0, 0));

        VBox box = new VBox(4, source, tools);
        VBox.setVgrow(source, Priority.ALWAYS);
        sourcePane.setContent(box);
        sourcePane.setExpanded(false);
        sourcePane.getStyleClass().add("source-pane");
        return sourcePane;
    }

    public void setLogger(Consumer<String> l) { this.logger = l == null ? s -> {} : l; }

    public void setExplorer(ResourceExplorer explorer) { this.explorer = explorer; }

    /** Fired when a node is selected (single click / keyboard). */
    public void setOnSelect(Consumer<ResourceNode> c) { this.onSelect = c == null ? n -> {} : c; }

    /** Fired when a node is activated (double click). */
    public void setOnActivate(Consumer<ResourceNode> c) { this.onActivate = c == null ? n -> {} : c; }

    /** Supplies a right-click menu per node (return {@code null} for no menu). */
    public void setContextMenuFactory(Function<ResourceNode, ContextMenu> f) {
        this.contextMenuFactory = f == null ? n -> null : f;
    }

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
        showSource("");
        status.setText("Not connected");
    }

    private void showDetails(ResourceNode node) {
        details.getItems().clear();
        showSource(node == null ? "" : null);
        if (node == null) return;
        // Show any static details immediately, then fetch live details (default returns the same).
        details.getItems().addAll(node.details().entrySet());
        if (explorer != null) {
            runBg(() -> explorer.details(node), live -> {
                if (live != null && !live.equals(node.details())) {
                    details.getItems().setAll(live.entrySet());
                }
            });
            runBg(() -> explorer.script(node), this::showSource);
        }
    }

    /**
     * Fills the Source pane. An object with no definition leaves the pane collapsed and disabled,
     * so it never invites a click that would show nothing; one that has a definition enables the
     * pane and opens it the first time, after which the user's own choice is respected.
     */
    private void showSource(String text) {
        if (text == null) { source.clear(); return; }   // still loading — leave the pane as it is
        boolean has = !text.isBlank();
        source.setText(has ? text : "");
        sourcePane.setDisable(!has);
        if (!has) sourcePane.setExpanded(false);
        else if (!sourceEverShown) { sourcePane.setExpanded(true); sourceEverShown = true; }
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
