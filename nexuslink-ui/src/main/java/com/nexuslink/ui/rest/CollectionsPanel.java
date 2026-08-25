package com.nexuslink.ui.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuslink.protocol.http.rest.CollectionNode;
import com.nexuslink.protocol.http.rest.RestCollectionStore;
import com.nexuslink.protocol.http.rest.RestCollectionTree;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The REST client's collections sidebar — a tree of collections, folders and saved requests.
 *
 * <p>The panel owns no request state of its own: it captures the editor's serialized request when
 * saving and hands one back when opening, through the two functions passed in. That keeps the whole
 * request format inside {@link RestClientView}, so a new request field is saved without touching
 * this class.
 */
public final class CollectionsPanel extends VBox {

    /** Drag payload — the moving node's id. An in-process format keeps foreign drags out. */
    private static final DataFormat NODE_ID = new DataFormat("application/x-nexuslink-collection-node");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestCollectionStore store;
    private final Supplier<String> captureRequest;
    private final Consumer<String> openRequest;
    private final Consumer<String> logger;

    private final TreeView<CollectionNode> tree = new TreeView<>();
    private final Label status = new Label();

    public CollectionsPanel(RestCollectionStore store,
                            Supplier<String> captureRequest,
                            Consumer<String> openRequest,
                            Consumer<String> logger) {
        this.store = store;
        this.captureRequest = captureRequest;
        this.openRequest = openRequest;
        this.logger = logger == null ? s -> {} : logger;

        getStyleClass().add("rest-collections");
        setId("restCollections");
        setSpacing(6);
        setPadding(new Insets(8));

        Label title = new Label("Collections");
        title.getStyleClass().add("panel-title");

        Button newCollection = small("+", "New collection", e -> newCollection());
        Button saveHere = small("Save", "Save the current request into the selected folder",
                e -> saveCurrentRequest());
        Button importBtn = small("Import", "Import collections from a file", e -> importFile());
        Button exportBtn = small("Export", "Export all collections to a file", e -> exportFile());
        HBox bar = new HBox(4, newCollection, saveHere, importBtn, exportBtn);

        tree.setId("restCollectionsTree");
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new NodeCell());
        tree.setContextMenu(buildContextMenu());
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) openSelected();
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        status.getStyleClass().add("hint-label");
        status.setWrapText(true);

        getChildren().addAll(title, bar, tree, status);
        reload();
        if (store.lastError() != null) status.setText(store.lastError());
    }

    private Button small(String text, String tip, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-secondary");
        b.setTooltip(new javafx.scene.control.Tooltip(tip));
        b.setOnAction(action);
        return b;
    }

    // ---- tree <-> model ----

    /** Rebuilds the tree from the store, preserving which folders were expanded. */
    public void reload() {
        Set<String> expanded = new HashSet<>();
        collectExpanded(tree.getRoot(), expanded);
        String selectedId = selectedNode().map(n -> n.id).orElse(null);

        TreeItem<CollectionNode> root = new TreeItem<>(CollectionNode.folder(""));
        for (CollectionNode n : store.collections()) root.getChildren().add(toItem(n, expanded));
        root.setExpanded(true);
        tree.setRoot(root);
        if (selectedId != null) select(selectedId);
    }

    private void collectExpanded(TreeItem<CollectionNode> item, Set<String> out) {
        if (item == null) return;
        if (item.getValue() != null && item.isExpanded()) out.add(item.getValue().id);
        item.getChildren().forEach(c -> collectExpanded(c, out));
    }

    private TreeItem<CollectionNode> toItem(CollectionNode node, Set<String> expanded) {
        TreeItem<CollectionNode> item = new TreeItem<>(node);
        for (CollectionNode c : node.children) item.getChildren().add(toItem(c, expanded));
        item.setExpanded(expanded.contains(node.id));
        return item;
    }

    private void select(String id) {
        find(tree.getRoot(), id).ifPresent(item -> {
            for (TreeItem<CollectionNode> p = item.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
            tree.getSelectionModel().select(item);
        });
    }

    private Optional<TreeItem<CollectionNode>> find(TreeItem<CollectionNode> from, String id) {
        if (from == null) return Optional.empty();
        if (from.getValue() != null && id.equals(from.getValue().id)) return Optional.of(from);
        for (TreeItem<CollectionNode> c : from.getChildren()) {
            Optional<TreeItem<CollectionNode>> hit = find(c, id);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    private Optional<CollectionNode> selectedNode() {
        TreeItem<CollectionNode> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? Optional.empty() : Optional.ofNullable(item.getValue());
    }

    private void persist(String message) {
        store.save();
        if (store.lastError() != null) {
            status.setText(store.lastError());
        } else {
            status.setText(message);
            logger.accept(message);
        }
    }

    // ---- actions ----

    private ContextMenu buildContextMenu() {
        MenuItem open = new MenuItem("Open request");
        open.setOnAction(e -> openSelected());
        MenuItem saveInto = new MenuItem("Save current request here…");
        saveInto.setOnAction(e -> saveCurrentRequest());
        MenuItem updateItem = new MenuItem("Update from editor");
        updateItem.setOnAction(e -> updateSelectedFromEditor());
        MenuItem newFolder = new MenuItem("New folder…");
        newFolder.setOnAction(e -> newFolder());
        MenuItem newColl = new MenuItem("New collection…");
        newColl.setOnAction(e -> newCollection());
        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(e -> renameSelected());
        MenuItem duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(e -> duplicateSelected());
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> deleteSelected());
        return new ContextMenu(open, saveInto, updateItem, new SeparatorMenuItem(),
                newColl, newFolder, new SeparatorMenuItem(), rename, duplicate, delete);
    }

    private void openSelected() {
        selectedNode().ifPresent(n -> {
            if (n.folder || n.request == null) return;
            openRequest.accept(n.request.toString());
            status.setText("Opened " + RestCollectionTree.path(store.collections(), n.id));
        });
    }

    private void newCollection() {
        ask("New collection", "Collection name:", "New collection").ifPresent(name -> {
            CollectionNode c = CollectionNode.folder(RestCollectionTree.uniqueName(store.collections(), name));
            RestCollectionTree.add(store.collections(), null, c);
            persist("Created collection " + c.name);
            reload();
            select(c.id);
        });
    }

    private void newFolder() {
        String parentId = folderTarget();
        if (parentId == null) {
            status.setText("Create a collection first");
            return;
        }
        ask("New folder", "Folder name:", "New folder").ifPresent(name -> {
            CollectionNode parent = RestCollectionTree.find(store.collections(), parentId).orElseThrow();
            CollectionNode f = CollectionNode.folder(RestCollectionTree.uniqueName(parent.children, name));
            RestCollectionTree.add(store.collections(), parentId, f);
            persist("Created folder " + RestCollectionTree.path(store.collections(), f.id));
            reload();
            select(f.id);
        });
    }

    /** The folder a new child belongs in: the selected folder, a selected request's parent, or none. */
    private String folderTarget() {
        Optional<CollectionNode> sel = selectedNode();
        if (sel.isEmpty()) {
            return store.collections().isEmpty() ? null : store.collections().get(0).id;
        }
        CollectionNode n = sel.get();
        if (n.folder) return n.id;
        return RestCollectionTree.parentOf(store.collections(), n.id).map(p -> p.id).orElse(null);
    }

    private void saveCurrentRequest() {
        String requestJson = captureRequest.get();
        if (requestJson == null || requestJson.isBlank()) return;
        String parentId = folderTarget();
        if (parentId == null) {
            // Nothing to save into yet — mint the first collection rather than refusing.
            CollectionNode c = CollectionNode.folder("My collection");
            RestCollectionTree.add(store.collections(), null, c);
            parentId = c.id;
        }
        String defaultName = defaultRequestName(requestJson);
        String target = parentId;
        ask("Save request", "Request name:", defaultName).ifPresent(name -> {
            CollectionNode parent = RestCollectionTree.find(store.collections(), target).orElseThrow();
            CollectionNode node = CollectionNode.request(
                    RestCollectionTree.uniqueName(parent.children, name), parse(requestJson));
            RestCollectionTree.add(store.collections(), target, node);
            persist("Saved " + RestCollectionTree.path(store.collections(), node.id));
            reload();
            select(node.id);
        });
    }

    /** Overwrites the selected saved request with what the editor currently holds. */
    private void updateSelectedFromEditor() {
        Optional<CollectionNode> sel = selectedNode();
        if (sel.isEmpty() || sel.get().folder) {
            status.setText("Select a saved request to update");
            return;
        }
        String requestJson = captureRequest.get();
        if (requestJson == null || requestJson.isBlank()) return;
        sel.get().request = parse(requestJson);
        persist("Updated " + RestCollectionTree.path(store.collections(), sel.get().id));
    }

    private void renameSelected() {
        selectedNode().ifPresent(n -> ask("Rename", "New name:", n.name).ifPresent(name -> {
            RestCollectionTree.rename(store.collections(), n.id, name);
            persist("Renamed to " + n.name);
            reload();
            select(n.id);
        }));
    }

    private void duplicateSelected() {
        selectedNode().ifPresent(n -> {
            List<CollectionNode> siblings =
                    RestCollectionTree.siblingsOf(store.collections(), n.id).orElse(store.collections());
            CollectionNode clone = n.copy();
            clone.name = RestCollectionTree.uniqueName(siblings, n.name);
            siblings.add(siblings.indexOf(n) + 1, clone);
            persist("Duplicated " + n.name + " as " + clone.name);
            reload();
            select(clone.id);
        });
    }

    private void deleteSelected() {
        selectedNode().ifPresent(n -> {
            int requests = RestCollectionTree.requestsUnder(n).size();
            String detail = n.folder
                    ? "Delete \"" + n.name + "\" and the " + requests + " request(s) inside it?"
                    : "Delete \"" + n.name + "\"?";
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, detail,
                    javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
            confirm.setHeaderText(null);
            confirm.initOwner(getScene() == null ? null : getScene().getWindow());
            confirm.showAndWait()
                    .filter(b -> b == javafx.scene.control.ButtonType.OK)
                    .ifPresent(b -> {
                        RestCollectionTree.remove(store.collections(), n.id);
                        persist("Deleted " + n.name);
                        reload();
                    });
        });
    }

    // ---- import / export ----

    private void exportFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export collections");
        chooser.setInitialFileName("nexuslink-collections.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), store.toJson());
            status.setText("Exported to " + file.getName());
        } catch (IOException e) {
            status.setText("Export failed: " + e.getMessage());
        }
    }

    private void importFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import collections");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            List<CollectionNode> imported = readCollections(Files.readString(file.toPath()));
            if (imported.isEmpty()) {
                status.setText("No collections found in " + file.getName());
                return;
            }
            for (CollectionNode n : imported) {
                // Fresh ids: an import of a file exported from this same machine must not collide
                // with the collections already present.
                CollectionNode copy = n.copy();
                copy.name = RestCollectionTree.uniqueName(store.collections(), copy.name);
                store.collections().add(copy);
            }
            persist("Imported " + imported.size() + " collection(s) from " + file.getName());
            reload();
        } catch (IOException e) {
            status.setText("Import failed: " + e.getMessage());
        }
    }

    /** Accepts either an exported wrapper object or a bare array of nodes. */
    static List<CollectionNode> readCollections(String text) throws IOException {
        JsonNode root = JSON.readTree(text);
        JsonNode array = root.isArray() ? root : root.path("collections");
        List<CollectionNode> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode n : array) out.add(JSON.treeToValue(n, CollectionNode.class));
        }
        return out;
    }

    // ---- helpers ----

    private JsonNode parse(String requestJson) {
        try {
            return JSON.readTree(requestJson);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    /** "GET /v1/users" — a name that identifies the request without the user typing one. */
    static String defaultRequestName(String requestJson) {
        try {
            JsonNode n = JSON.readTree(requestJson);
            String method = n.path("method").asText("GET");
            String url = n.path("url").asText("");
            String path = url;
            int scheme = url.indexOf("://");
            if (scheme >= 0) {
                int slash = url.indexOf('/', scheme + 3);
                path = slash >= 0 ? url.substring(slash) : "/";
            }
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            return (method + " " + path).trim();
        } catch (IOException e) {
            return "New request";
        }
    }

    private Optional<String> ask(String title, String prompt, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        return dialog.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    /** Tree cell with a folder/method prefix and drag-to-reorder. */
    private final class NodeCell extends TreeCell<CollectionNode> {

        NodeCell() {
            setOnDragDetected(e -> {
                CollectionNode n = getItem();
                if (n == null) return;
                ClipboardContent content = new ClipboardContent();
                content.put(NODE_ID, n.id);
                startDragAndDrop(TransferMode.MOVE).setContent(content);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getDragboard().hasContent(NODE_ID) && getItem() != null) {
                    e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });
            setOnDragDropped(e -> {
                Object payload = e.getDragboard().getContent(NODE_ID);
                CollectionNode target = getItem();
                if (payload == null || target == null) return;
                String movingId = payload.toString();
                boolean moved;
                if (target.folder) {
                    // Dropping onto a folder puts the node inside it, at the end.
                    moved = RestCollectionTree.move(store.collections(), movingId, target.id,
                            target.children.size());
                } else {
                    // Dropping onto a request reorders next to it, inside its own parent.
                    String parentId = RestCollectionTree.parentOf(store.collections(), target.id)
                            .map(p -> p.id).orElse(null);
                    List<CollectionNode> siblings =
                            RestCollectionTree.siblingsOf(store.collections(), target.id).orElse(List.of());
                    moved = RestCollectionTree.move(store.collections(), movingId, parentId,
                            siblings.indexOf(target));
                }
                if (moved) {
                    persist("Moved " + RestCollectionTree.path(store.collections(), movingId));
                    reload();
                    select(movingId);
                }
                e.setDropCompleted(moved);
                e.consume();
            });
        }

        @Override
        protected void updateItem(CollectionNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (node.folder) {
                setText(node.name);
            } else {
                String method = node.request == null ? "" : node.request.path("method").asText("");
                setText(method.isBlank() ? node.name : method + "  " + node.name);
            }
        }
    }
}
