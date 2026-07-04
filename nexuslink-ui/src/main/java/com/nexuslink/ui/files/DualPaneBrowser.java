package com.nexuslink.ui.files;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The WinSCP/MobaXterm-style two-pane file commander: the local disk on the left, a remote
 * service on the right, with Upload → / ← Download controls in between. Transfers are funnelled
 * through a {@link TransferQueue} and shown live in a collapsible {@link TransferQueuePanel} along
 * the bottom; a target that already exists raises an Overwrite / Skip / …all prompt.
 * Double-clicking a file in either pane enqueues it to the other side's current directory.
 */
public final class DualPaneBrowser extends BorderPane {

    private final FileSystem local;
    private final FileSystem remote;
    private final FileBrowserPane localPane;
    private final FileBrowserPane remotePane;
    private final TransferQueue queue;
    private final TransferQueuePanel queuePanel;

    private final Label messageLabel = new Label();
    private Consumer<String> logger = s -> {};
    private boolean remoteConnected = false;

    // Synchronized browsing: mirror each pane's navigation onto the other by relative path.
    private boolean syncBrowsing = false;
    private int suppressSync = 0;               // >0 while a mirrored navigation is in flight
    private String lastLocalPath = "";
    private String lastRemotePath = "";

    // The pane the Norton-Commander function keys act on — whichever last held keyboard focus.
    private FileBrowserPane activePane;

    public DualPaneBrowser(FileSystem local, FileSystem remote, FileTransfer transfer) {
        this.local = local;
        this.remote = remote;
        this.localPane = new FileBrowserPane(local, "Local — " + local.name());
        this.remotePane = new FileBrowserPane(remote, "Remote — " + remote.name());
        this.queue = new TransferQueue(local, remote, transfer);
        this.queuePanel = new TransferQueuePanel(queue);
        getStyleClass().add("dual-pane-browser");

        // Double-click a file → transfer it to the opposite pane's directory.
        localPane.setOnActivateFile(f -> upload(List.of(f)));
        remotePane.setOnActivateFile(f -> download(List.of(f)));

        // Drag-and-drop: drop local files onto the remote pane to upload, and vice versa.
        remotePane.setOnDropFromOther(this::upload);
        localPane.setOnDropFromOther(this::download);

        // Synchronized browsing: when one pane navigates, mirror the relative move onto the other.
        localPane.setOnChanged(() -> onPaneNavigated(true));
        remotePane.setOnChanged(() -> onPaneNavigated(false));

        // Track which pane the Norton-Commander function keys should act on (last focused).
        this.activePane = localPane;
        localPane.setOnFocused(() -> activePane = localPane);
        remotePane.setOnFocused(() -> activePane = remotePane);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onCommanderKey);

        // Refresh the destination pane each time a transfer completes.
        queue.setOnItemFinished(item -> {
            if (item.status() != TransferStatus.DONE) return;
            Platform.runLater(() -> {
                if (item.direction() == TransferItem.Direction.UPLOAD) remotePane.refresh();
                else localPane.refresh();
            });
        });
        queue.startWorker();

