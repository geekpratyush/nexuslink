package com.nexuslink.ui.files;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * One pane of the {@link DualPaneBrowser}: an address bar, a navigation toolbar (Up / Refresh /
 * New Folder) and a details table (name · size · modified · permissions) over a {@link FileSystem}.
 * Double-clicking a directory navigates into it; double-clicking a file fires {@link #setOnActivateFile}.
 * All blocking I/O runs on a background thread so the UI never freezes — the WinSCP/MobaXterm feel.
 */
public final class FileBrowserPane extends VBox {

    // A drag between panes happens inside one JVM, so the payload is stashed statically and the
    // dragboard only carries a marker. The source reference lets a target reject same-pane drops.
    private static final DataFormat FILE_DRAG = new DataFormat("application/x-nexuslink-files");
    private static List<FileItem> draggedItems;
    private static FileBrowserPane dragSource;

    private final FileSystem fs;
    private final TextField addressField = new TextField();
    private final TableView<FileItem> table = new TableView<>();
    private final Label statusLabel = new Label();
    private String currentPath = "/";

    private Consumer<String> logger = s -> {};
    private Consumer<FileItem> onActivateFile = f -> {};
    private Runnable onChanged = () -> {};
    private Consumer<List<FileItem>> onDropFromOther = items -> {};

    public FileBrowserPane(FileSystem fs, String title) {
        this.fs = fs;
        getStyleClass().add("file-pane");
        setSpacing(6);
        setPadding(new Insets(6));
        getChildren().addAll(buildHeader(title), buildAddressBar(), buildTable(), statusLabel);
        VBox.setVgrow(table, Priority.ALWAYS);
        statusLabel.getStyleClass().add("meta-label");
    }

    public void setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> {} : logger; }

    /** Notified when a file (not a directory) is opened — used to trigger a transfer. */
    public void setOnActivateFile(Consumer<FileItem> handler) {
        this.onActivateFile = handler == null ? f -> {} : handler;
    }

    /** Notified after a successful navigation/refresh (so the container can refresh the other pane). */
    public void setOnChanged(Runnable handler) { this.onChanged = handler == null ? () -> {} : handler; }

    /** Notified when files dragged from the <em>other</em> pane are dropped onto this one (transfer). */
    public void setOnDropFromOther(Consumer<List<FileItem>> handler) {
        this.onDropFromOther = handler == null ? items -> {} : handler;
    }

    public String currentPath() { return currentPath; }

    public List<FileItem> selected() { return List.copyOf(table.getSelectionModel().getSelectedItems()); }

    private Label buildHeader(String title) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        return l;
    }

    private HBox buildAddressBar() {
        Button up = new Button("↑ Up");
        up.getStyleClass().add("btn-secondary");
        up.setOnAction(e -> navigateTo(fs.parent(currentPath)));

        Button refresh = new Button("⟳");
        refresh.getStyleClass().add("btn-secondary");
        refresh.setTooltip(new Tooltip("Refresh"));
        refresh.setOnAction(e -> refresh());

        Button mkdir = new Button("New Folder");
        mkdir.getStyleClass().add("btn-secondary");
        mkdir.setOnAction(e -> newFolder());

        addressField.getStyleClass().add("nl-field");
        addressField.setOnAction(e -> navigateTo(addressField.getText().trim()));
        HBox.setHgrow(addressField, Priority.ALWAYS);

        HBox bar = new HBox(6, up, refresh, addressField, mkdir);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    @SuppressWarnings("unchecked")
    private TableView<FileItem> buildTable() {
        TableColumn<FileItem, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().directory() ? "📁 " : "📄 ") + c.getValue().name()));
        name.setPrefWidth(260);

        TableColumn<FileItem, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sizeText()));
        size.setStyle("-fx-alignment: CENTER-RIGHT;");
        size.setPrefWidth(90);

        TableColumn<FileItem, String> modified = new TableColumn<>("Modified");
        modified.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().modified()));
        modified.setPrefWidth(140);

        TableColumn<FileItem, String> perms = new TableColumn<>("Permissions");
        perms.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().permissions()));
        perms.setPrefWidth(110);

        table.getColumns().setAll(name, size, modified, perms);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Empty"));
        table.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) activate(row.getItem());
            });
            // Begin a drag from the current multi-selection (Ctrl/Shift selection is preserved).
            row.setOnDragDetected(e -> {
                if (row.isEmpty()) return;
                if (!table.getSelectionModel().getSelectedItems().contains(row.getItem())) {
                    table.getSelectionModel().clearAndSelect(row.getIndex());
                }
                List<FileItem> sel = selected().stream()
                        .filter(f -> !f.parent() && !f.directory()).toList();
                if (sel.isEmpty()) return;
                Dragboard db = row.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.put(FILE_DRAG, sel.size());
                content.putString(sel.size() + " file(s)");
                db.setContent(content);
                draggedItems = sel;
                dragSource = FileBrowserPane.this;
                e.consume();
            });
            return row;
        });
        wireDropTarget();
        table.setOnKeyPressed(e -> {
            FileItem sel = table.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && sel != null) activate(sel);
            else if (e.getCode() == KeyCode.DELETE && sel != null) deleteSelected();
            else if (e.getCode() == KeyCode.F2 && sel != null) renameSelected();
        });
        table.setContextMenu(buildContextMenu());
        return table;
    }

    private ContextMenu buildContextMenu() {
        MenuItem open = new MenuItem("Open / Transfer");
        open.setOnAction(e -> { FileItem s = table.getSelectionModel().getSelectedItem(); if (s != null) activate(s); });
        MenuItem rename = new MenuItem("Rename…  (F2)");
        rename.setOnAction(e -> renameSelected());
        MenuItem delete = new MenuItem("Delete  (Del)");
        delete.setOnAction(e -> deleteSelected());
        MenuItem mkdir = new MenuItem("New Folder…");
        mkdir.setOnAction(e -> newFolder());
        MenuItem refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> refresh());
        ContextMenu menu = new ContextMenu(open, new SeparatorMenuItem(), rename, delete, mkdir, new SeparatorMenuItem(), refresh);
        if (fs.supportsChmod()) {
            MenuItem chmod = new MenuItem("Permissions (chmod)…");
            chmod.setOnAction(e -> chmodSelected());
            menu.getItems().add(chmod);
        }
        return menu;
    }

    /** Accepts drops of files dragged from the other pane; highlights while hovering. */
    private void wireDropTarget() {
        table.setOnDragOver(e -> {
            if (dragSource != null && dragSource != this && e.getDragboard().hasContent(FILE_DRAG)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        table.setOnDragEntered(e -> {
            if (dragSource != null && dragSource != this && e.getDragboard().hasContent(FILE_DRAG)) {
                if (!table.getStyleClass().contains("drop-target")) table.getStyleClass().add("drop-target");
            }
            e.consume();
        });
        table.setOnDragExited(e -> { table.getStyleClass().remove("drop-target"); e.consume(); });
        table.setOnDragDropped(e -> {
            boolean ok = false;
            if (dragSource != null && dragSource != this && draggedItems != null) {
                onDropFromOther.accept(List.copyOf(draggedItems));
                ok = true;
            }
            table.getStyleClass().remove("drop-target");
            e.setDropCompleted(ok);
            e.consume();
        });
        table.setOnDragDone(e -> { draggedItems = null; dragSource = null; e.consume(); });
    }

    private void activate(FileItem item) {
        if (item.directory()) navigateTo(item.path());
        else onActivateFile.accept(item);
    }

    /** Loads the file system's home directory. Call once after connecting. */
    public void loadHome() {
        runBg("home", () -> fs.home(), result -> navigateTo((String) result));
    }

    public void navigateTo(String path) {
        runBg("list " + path, () -> {
            List<FileItem> items = fs.list(path);
            return new Object[]{path, items};
        }, result -> {
            Object[] r = (Object[]) result;
            currentPath = (String) r[0];
            addressField.setText(currentPath);
            @SuppressWarnings("unchecked")
            List<FileItem> items = (List<FileItem>) r[1];
            var rows = FXCollections.<FileItem>observableArrayList();
            rows.add(FileItem.up(fs.parent(currentPath)));
            rows.addAll(items);
            table.setItems(rows);
            statusLabel.setText(items.size() + " item(s)");
            onChanged.run();
        });
    }

    public void refresh() { navigateTo(currentPath); }

    /**
     * Puts the pane into a "not connected" state: clears the current listing and shows {@code message}
     * as both the table placeholder and the status line. Used by the {@link DualPaneBrowser} so the
     * remote pane reads "not connected" before a server connection rather than appearing empty.
     */
    public void showDisconnected(String message) {
        currentPath = "/";
        addressField.clear();
        table.setItems(FXCollections.observableArrayList());
        table.setPlaceholder(new Label(message));
        statusLabel.setText(message);
    }

    private void newFolder() {
        TextInputDialog d = themedInput("new-folder", "New Folder", "Folder name:");
        d.showAndWait().ifPresent(nameRaw -> {
            String nm = nameRaw.trim();
            if (nm.isEmpty()) return;
            runBg("mkdir " + nm, () -> { fs.mkdir(fs.join(currentPath, nm)); return null; }, r -> refresh());
        });
    }

    private void renameSelected() {
        FileItem sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.parent()) return;
        TextInputDialog d = themedInput(sel.name(), "Rename", "New name:");
        d.showAndWait().ifPresent(nameRaw -> {
            String nm = nameRaw.trim();
            if (nm.isEmpty() || nm.equals(sel.name())) return;
            runBg("rename " + sel.name(), () -> { fs.rename(sel.path(), fs.join(currentPath, nm)); return null; }, r -> refresh());
        });
    }

    private void deleteSelected() {
        List<FileItem> sel = selected();
        sel.removeIf(FileItem::parent);
        if (sel.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + sel.size() + " item(s)? This cannot be undone.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        register(confirm.getDialogPane());
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b ->
                runBg("delete", () -> { for (FileItem f : sel) fs.delete(f); return null; }, r -> refresh()));
    }

    private void chmodSelected() {
        FileItem sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.parent()) return;
        TextInputDialog d = themedInput("0644", "Permissions", "Octal mode for " + sel.name() + " (e.g. 0644):");
        d.showAndWait().ifPresent(octal -> {
            try {
                int bits = Integer.parseInt(octal.trim(), 8);
                runBg("chmod " + octal, () -> { fs.chmod(sel, bits); return null; }, r -> refresh());
            } catch (NumberFormatException ex) {
                statusLabel.setText("Invalid octal mode: " + octal);
            }
        });
    }

    private TextInputDialog themedInput(String initial, String title, String prompt) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(prompt);
        if (getScene() != null) d.initOwner(getScene().getWindow());
        register(d.getDialogPane());
        return d;
    }

    private void register(DialogPane pane) {
        pane.sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
    }

    // ---- background-task plumbing ----

    private interface IoCall { Object run() throws Exception; }

    private void runBg(String label, IoCall call, Consumer<Object> onOk) {
        statusLabel.setText(label + "…");
        Task<Object> task = new Task<>() {
            @Override protected Object call() throws Exception { return call.run(); }
        };
        task.setOnSucceeded(e -> { onOk.accept(task.getValue()); logger.accept(fs.name() + ": " + label); });
        task.setOnFailed(e -> {
            statusLabel.setText("✖ " + label + ": " + task.getException().getMessage());
            logger.accept(fs.name() + " FAILED " + label + ": " + task.getException().getMessage());
        });
        Thread t = new Thread(task, "file-pane");
        t.setDaemon(true);
        t.start();
    }

    /** Refresh helper safe to call from any thread. */
    public void refreshLater() { Platform.runLater(this::refresh); }
}
