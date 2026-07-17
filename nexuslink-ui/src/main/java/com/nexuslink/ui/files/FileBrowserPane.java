package com.nexuslink.ui.files;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
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
    private final TextField filterField = new TextField();
    private final ToggleButton showHidden = new ToggleButton("• Hidden");
    private final MenuButton bookmarksButton = new MenuButton("★");
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final NavHistory history = new NavHistory();
    private final PathBookmarks bookmarks;
    private final javafx.scene.layout.FlowPane breadcrumbBar = new javafx.scene.layout.FlowPane();
    private final TableView<FileItem> table = new TableView<>();
    private final Label statusLabel = new Label();
    private String currentPath = "/";
    // The full listing (including the ".." row) as last loaded; the table shows a filtered view of it.
    private List<FileItem> listing = List.of();
    // The item-count status shown when nothing is selected; a selection temporarily replaces it.
    private String baseStatus = "";
    // Free/total capacity of the current directory's volume, appended to the item-count status; null when unknown.
    private DiskSpace diskSpace;

    /** A drop of items from the other pane: the items, the destination directory, and whether it is a move. */
    @FunctionalInterface
    public interface CrossPaneDrop {
        void accept(List<FileItem> items, String destDir, boolean move);
    }

    private Consumer<String> logger = s -> {};
    private Consumer<FileItem> onActivateFile = f -> {};
    private Runnable onChanged = () -> {};
    // (items dragged from the other pane, target directory, isMove) → transfer into that directory.
    private CrossPaneDrop onDropFromOther = (items, dir, move) -> {};
    // (items dragged within THIS pane onto a folder row, that folder) → rename-move into it (same file system).
    private BiConsumer<List<FileItem>, String> onSameSideMove = (items, dir) -> {};
    // (files dragged in from the OS file manager, target directory) → upload/copy into that directory.
    private BiConsumer<List<Path>, String> onExternalDrop = (paths, dir) -> {};
    private Runnable onFocused = () -> {};
    private Consumer<String> onOpenTerminal = null;   // set → an "Open terminal here" context item appears
    private final MenuItem openTerminalItem = new MenuItem("Open terminal here");

    public FileBrowserPane(FileSystem fs, String title) {
        this.fs = fs;
        this.bookmarks = PathBookmarks.load(bookmarkFile());
        getStyleClass().add("file-pane");
        setSpacing(6);
        setPadding(new Insets(6));
        breadcrumbBar.setHgap(2);
        breadcrumbBar.setVgap(2);
        breadcrumbBar.getStyleClass().add("breadcrumb-bar");
        getChildren().addAll(buildHeader(title), buildAddressBar(), breadcrumbBar, buildTable(), statusLabel);
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

    /**
     * Notified when files dragged from the <em>other</em> pane are dropped onto this one: the items, the
     * destination directory (a folder row under the cursor, else this pane's current directory), and whether
     * the gesture was a move (the platform's move modifier, e.g. Shift) rather than a plain copy.
     */
    public void setOnDropFromOther(CrossPaneDrop handler) {
        this.onDropFromOther = handler == null ? (items, dir, move) -> {} : handler;
    }

    /**
     * Notified when entries dragged from <em>this</em> pane are dropped onto one of its own folder rows: the
     * items and the target folder. Such a move stays on one file system, so the container performs it as a
     * rename rather than a copy-and-delete (see {@link SameSideMove}).
     */
    public void setOnSameSideMove(BiConsumer<List<FileItem>, String> handler) {
        this.onSameSideMove = handler == null ? (items, dir) -> {} : handler;
    }

    /**
     * Notified when files are dragged in from the OS file manager and dropped onto this pane: the local
     * paths and the destination directory (a folder row under the cursor, else the current directory).
     */
    public void setOnExternalDrop(BiConsumer<List<Path>, String> handler) {
        this.onExternalDrop = handler == null ? (paths, dir) -> {} : handler;
    }

    /** Notified when this pane's table gains keyboard focus, so a container can track the active pane. */
    public void setOnFocused(Runnable handler) { this.onFocused = handler == null ? () -> {} : handler; }

    /**
     * Enables an "Open terminal here" context action that hands the current directory to {@code handler}
     * (e.g. the SFTP view opening an SSH terminal cd'd to that path). Passing {@code null} hides it.
     */
    public void setOnOpenTerminal(Consumer<String> handler) {
        this.onOpenTerminal = handler;
        openTerminalItem.setVisible(handler != null);
    }

    /** Moves keyboard focus to this pane's file table (used when switching panes with Tab). */
    public void focusTable() { table.requestFocus(); }

    public String currentPath() { return currentPath; }

    public List<FileItem> selected() { return List.copyOf(table.getSelectionModel().getSelectedItems()); }

    /** The full current directory listing (including the synthetic ".." row) as last loaded. */
    public List<FileItem> currentListing() { return List.copyOf(listing); }

    private Label buildHeader(String title) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        return l;
    }

    private HBox buildAddressBar() {
        backButton.getStyleClass().add("btn-secondary");
        backButton.setTooltip(new Tooltip("Back"));
        backButton.setOnAction(e -> goBack());
        forwardButton.getStyleClass().add("btn-secondary");
        forwardButton.setTooltip(new Tooltip("Forward"));
        forwardButton.setOnAction(e -> goForward());
        backButton.setDisable(true);
        forwardButton.setDisable(true);

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

        // Quick filter: type to narrow the listing by name; the toggle reveals dotfiles.
        filterField.getStyleClass().add("nl-field");
        filterField.setPromptText("Filter…");
        filterField.setPrefWidth(140);
        filterField.textProperty().addListener((o, ov, nv) -> applyView());
        showHidden.getStyleClass().add("btn-secondary");
        showHidden.setTooltip(new Tooltip("Show hidden (dot) files"));
        showHidden.setOnAction(e -> applyView());

        bookmarksButton.getStyleClass().add("btn-secondary");
        bookmarksButton.setTooltip(new Tooltip("Bookmark folders and jump back to them"));
        bookmarksButton.setOnShowing(e -> rebuildBookmarksMenu());

        HBox bar = new HBox(6, backButton, forwardButton, up, refresh, addressField,
                filterField, showHidden, bookmarksButton, mkdir);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    @SuppressWarnings("unchecked")
    private TableView<FileItem> buildTable() {
        TableColumn<FileItem, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().directory() ? "📁 " : "📄 ") + c.getValue().name()));
        name.setPrefWidth(260);
        name.setUserData(FileOrder.SortKey.NAME);

        TableColumn<FileItem, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sizeText()));
        size.setStyle("-fx-alignment: CENTER-RIGHT;");
        size.setPrefWidth(90);
        size.setUserData(FileOrder.SortKey.SIZE);

        TableColumn<FileItem, String> modified = new TableColumn<>("Modified");
        modified.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().modified()));
        modified.setPrefWidth(140);
        modified.setUserData(FileOrder.SortKey.MODIFIED);

        TableColumn<FileItem, String> perms = new TableColumn<>("Permissions");
        perms.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().permissions()));
        perms.setPrefWidth(110);
        perms.setUserData(FileOrder.SortKey.PERMISSIONS);

        table.getColumns().setAll(name, size, modified, perms);
        // Click-to-sort: the ".." row and dirs-first grouping are always preserved; the clicked column
        // only picks the key + direction. A single sort policy backs both the default and user sorts.
        table.setSortPolicy(FileBrowserPane::applyCommanderSort);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        // Reflect the current selection (count + total size) in the status line.
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<FileItem>) c -> updateSelectionStatus());
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
                // Folders are draggable too: cross-pane they copy recursively, same-side they rename-move.
                List<FileItem> sel = selected().stream().filter(f -> !f.parent()).toList();
                if (sel.isEmpty()) return;
                // Offer both modes so the platform's copy/move modifier (e.g. Shift) can pick at drop time.
                Dragboard db = row.startDragAndDrop(TransferMode.COPY_OR_MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(FILE_DRAG, sel.size());
                content.putString(sel.size() + " file(s)");
                // Local files exist on disk → also expose them as OS files so they can be dragged out
                // to the desktop / another app. Remote paths don't resolve, so this is a no-op there.
                List<File> realFiles = sel.stream().map(f -> new File(f.path())).filter(File::exists).toList();
                if (!realFiles.isEmpty()) content.putFiles(realFiles);
                db.setContent(content);
                draggedItems = sel;
                dragSource = FileBrowserPane.this;
                e.consume();
            });
            wireRowDropTarget(row);
            return row;
        });
        wireDropTarget();
        table.focusedProperty().addListener((o, was, now) -> { if (now) onFocused.run(); });
        table.setOnKeyPressed(e -> {
            FileItem sel = table.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && sel != null) activate(sel);
            else if (e.getCode() == KeyCode.DELETE && sel != null) deleteSelected();
            else if (e.getCode() == KeyCode.F2 && sel != null) renameSelected();
            else if (e.getCode() == KeyCode.C && e.isShortcutDown() && e.isShiftDown()) copyPathSelected();
        });
        table.setContextMenu(buildContextMenu());
        return table;
    }

    private ContextMenu buildContextMenu() {
        MenuItem open = new MenuItem("Open / Transfer");
        open.setOnAction(e -> { FileItem s = table.getSelectionModel().getSelectedItem(); if (s != null) activate(s); });
        MenuItem quickView = new MenuItem("Quick view / Edit…");
        quickView.setOnAction(e -> quickViewSelected());
        MenuItem rename = new MenuItem("Rename…  (F2)");
        rename.setOnAction(e -> renameSelected());
        MenuItem batchRename = new MenuItem("Batch rename…");
        batchRename.setOnAction(e -> batchRenameSelected());
        MenuItem duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(e -> duplicateSelected());
        MenuItem delete = new MenuItem("Delete  (Del)");
        delete.setOnAction(e -> deleteSelected());
        MenuItem copyPath = new MenuItem("Copy path  (Ctrl+Shift+C)");
        copyPath.setOnAction(e -> copyPathSelected());
        MenuItem properties = new MenuItem("Properties…");
        properties.setOnAction(e -> showProperties());
        MenuItem mkdir = new MenuItem("New Folder…");
        mkdir.setOnAction(e -> newFolder());
        MenuItem refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> refresh());
        openTerminalItem.setVisible(false);
        openTerminalItem.setOnAction(e -> { if (onOpenTerminal != null) onOpenTerminal.accept(currentPath); });
        ContextMenu menu = new ContextMenu(open, quickView, new SeparatorMenuItem(), rename, batchRename, duplicate, delete, copyPath, mkdir, new SeparatorMenuItem(), properties, refresh, openTerminalItem);
        // Only offer quick view when this file system can read/write content in place.
        quickView.setDisable(!fs.supportsContentAccess());
        // Only offer duplicate when this file system can copy an entry in place.
        duplicate.setDisable(!fs.supportsCopy());
        if (fs.supportsChmod()) {
            MenuItem chmod = new MenuItem("Permissions (chmod)…");
            chmod.setOnAction(e -> chmodSelected());
            menu.getItems().add(chmod);
        }
        return menu;
    }

    /**
     * Table-level drop target: accepts files dragged from the other pane <em>or</em> from the OS file
     * manager, dropping them into this pane's current directory (drops onto a folder row are handled at
     * the row level and never reach here). Highlights the whole table while hovering over empty space.
     */
    private void wireDropTarget() {
        // The table body drops into the current directory, so it only accepts drags from elsewhere — dropping
        // this pane's own selection into its own current folder would be a no-op (that is a same-side move,
        // and only makes sense onto a different folder row, handled below).
        table.setOnDragOver(e -> {
            if (acceptsFromElsewhere(e.getDragboard())) e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            e.consume();
        });
        table.setOnDragEntered(e -> {
            if (acceptsFromElsewhere(e.getDragboard()) && !table.getStyleClass().contains("drop-target")) {
                table.getStyleClass().add("drop-target");
            }
            e.consume();
        });
        table.setOnDragExited(e -> { table.getStyleClass().remove("drop-target"); e.consume(); });
        table.setOnDragDropped(e -> {
            boolean ok = handleDrop(e.getDragboard(), currentPath, e.getTransferMode() == TransferMode.MOVE);
            table.getStyleClass().remove("drop-target");
            e.setDropCompleted(ok);
            e.consume();
        });
        table.setOnDragDone(e -> { draggedItems = null; dragSource = null; e.consume(); });
    }

    /**
     * Row-level drop target so files can be dropped directly onto a <em>folder row</em> (not only the
     * pane's current directory). Non-folder rows don't consume the drag, so it falls through to the
     * table-level handler and lands in the current directory.
     */
    private void wireRowDropTarget(TableRow<FileItem> row) {
        row.setOnDragOver(e -> {
            if (!isFolderDropTarget(row)) return;
            Dragboard db = e.getDragboard();
            // A folder row also accepts this pane's OWN drag — dropping onto a different folder is a move.
            if (isOwnDrag(db)) { e.acceptTransferModes(TransferMode.MOVE); e.consume(); }
            else if (acceptsFromElsewhere(db)) { e.acceptTransferModes(TransferMode.COPY_OR_MOVE); e.consume(); }
        });
        row.setOnDragEntered(e -> {
            if (isFolderDropTarget(row) && (isOwnDrag(e.getDragboard()) || acceptsFromElsewhere(e.getDragboard()))) {
                if (!row.getStyleClass().contains("drop-target")) row.getStyleClass().add("drop-target");
                e.consume();
            }
        });
        row.setOnDragExited(e -> { row.getStyleClass().remove("drop-target"); });
        row.setOnDragDropped(e -> {
            if (!isFolderDropTarget(row)) return;   // let the table-level handler take it
            boolean ok = handleDrop(e.getDragboard(), row.getItem().path(), e.getTransferMode() == TransferMode.MOVE);
            row.getStyleClass().remove("drop-target");
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /** A real directory row (not empty, not the ".." row) that a drop can target. */
    private boolean isFolderDropTarget(TableRow<FileItem> row) {
        return !row.isEmpty() && row.getItem() != null
                && row.getItem().directory() && !row.getItem().parent();
    }

    /** True if the dragboard carries files from the <em>other</em> pane or from the OS (not our own drag). */
    private boolean acceptsFromElsewhere(Dragboard db) {
        boolean fromOtherPane = dragSource != null && dragSource != this && db.hasContent(FILE_DRAG);
        return fromOtherPane || db.hasFiles();
    }

    /** True if this drag originated in this very pane (a candidate same-side rename-move). */
    private boolean isOwnDrag(Dragboard db) {
        return dragSource == this && db.hasContent(FILE_DRAG) && draggedItems != null;
    }

    /**
     * Routes a drop into {@code destDir}: this pane's own items become a same-side move, items from the other
     * pane go to the transfer handler ({@code move} = the platform move modifier was held), and OS files are
     * uploaded/copied.
     */
    private boolean handleDrop(Dragboard db, String destDir, boolean move) {
        if (isOwnDrag(db)) {
            onSameSideMove.accept(List.copyOf(draggedItems), destDir);
            return true;
        }
        if (dragSource != null && dragSource != this && db.hasContent(FILE_DRAG) && draggedItems != null) {
            onDropFromOther.accept(List.copyOf(draggedItems), destDir, move);
            return true;
        }
        if (db.hasFiles()) {
            List<Path> paths = db.getFiles().stream().map(File::toPath).toList();
            if (!paths.isEmpty()) { onExternalDrop.accept(paths, destDir); return true; }
        }
        return false;
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
        navigateTo(path, true);
    }

    /**
     * Lists {@code path} and renders it. When {@code record} is true the successful navigation is pushed
     * onto the back/forward {@link NavHistory}; {@link #goBack()}/{@link #goForward()} pass false so that
     * replaying a history entry doesn't itself rewrite the history.
     */
    private void navigateTo(String path, boolean record) {
        runBg("list " + path, () -> {
            List<FileItem> items = fs.list(path);
            return new Object[]{path, items, fs.diskSpace(path)};
        }, result -> {
            Object[] r = (Object[]) result;
            currentPath = (String) r[0];
            addressField.setText(currentPath);
            updateBreadcrumbs();
            if (record) history.visit(currentPath);
            updateNavButtons();
            @SuppressWarnings("unchecked")
            List<FileItem> items = (List<FileItem>) r[1];
            @SuppressWarnings("unchecked")
            var space = (java.util.Optional<DiskSpace>) r[2];
            diskSpace = space.orElse(null);
            var rows = new java.util.ArrayList<FileItem>(items.size() + 1);
            rows.add(FileItem.up(fs.parent(currentPath)));
            rows.addAll(items);
            listing = rows;
            applyView();
            onChanged.run();
        });
    }

    private void goBack() {
        String p = history.back();
        if (p != null) navigateTo(p, false);
    }

    private void goForward() {
        String p = history.forward();
        if (p != null) navigateTo(p, false);
    }

    /** Enables/disables the back/forward buttons to match the current history cursor. */
    private void updateNavButtons() {
        backButton.setDisable(!history.canGoBack());
        forwardButton.setDisable(!history.canGoForward());
    }

    /**
     * Renders the current {@link #listing} into the table, applying the hidden-files toggle and the
     * quick-filter text ({@link FileFilter}) then the active sort policy. Called on navigation and
     * whenever a filter control changes, so filtering never re-hits the file system.
     */
    private void applyView() {
        List<FileItem> shown = FileFilter.apply(listing, showHidden.isSelected(), filterField.getText());
        // Order via the sort policy so a column the user clicked persists across navigation/filtering;
        // with no column selected it falls back to the ".."/dirs-first/name default.
        table.setItems(FXCollections.observableArrayList(shown));
        table.sort();
        long total = listing.stream().filter(f -> !f.parent()).count();
        long visible = shown.stream().filter(f -> !f.parent()).count();
        baseStatus = visible == total
                ? total + " item(s)"
                : "showing " + visible + " of " + total + " item(s)";
        if (diskSpace != null) baseStatus += "  ·  " + diskSpace.summary();
        statusLabel.setText(baseStatus);
    }

    /**
     * Shows the current selection's count and combined file size in the status line (a commander
     * convention), falling back to the plain item-count text ({@link #baseStatus}) when nothing —
     * other than the ".." row — is selected.
     */
    private void updateSelectionStatus() {
        List<FileItem> sel = table.getSelectionModel().getSelectedItems().stream()
                .filter(f -> f != null && !f.parent()).toList();
        if (sel.isEmpty()) {
            statusLabel.setText(baseStatus);
            return;
        }
        long bytes = sel.stream().filter(f -> !f.directory()).mapToLong(FileItem::size).sum();
        statusLabel.setText(sel.size() + " selected · " + FileItem.humanSize(bytes));
    }

    public void refresh() { navigateTo(currentPath); }

    /** Rebuilds the clickable breadcrumb trail for {@link #currentPath}; each segment navigates to it. */
    private void updateBreadcrumbs() {
        breadcrumbBar.getChildren().clear();
        var crumbs = PathCrumbs.of(currentPath, fs::parent);
        for (int i = 0; i < crumbs.size(); i++) {
            PathCrumbs.Crumb crumb = crumbs.get(i);
            Hyperlink link = new Hyperlink(crumb.label());
            link.getStyleClass().add("breadcrumb-link");
            link.setOnAction(e -> navigateTo(crumb.path()));
            breadcrumbBar.getChildren().add(link);
            if (i < crumbs.size() - 1) {
                Label sep = new Label("›");
                sep.getStyleClass().add("meta-label");
                breadcrumbBar.getChildren().add(sep);
            }
        }
    }

    /**
     * Puts the pane into a "not connected" state: clears the current listing and shows {@code message}
     * as both the table placeholder and the status line. Used by the {@link DualPaneBrowser} so the
     * remote pane reads "not connected" before a server connection rather than appearing empty.
     */
    public void showDisconnected(String message) {
        currentPath = "/";
        addressField.clear();
        filterField.clear();
        listing = List.of();
        diskSpace = null;
        breadcrumbBar.getChildren().clear();
        history.clear();
        updateNavButtons();
        table.setItems(FXCollections.observableArrayList());
        table.setPlaceholder(new Label(message));
        statusLabel.setText(message);
    }

    /** Copies the full path(s) of the current selection (excluding "..") to the system clipboard. */
    public void copyPathSelected() {
        List<FileItem> sel = selected();
        sel.removeIf(FileItem::parent);
        if (sel.isEmpty()) return;
        String joined = sel.stream().map(FileItem::path).collect(java.util.stream.Collectors.joining("\n"));
        ClipboardContent content = new ClipboardContent();
        content.putString(joined);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied " + sel.size() + " path(s) to clipboard");
    }

    /**
     * Rebuilds the bookmarks dropdown each time it opens: a toggle to bookmark (or un-bookmark) the
     * current folder, then one entry per saved bookmark that navigates to it. Bookmarks persist per
     * file-system name under {@code ~/.nexuslink}.
     */
    private void rebuildBookmarksMenu() {
        bookmarksButton.getItems().clear();
        boolean marked = bookmarks.contains(currentPath);
        MenuItem toggle = new MenuItem(marked ? "★ Remove this bookmark" : "☆ Bookmark this folder");
        toggle.setOnAction(e -> {
            if (marked) bookmarks.remove(currentPath); else bookmarks.add(null, currentPath);
            persistBookmarks();
        });
        bookmarksButton.getItems().add(toggle);
        if (bookmarks.size() > 0) {
            bookmarksButton.getItems().add(new SeparatorMenuItem());
            for (PathBookmarks.Bookmark b : bookmarks.list()) {
                MenuItem mi = new MenuItem(b.label() + "  —  " + b.path());
                mi.setOnAction(e -> navigateTo(b.path()));
                bookmarksButton.getItems().add(mi);
            }
        }
    }

    private void persistBookmarks() {
        try {
            bookmarks.save(bookmarkFile());
        } catch (Exception ex) {
            logger.accept(fs.name() + ": could not save bookmarks: " + ex.getMessage());
        }
    }

    /** The per-file-system bookmarks file, e.g. {@code ~/.nexuslink/bookmarks-Local.txt}. */
    private java.nio.file.Path bookmarkFile() {
        String safe = fs.name().replaceAll("[^A-Za-z0-9_.-]", "_");
        return java.nio.file.Path.of(System.getProperty("user.home"), ".nexuslink", "bookmarks-" + safe + ".txt");
    }

    /** Opens the quick-view / edit-in-place dialog for the selected file (text editable, images preview). */
    public void quickViewSelected() {
        FileItem sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.parent() || sel.directory()) return;
        if (!fs.supportsContentAccess()) { statusLabel.setText(fs.name() + " has no quick view"); return; }
        Window owner = getScene() == null ? null : getScene().getWindow();
        QuickViewDialog.open(owner, fs, sel, logger, this::refresh);
    }

    /** Shows a read-only Properties dialog (name/type/path/size/modified/permissions) for the selection. */
    public void showProperties() {
        FileItem sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.parent()) return;
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        int row = 0;
        for (FileDetails.Row r : FileDetails.of(sel)) {
            Label label = new Label(r.label() + ":");
            label.getStyleClass().add("meta-label");
            Label value = new Label(r.value());
            value.setWrapText(true);
            value.setMaxWidth(360);
            grid.add(label, 0, row);
            grid.add(value, 1, row);
            row++;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Properties");
        dialog.setHeaderText(sel.name());
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        register(dialog.getDialogPane());
        dialog.showAndWait();
    }

    public void newFolder() {
        TextInputDialog d = themedInput("new-folder", "New Folder", "Folder name:");
        d.showAndWait().ifPresent(nameRaw -> {
            String nm = nameRaw.trim();
            if (nm.isEmpty()) return;
            runBg("mkdir " + nm, () -> { fs.mkdir(fs.join(currentPath, nm)); return null; }, r -> refresh());
        });
    }

    public void renameSelected() {
        FileItem sel = table.getSelectionModel().getSelectedItem();
        if (sel == null || sel.parent()) return;
        TextInputDialog d = themedInput(sel.name(), "Rename", "New name:");
        d.showAndWait().ifPresent(nameRaw -> {
            String nm = nameRaw.trim();
            if (nm.isEmpty() || nm.equals(sel.name())) return;
            runBg("rename " + sel.name(), () -> { fs.rename(sel.path(), fs.join(currentPath, nm)); return null; }, r -> refresh());
        });
    }

    /**
     * Opens the {@link BatchRenameDialog} for the current multi-selection and applies the resulting
     * renames sequentially in the background. Only rows whose name actually changed are renamed; the
     * dialog already blocks committing a plan with colliding targets. Falls back to single-file
     * {@link #renameSelected()} semantics when the user selects just one item and prefers a plain rename.
     */
    public void batchRenameSelected() {
        List<FileItem> sel = selected();
        sel.removeIf(FileItem::parent);
        if (sel.isEmpty()) return;
        List<String> names = sel.stream().map(FileItem::name).toList();
        Window owner = getScene() == null ? null : getScene().getWindow();
        BatchRenameDialog.open(owner, names).ifPresent(results -> {
            // Map each source name to its full path so we can rename by absolute path.
            var byName = new java.util.HashMap<String, FileItem>();
            for (FileItem f : sel) byName.putIfAbsent(f.name(), f);
            var renames = results.stream()
                    .filter(r -> !r.from().equals(r.to()) && byName.containsKey(r.from()))
                    .toList();
            if (renames.isEmpty()) return;
            runBg("batch rename " + renames.size(), () -> {
                for (BulkRename.Result r : renames) {
                    FileItem src = byName.get(r.from());
                    fs.rename(src.path(), fs.join(currentPath, r.to()));
                }
                return null;
            }, r -> refresh());
        });
    }

    /**
     * Duplicates each selected entry in place under an auto-suffixed, non-colliding name
     * ({@code report.txt} → {@code report copy.txt}, then {@code report copy 2.txt}, …). Names are
     * reserved across the batch so duplicating several files at once never collides, and the running
     * set is seeded from the current listing. Runs the copies sequentially off the FX thread.
     */
    public void duplicateSelected() {
        if (!fs.supportsCopy()) { statusLabel.setText(fs.name() + " cannot duplicate"); return; }
        List<FileItem> sel = selected();
        sel.removeIf(FileItem::parent);
        if (sel.isEmpty()) return;
        // Seed the "taken" names from the current directory so we skip existing entries, and grow it as
        // we mint each duplicate name so a multi-file duplicate can't target the same name twice.
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (FileItem f : listing) taken.add(f.name());
        var plan = new java.util.ArrayList<java.util.Map.Entry<FileItem, String>>();
        for (FileItem src : sel) {
            String dup = DuplicateName.of(src.name(), taken);
            taken.add(dup);
            plan.add(java.util.Map.entry(src, dup));
        }
        runBg("duplicate " + plan.size(), () -> {
            for (var e : plan) fs.copy(e.getKey(), currentPath, e.getValue());
            return null;
        }, r -> refresh());
    }

    public void deleteSelected() {
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

    /**
     * Sort policy shared by the default listing and header clicks: reads the first column in the
     * table's sort order (its {@link FileOrder.SortKey} and ascending/descending) and reorders the
     * items with {@link FileOrder#by}, which keeps ".." first and directories before files. When no
     * column is selected it defaults to a case-insensitive name sort.
     */
    private static boolean applyCommanderSort(TableView<FileItem> tv) {
        FileOrder.SortKey key = FileOrder.SortKey.NAME;
        boolean ascending = true;
        if (!tv.getSortOrder().isEmpty()) {
            TableColumn<FileItem, ?> col = tv.getSortOrder().get(0);
            if (col.getUserData() instanceof FileOrder.SortKey k) key = k;
            ascending = col.getSortType() != TableColumn.SortType.DESCENDING;
        }
        FXCollections.sort(tv.getItems(), FileOrder.by(key, ascending));
        return true;
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