        SplitPane split = new SplitPane(localPane, buildTransferColumn(), remotePane);
        split.setDividerPositions(0.43, 0.57);
        setCenter(split);
        setBottom(buildBottom());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        localPane.setLogger(this.logger);
        remotePane.setLogger(this.logger);
    }

    /** Loads the local home in the left pane and the remote home in the right pane. */
    public void start() {
        startLocal();
        connectRemote();
    }

    /**
     * Loads just the local pane's home directory. Call as soon as the commander is shown so the local
     * file tree is browsable immediately, before any server connection — the WinSCP/MobaXterm feel
     * where the local pane is always present.
     */
    public void startLocal() {
        localPane.loadHome();
    }

    /** Marks the remote side connected and loads its home directory into the right pane. */
    public void connectRemote() {
        remoteConnected = true;
        remotePane.loadHome();
    }

    /**
     * Marks the remote side disconnected and shows a "not connected" placeholder in the right pane.
     * The local pane keeps its current directory, so local navigation survives connect/disconnect.
     */
    public void disconnectRemote() {
        remoteConnected = false;
        remotePane.showDisconnected("Not connected — use Connect above to browse the remote server");
    }

    private VBox buildTransferColumn() {
        Button upload = new Button("Upload →");
        upload.getStyleClass().add("btn-primary");
        upload.setTooltip(new javafx.scene.control.Tooltip("Send the selected local files to the remote directory"));
        upload.setOnAction(e -> upload(localPane.selected()));

        Button download = new Button("← Download");
        download.getStyleClass().add("btn-primary");
        download.setTooltip(new javafx.scene.control.Tooltip("Fetch the selected remote files to the local directory"));
        download.setOnAction(e -> download(remotePane.selected()));

        ToggleButton sync = new ToggleButton("⇄ Sync");
        sync.getStyleClass().add("btn-secondary");
        sync.setTooltip(new Tooltip("Synchronized browsing: mirror navigation across both panes by relative path"));
        sync.selectedProperty().addListener((o, was, now) -> {
            syncBrowsing = now;
            // Snapshot current paths so enabling sync doesn't immediately jump either pane.
            lastLocalPath = localPane.currentPath();
            lastRemotePath = remotePane.currentPath();
            messageLabel.setText(now ? "Synchronized browsing on" : "Synchronized browsing off");
        });

        Button compare = new Button("⇋ Compare");
        compare.getStyleClass().add("btn-secondary");
        compare.setTooltip(new Tooltip("Compare both directories and optionally sync (copy/delete) the differences"));
        compare.setOnAction(e -> compareDirectories());

        VBox col = new VBox(12, upload, download, sync, compare);
        col.setAlignment(Pos.CENTER);
        col.setPadding(new Insets(40, 6, 6, 6));
        col.setMinWidth(120);
        return col;
    }

    /**
     * Called after either pane finishes navigating. In synchronized-browsing mode, replays the same
     * relative move on the other pane via {@link SyncBrowsing#mirror}. A suppression counter breaks
     * the feedback loop: each mirrored navigation fires the other pane's change once, which is then
     * swallowed instead of bouncing back.
     */
    private void onPaneNavigated(boolean isLocal) {
        String current = (isLocal ? localPane : remotePane).currentPath();
        if (suppressSync > 0) {                          // this change was our own mirrored navigation
            suppressSync--;
            if (isLocal) lastLocalPath = current; else lastRemotePath = current;
            return;
        }
        String old = isLocal ? lastLocalPath : lastRemotePath;
        if (isLocal) lastLocalPath = current; else lastRemotePath = current;

        if (!syncBrowsing || !remoteConnected) return;

        FileBrowserPane other = isLocal ? remotePane : localPane;
        String target = SyncBrowsing.mirror(other.currentPath(), old, current);
        if (target != null && !target.equals(other.currentPath())) {
            suppressSync++;
            other.navigateTo(target);
        }
    }

    /**
     * Norton-Commander function keys, handled for the whole commander: F5 copy the active pane's
     * selection to the other pane, F6 rename, F7 new folder, F8 delete, and Tab to switch panes.
     */
    private void onCommanderKey(KeyEvent e) {
        // Don't hijack Tab / function keys while the user is editing the address or filter field.
        if (getScene() != null
                && getScene().getFocusOwner() instanceof javafx.scene.control.TextInputControl) {
            return;
        }
        switch (e.getCode()) {
            case F5 -> copyActiveToOther();
            case F6 -> activePane.renameSelected();
            case F7 -> activePane.newFolder();
            case F8 -> activePane.deleteSelected();
            case TAB -> switchPane();
            default -> { return; }                       // leave every other key to the focused control
        }
        e.consume();
    }

    /** Copies the active pane's current selection to the opposite pane's directory. */
    private void copyActiveToOther() {
        if (activePane == remotePane) download(remotePane.selected());
        else upload(localPane.selected());
    }

    /** Moves keyboard focus (and the active-pane marker) to the opposite pane. */
    private void switchPane() {
        FileBrowserPane target = activePane == localPane ? remotePane : localPane;
        activePane = target;
        target.focusTable();
    }

    private HBox buildFunctionBar() {
        HBox bar = new HBox(6,
                fnKey("F5 Copy", this::copyActiveToOther),
                fnKey("F6 Rename", () -> activePane.renameSelected()),
                fnKey("F7 MkDir", () -> activePane.newFolder()),
                fnKey("F8 Delete", () -> activePane.deleteSelected()),
                fnKey("Tab Switch", this::switchPane));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Button fnKey(String label, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().add("btn-secondary");
        b.setFocusTraversable(false);                    // keep focus on the file table so the key acts there
        b.setOnAction(e -> action.run());
        return b;
    }

    private VBox buildBottom() {
        messageLabel.getStyleClass().add("meta-label");
        VBox box = new VBox(4, buildFunctionBar(), messageLabel, queuePanel);
        box.setPadding(new Insets(6, 8, 6, 8));
        return box;
    }

    /**
     * Opens the {@link DirectoryCompareDialog} over both panes' current listings and, if the user runs a
     * sync, executes the resulting {@link SyncPlanner} plan: copies flow through the transfer queue (with
     * the usual overwrite prompt) and deletes are confirmed once, then applied off the FX thread.
     */
    private void compareDirectories() {
        if (!remoteConnected) {
            messageLabel.setText("Connect to a remote server before comparing directories");
            return;
        }
        Window owner = getScene() == null ? null : getScene().getWindow();
        DirectoryCompareDialog.open(owner, localPane.currentListing(), remotePane.currentListing(),
                "Local — " + local.name(), "Remote — " + remote.name())
                .ifPresent(this::runSyncPlan);
    }

    private void runSyncPlan(List<SyncPlanner.Action> plan) {
        java.util.List<FileItem> uploads = new java.util.ArrayList<>();
        java.util.List<FileItem> downloads = new java.util.ArrayList<>();
        java.util.List<SyncPlanner.Action> deletes = new java.util.ArrayList<>();
        for (SyncPlanner.Action a : plan) {
            switch (a.op()) {
                case COPY_LEFT_TO_RIGHT -> uploads.add(a.item());
                case COPY_RIGHT_TO_LEFT -> downloads.add(a.item());
                case DELETE_LEFT, DELETE_RIGHT -> deletes.add(a);
            }
        }
        if (!uploads.isEmpty()) upload(uploads);
        if (!downloads.isEmpty()) download(downloads);
        if (!deletes.isEmpty()) confirmAndRunDeletes(deletes);
    }

    /** Confirms the delete count once, then deletes each victim on its own side off the FX thread. */
    private void confirmAndRunDeletes(List<SyncPlanner.Action> deletes) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Sync will delete " + deletes.size() + " item(s) that don't exist on the other side. "
                        + "This cannot be undone.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Confirm sync deletions");
        if (getScene() != null) {
            confirm.initOwner(getScene().getWindow());
            confirm.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
                if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
            });
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        Thread t = new Thread(() -> {
            int done = 0;
            for (SyncPlanner.Action a : deletes) {
                try {
                    (a.op() == SyncPlanner.Op.DELETE_LEFT ? local : remote).delete(a.item());
                    done++;
                } catch (Exception ex) {
                    logger.accept("Sync delete failed for " + a.name() + ": " + ex.getMessage());
                }
            }
            int finalDone = done;
            Platform.runLater(() -> {
                messageLabel.setText("Sync deleted " + finalDone + " of " + deletes.size() + " item(s)");
                localPane.refresh();
                remotePane.refresh();
            });
        }, "sync-delete");
        t.setDaemon(true);
        t.start();
    }

    private void upload(List<FileItem> items) {
        enqueue(items, TransferItem.Direction.UPLOAD);
    }

    private void download(List<FileItem> items) {
        enqueue(items, TransferItem.Direction.DOWNLOAD);
    }

    /** Enqueues the selection (files and whole folders) toward the opposite pane's directory. */
    private void enqueue(List<FileItem> itemsRaw, TransferItem.Direction direction) {
        if (!remoteConnected) {
            messageLabel.setText("Connect to a remote server before transferring files");
            return;
        }
        List<FileItem> items = itemsRaw.stream().filter(f -> !f.parent()).toList();
        if (items.isEmpty()) {
            messageLabel.setText("Select one or more files or folders to transfer");
            return;
        }
        String destDir = direction == TransferItem.Direction.UPLOAD
                ? remotePane.currentPath() : localPane.currentPath();
        // Each batch gets its own resolver so "Overwrite all / Skip all" applies only to this batch.
        OverwriteResolver resolver = new OverwriteResolver(this::promptOverwrite);
        queuePanel.setExpanded(true);

        boolean hasFolder = items.stream().anyMatch(FileItem::directory);
        if (!hasFolder) {
            reportQueued(queue.enqueue(direction, items, destDir, resolver).size(), direction);
            return;
        }
        // Whole-folder transfer: walk the tree + create destination directories off the FX thread.
        messageLabel.setText("Scanning folder(s)…");
        Thread t = new Thread(() -> {
            try {
                int n = queue.enqueueRecursive(direction, items, destDir, resolver).size();
                Platform.runLater(() -> reportQueued(n, direction));
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Platform.runLater(() -> {
                    messageLabel.setText("Folder scan failed: " + msg);
                    logger.accept("Folder scan failed: " + msg);
                });
            }
        }, "transfer-expand");
        t.setDaemon(true);
        t.start();
    }

    private void reportQueued(int count, TransferItem.Direction direction) {
        String verb = direction == TransferItem.Direction.UPLOAD ? "upload" : "download";
        messageLabel.setText("Queued " + count + " " + verb + "(s)");
        logger.accept("Queued " + count + " " + verb + "(s)");
    }

    /**
     * Shows the overwrite/skip dialog for a conflicting target. Called on the queue worker thread,
     * so it blocks until the user (on the FX thread) answers.
     */
    private OverwriteResolver.Choice promptOverwrite(String name) {
        AtomicReference<OverwriteResolver.Choice> result =
                new AtomicReference<>(OverwriteResolver.Choice.SKIP);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ButtonType overwrite = new ButtonType("Overwrite", ButtonBar.ButtonData.YES);
                ButtonType overwriteAll = new ButtonType("Overwrite all", ButtonBar.ButtonData.OTHER);
                ButtonType skip = new ButtonType("Skip", ButtonBar.ButtonData.NO);
                ButtonType skipAll = new ButtonType("Skip all", ButtonBar.ButtonData.OTHER);
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                        "\"" + name + "\" already exists in the target directory.",
                        overwrite, overwriteAll, skip, skipAll);
                alert.setHeaderText("File already exists");
                alert.setTitle("Overwrite?");
                if (getScene() != null) {
                    alert.initOwner(getScene().getWindow());
                    alert.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
                        if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
                    });
                }
                ButtonType picked = alert.showAndWait().orElse(skip);
                if (picked == overwrite) result.set(OverwriteResolver.Choice.OVERWRITE);
                else if (picked == overwriteAll) result.set(OverwriteResolver.Choice.OVERWRITE_ALL);
                else if (picked == skipAll) result.set(OverwriteResolver.Choice.SKIP_ALL);
                else result.set(OverwriteResolver.Choice.SKIP);
            } finally {
                latch.countDown();
            }
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result.get();
    }
}
